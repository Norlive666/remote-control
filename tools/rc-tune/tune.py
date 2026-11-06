#!/usr/bin/env python3
"""rc-tune —— 把 App 的控制帧写进 STM32 的全局结构体（默认 g_rc）。

链路：手机 App --20 字节帧/HTTP--> 本脚本 --DAPLink/SWD--> STM32 RAM

只做读改写：不 halt、不 reset、不擦写 Flash。写入前请架空机器人，
急停由 App 的急停按钮负责；断流超过 --watchdog-ms 时这里会写入安全值。
"""

from __future__ import annotations

import argparse
import hmac
import json
import socket
import struct
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

FRAME_SIZE = 20
VERSION = "rc-tune/1"
# 断流后写入的安全值：输出归零、标记为停止、断开 link_valid。
NEUTRAL = {"axes": (0, 0, 0, 0), "sliders": (0, 0, 0, 0), "enabled": 0, "stopped": 1, "link_valid": 0}


class TuneError(Exception):
    """可以直接展示给用户的错误。"""


class FrameError(TuneError):
    """控制帧本身不合法。"""


class WriteDisabled(TuneError):
    """桥接器以只读模式启动。"""


# --------------------------------------------------------------------- DWARF


class Member:
    __slots__ = ("id", "type", "size", "offset")

    def __init__(self, id, type, size, offset):
        self.id = id
        self.type = type
        self.size = size
        self.offset = offset

    def __repr__(self):
        return f"Member({self.id!r}, {self.type}, {self.size}, {self.offset})"

    def to_dict(self):
        return {"id": self.id, "type": self.type, "size": self.size, "offset": self.offset}


class StructLayout:
    def __init__(self, symbol, address, size, members):
        self.symbol = symbol
        self.address = address
        self.size = size
        self.members = list(members)
        self.by_id = {member.id: member for member in self.members}

    def group(self, name):
        """按成员名取一组成员：标量返回 1 个，数组 axes 返回 axes[0..n]。"""
        if name in self.by_id:
            return [self.by_id[name]]
        prefix = name + "["
        return sorted((m for m in self.members if m.id.startswith(prefix)), key=lambda m: m.offset)


WRAPPER_TAGS = (
    "DW_TAG_typedef",
    "DW_TAG_const_type",
    "DW_TAG_volatile_type",
    "DW_TAG_restrict_type",
    "DW_TAG_atomic_type",
)


def parse_layout(elf_path, symbol):
    """从 ELF 的 DWARF 里读出 symbol 的地址、大小和每个成员的类型/偏移。"""
    try:
        from elftools.elf.elffile import ELFFile
    except ImportError as exc:  # pragma: no cover - 依赖缺失时给出可操作提示
        raise TuneError("缺少 pyelftools：pip install pyelftools") from exc

    with open(elf_path, "rb") as handle:
        elf = ELFFile(handle)
        if not elf.has_dwarf_info():
            raise TuneError("ELF 里没有 DWARF 调试信息（编译时加 -g，且不要 strip）")
        dwarf = elf.get_dwarf_info()
        cu, die, address = _find_variable(dwarf, symbol)
        type_ref = _type_ref(cu, die)
        if type_ref is None:
            raise TuneError(f"{symbol} 的 DIE 没有类型信息（可能已被优化掉）")
        target = _unwrap(cu, type_ref)
        if target.tag != "DW_TAG_structure_type":
            raise TuneError(f"{symbol} 是 {target.tag}，不是结构体")
        members = _collect_members(cu, target)
        size_attr = target.attributes.get("DW_AT_byte_size")
        size = size_attr.value if size_attr is not None else max(m.offset + m.size for m in members)

    if not members:
        raise TuneError(f"{symbol} 没有可读成员")
    return StructLayout(symbol, address, size, members)


def _find_variable(dwarf, symbol):
    for cu in dwarf.iter_CUs():
        for die in cu.iter_DIEs():
            if die.tag != "DW_TAG_variable":
                continue
            if _variable_name(cu, die) != symbol:
                continue
            address = _static_address(die)
            if address is not None:
                return cu, die, address
    raise TuneError(f"ELF 里找不到全局变量 {symbol}（需要 -g，且不能已被优化掉）")


def _static_address(die):
    attr = die.attributes.get("DW_AT_location")
    if attr is None:
        return None
    raw = attr.value
    if isinstance(raw, int):
        return raw
    if isinstance(raw, (bytes, bytearray, memoryview)):
        # DW_FORM_exprloc 的原始字节：DW_OP_addr(0x03) + 目标地址。
        payload = bytes(raw)
        return int.from_bytes(payload[1:], "little") if len(payload) >= 5 and payload[0] == 0x03 else None
    if not len(raw):
        return None
    if isinstance(raw[0], int):
        # pyelftools 在无法解析表达式时给出的是字节序列（ListContainer[int]）。
        payload = bytes(raw)
        return int.from_bytes(payload[1:], "little") if len(payload) >= 5 and payload[0] == 0x03 else None
    for entry in raw:
        if entry[0] != "DW_OP_addr":
            return None
        value = entry[1]
        return int.from_bytes(value, "little") if isinstance(value, (bytes, bytearray)) else int(value)
    return None


_CU_RELATIVE_FORMS = frozenset({
    "DW_FORM_ref1",
    "DW_FORM_ref2",
    "DW_FORM_ref4",
    "DW_FORM_ref8",
    "DW_FORM_ref_udata",
})


def _die_from_ref(cu, attr):
    """把 DW_AT_type / DW_AT_specification 的引用解析成 DIE。

    ref1/2/4/8/ref_udata 是 CU 内偏移，必须先加 cu_offset；
    ref_addr 已经是 .debug_info 里的全局偏移。早期版本两种混用，
    所以全局偏移失败时再试一次 CU 内偏移。
    """
    value = attr.value
    if getattr(attr, "form", "") in _CU_RELATIVE_FORMS:
        return cu.get_DIE_from_refaddr(cu.cu_offset + value)
    try:
        return cu.get_DIE_from_refaddr(value)
    except Exception:
        return cu.get_DIE_from_refaddr(cu.cu_offset + value)


def _variable_name(cu, die):
    """变量的名字；定义 DIE 常只有 DW_AT_specification，名字写在声明 DIE 上。"""
    for _ in range(8):
        name = die.attributes.get("DW_AT_name")
        if name is not None:
            return name.value.decode("utf-8", "replace")
        ref = die.attributes.get("DW_AT_specification") or die.attributes.get("DW_AT_abstract_origin")
        if ref is None:
            return None
        die = _die_from_ref(cu, ref)
    return None


def _type_ref(cu, die):
    """DIE 的 DW_AT_type；定义 DIE 常常把类型也留给声明 DIE。"""
    for _ in range(8):
        attr = die.attributes.get("DW_AT_type")
        if attr is not None:
            return attr
        ref = die.attributes.get("DW_AT_specification") or die.attributes.get("DW_AT_abstract_origin")
        if ref is None:
            return None
        die = _die_from_ref(cu, ref)
    return None


def _unwrap(cu, ref):
    die = _die_from_ref(cu, ref)
    for _ in range(16):
        if die.tag not in WRAPPER_TAGS:
            return die
        inner = die.attributes.get("DW_AT_type")
        if inner is None:
            raise TuneError(f"{die.tag} 缺少底层类型")
        die = _die_from_ref(cu, inner)
    raise TuneError("类型嵌套过深")


def _collect_members(cu, struct_die, prefix=""):
    members = []
    for child in struct_die.iter_children():
        if child.tag != "DW_TAG_member" or "DW_AT_declaration" in child.attributes:
            continue
        if "DW_AT_bit_size" in child.attributes:
            raise TuneError("不支持位域成员")
        name_attr = child.attributes.get("DW_AT_name")
        if name_attr is None:  # 匿名成员/匿名联合体
            continue
        name = prefix + name_attr.value.decode("utf-8", "replace")
        offset = _member_offset(child)
        if offset is None:
            raise TuneError(f"成员 {name} 的偏移不是编译期常量")
        _flatten(cu, _unwrap(cu, child.attributes["DW_AT_type"]), name, offset, members)
    members.sort(key=lambda member: member.offset)
    return members


def _member_offset(die):
    attr = die.attributes.get("DW_AT_data_member_location")
    if attr is None:
        return 0
    raw = attr.value
    if isinstance(raw, int):
        return raw
    if isinstance(raw, (bytes, bytearray, memoryview)):
        return _plus_uconst(bytes(raw))
    if not len(raw):
        return 0
    if isinstance(raw[0], int):
        return _plus_uconst(bytes(raw))
    total = 0
    for entry in raw:
        if entry[0] in ("DW_OP_plus_uconst", "DW_OP_constu", "DW_OP_const1u", "DW_OP_const2u", "DW_OP_const4u"):
            total += int(entry[1])
        else:
            return None
    return total


def _plus_uconst(payload):
    """DW_OP_plus_uconst(0x23) + ULEB128，其余表达式一律不支持。"""
    if len(payload) < 2 or payload[0] != 0x23:
        return None
    return _uleb(payload, 1)[0]


def _uleb(data, index):
    result = 0
    shift = 0
    while index < len(data):
        byte = data[index]
        index += 1
        result |= (byte & 0x7F) << shift
        if not byte & 0x80:
            break
        shift += 7
    return result, index


def _flatten(cu, die, name, offset, out):
    if die.tag == "DW_TAG_base_type":
        out.append(Member(name, _base_type_name(die), die.attributes["DW_AT_byte_size"].value, offset))
    elif die.tag == "DW_TAG_enumeration_type":
        size = die.attributes["DW_AT_byte_size"].value
        out.append(Member(name, f"uint{size * 8}", size, offset))
    elif die.tag == "DW_TAG_pointer_type":
        out.append(Member(name, "ptr", die.attributes["DW_AT_byte_size"].value, offset))
    elif die.tag == "DW_TAG_array_type":
        element = _unwrap(cu, die.attributes["DW_AT_type"])
        ranges = [child for child in die.iter_children() if child.tag == "DW_TAG_subrange_type"]
        if len(ranges) != 1:
            raise TuneError(f"{name} 是多维数组或长度未知，暂不支持")
        count = _subrange_count(ranges[0], name)
        element_size = die_element_size(element)
        for index in range(count):
            _flatten(cu, element, f"{name}[{index}]", offset + index * element_size, out)
    elif die.tag == "DW_TAG_structure_type":
        for member in _collect_members(cu, die, prefix=name + "."):
            member.offset += offset
            out.append(member)
    else:
        raise TuneError(f"不支持的成员类型 {die.tag}（{name}）")


def _subrange_count(die, name):
    count = die.attributes.get("DW_AT_count")
    if count is not None:
        return int(count.value)
    upper = die.attributes.get("DW_AT_upper_bound")
    if upper is not None:
        return int(upper.value) + 1
    raise TuneError(f"{name} 的数组长度不是常量")


def die_element_size(die):
    size = die.attributes.get("DW_AT_byte_size")
    if size is None:
        raise TuneError(f"{die.tag} 缺少 DW_AT_byte_size")
    return size.value


def _base_type_name(die):
    encoding = die.attributes.get("DW_AT_encoding")
    encoding = 0 if encoding is None else (encoding.value & 0x0F)
    size = die.attributes["DW_AT_byte_size"].value
    if encoding == 0x02:
        return "bool"
    if encoding == 0x04:
        if size in (4, 8):
            return f"float{size * 8}"
        raise TuneError(f"不支持 {size} 字节浮点（{die.attributes.get('DW_AT_name')}）")
    if encoding in (0x05, 0x06):
        return f"int{size * 8}"
    if encoding in (0x07, 0x08):
        return f"uint{size * 8}"
    raise TuneError(f"不支持的基础类型 {die.attributes.get('DW_AT_name')}（{size} 字节）")


def classify_region(address):
    """STM32H723 的内存分区，用来提示 D-Cache 影响。"""
    if 0x20000000 <= address < 0x20020000:
        return "DTCM（不经过 D-Cache）"
    if 0x24000000 <= address < 0x24080000:
        return "AXI SRAM（可能经过 D-Cache）"
    if 0x30000000 <= address < 0x30048000:
        return "SRAM1-3"
    if 0x38000000 <= address < 0x38010000:
        return "SRAM4"
    return "未知区域"


# --------------------------------------------------------------------- 协议


def crc16(data, start=0, end=None):
    """CRC-16/CCITT-FALSE，与驱动和 App 的 ControlProtocol 一致。"""
    end = len(data) if end is None else end
    value = 0xFFFF
    for index in range(start, end):
        value ^= (data[index] & 0xFF) << 8
        for _ in range(8):
            value = ((value << 1) ^ 0x1021) & 0xFFFF if value & 0x8000 else (value << 1) & 0xFFFF
    return value


class Frame:
    __slots__ = ("seq", "axes", "sliders", "active", "stopped")

    def __init__(self, seq, axes, sliders, active, stopped):
        self.seq = seq
        self.axes = axes
        self.sliders = sliders
        self.active = active
        self.stopped = stopped


def decode_frame(data):
    if len(data) != FRAME_SIZE:
        raise FrameError(f"帧长必须是 {FRAME_SIZE} 字节（收到 {len(data)}）")
    if data[0:2] != b"\xa5\x5a":
        raise FrameError("帧头错误")
    version = data[2]
    if version not in (1, 2):
        raise FrameError(f"不支持的协议版本 {version}")
    seq = data[3]
    axes = list(struct.unpack_from("<4h", data, 4))
    sliders = [0, 0, 0, 0]
    if version == 2:
        packed = int.from_bytes(data[12:17], "little")
        sliders = [(packed >> (10 * index)) & 0x3FF for index in range(4)]
    flags = data[17]
    active = bool(flags & 0x01)
    stopped = bool(flags & 0x02)
    checksum = int.from_bytes(data[18:20], "little")
    if checksum != crc16(data, 2, 18):
        raise FrameError(f"CRC 校验失败（帧内 0x{checksum:04X}）")
    if stopped and active:
        raise FrameError("急停与使能同时置位")
    if not active and (any(axes) or any(sliders)):
        raise FrameError("未使能时轴和滑块必须为 0")
    return Frame(seq, axes, sliders, active, stopped)


def _encode_member(member, value):
    if member.type == "float32":
        return struct.pack("<f", float(value))
    if member.type == "float64":
        return struct.pack("<d", float(value))
    if member.type == "bool":
        return bytes([1 if value else 0])
    try:
        return int(value).to_bytes(member.size, "little", signed=member.type.startswith("int"))
    except (OverflowError, ValueError) as exc:
        raise TuneError(f"{member.id} 的值 {value} 超出 {member.type} 的范围") from exc


def _decode_member(member, chunk):
    if member.type == "float32":
        return struct.unpack("<f", chunk)[0]
    if member.type == "float64":
        return struct.unpack("<d", chunk)[0]
    return int.from_bytes(chunk, "little", signed=member.type.startswith("int"))


# --------------------------------------------------------------------- 桥接


class Bridge:
    def __init__(self, layout, probe, write_enabled=False, watchdog=0.3, clock=time.monotonic):
        self.layout = layout
        self.probe = probe
        self.write_enabled = write_enabled
        self.watchdog = watchdog
        self.clock = clock
        self.lock = threading.Lock()
        self.last_frame = 0.0
        self.armed = False
        self.frames = 0
        self.closed = False

    def read_state(self):
        with self.lock:
            return self._read_locked()

    def apply_frame(self, data):
        frame = decode_frame(data)
        if not self.write_enabled:
            raise WriteDisabled("桥接器以只读模式启动：加 --allow-write 才能写入")
        started = self.clock()
        written = {
            "axes": list(frame.axes),
            "sliders": list(frame.sliders),
            "enabled": int(frame.active),
            "stopped": int(frame.stopped),
            "sequence": frame.seq,
            "link_valid": 1,
        }
        # 先记「收到活帧」再读写：一帧在 SWD 上偶尔要几百毫秒，
        # 如果等写完再刷新时间戳，看门狗会把它误判成断流。
        self.last_frame = self.clock()
        self.armed = True
        with self.lock:
            self._write_locked(written)
            observed = self._read_locked()
            # 在锁内再刷一次：看门狗等锁期间，这一帧已经写完了。
            self.last_frame = self.clock()
        self.frames += 1
        verified = True
        for name, value in written.items():
            values = value if isinstance(value, (list, tuple)) else [value]
            for member, expected in zip(self.layout.group(name), values):
                if observed.get(member.id) != expected:
                    verified = False
        return {
            "ok": True,
            "verified": verified,
            "frames": self.frames,
            "readback": observed,
            "ms": round((self.clock() - started) * 1000, 2),
        }

    def tick(self):
        """看门狗的一步：断流超过 watchdog 秒就写一次安全值。"""
        if not self.write_enabled or not self.armed:
            return False
        if self.clock() - self.last_frame <= self.watchdog:
            return False
        with self.lock:
            # 等锁期间可能刚好有帧写完，进来再确认一次。
            if not self.armed or self.clock() - self.last_frame <= self.watchdog:
                return False
            self._write_locked(NEUTRAL)
            self.armed = False
        return True

    def silence(self):
        """距离上一帧有多久（秒），给看门狗打印用。"""
        return self.clock() - self.last_frame

    def start_watchdog(self):
        interval = max(0.02, self.watchdog / 4)

        def loop():
            while not self.closed:
                time.sleep(interval)
                try:
                    if self.tick():
                        print(
                            f"控制流中断，已写入安全值（输出 0 / stopped=1）"
                            f"：共 {self.frames} 帧，断流 {self.silence():.2f}s"
                        )
                except Exception as exc:  # 探针掉线不应该杀掉看门狗线程
                    print(f"看门狗写入失败：{exc}")

        threading.Thread(target=loop, daemon=True, name="rc-tune-watchdog").start()

    def health(self):
        return {
            "ok": True,
            "version": VERSION,
            "symbol": self.layout.symbol,
            "address": self.layout.address,
            "size": self.layout.size,
            "region": classify_region(self.layout.address),
            "write_enabled": self.write_enabled,
            "frames": self.frames,
            "members": [member.to_dict() for member in self.layout.members],
        }

    def close(self):
        self.closed = True
        self.probe.close()

    def _read_locked(self):
        raw = self.probe.read(self.layout.address, self.layout.size)
        return {
            member.id: _decode_member(member, raw[member.offset:member.offset + member.size])
            for member in self.layout.members
        }

    def _write_locked(self, fields):
        patches = []
        low = high = None
        for name, value in fields.items():
            values = list(value) if isinstance(value, (list, tuple)) else [value]
            members = self.layout.group(name)
            if not members:
                raise TuneError(f"结构体里没有成员 {name}（--symbol 选错了？）")
            if name == "sliders" and len(members) < len(values):
                # 有的工程只用前两个滑块（sliders[2]），协议仍按 10 位发满 4 个，按顺序取前几个。
                values = values[:len(members)]
            if len(members) != len(values):
                raise TuneError(f"成员 {name} 有 {len(members)} 个，但收到 {len(values)} 个值")
            for member, item in zip(members, values):
                patches.append((member.offset, _encode_member(member, item)))
                low = member.offset if low is None else min(low, member.offset)
                high = member.offset + member.size if high is None else max(high, member.offset + member.size)
        # 读回整段再修改：保留有效帧计数、CRC 计数等相邻成员。
        buffer = bytearray(self.probe.read(self.layout.address + low, high - low))
        for offset, data in patches:
            buffer[offset - low:offset - low + len(data)] = data
        self.probe.write(self.layout.address + low, bytes(buffer))


# --------------------------------------------------------------------- HTTP


def make_handler(bridge, token=None):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        # 响应头和正文分多次小写；不关 Nagle 的话每次响应都要等 ACK，一帧多 40ms。
        disable_nagle_algorithm = True

        def log_message(self, fmt, *args):  # 静默，避免 50 Hz 刷屏
            pass

        def do_GET(self):
            if not self._authorized():
                return self._json(401, {"ok": False, "error": "令牌错误"})
            path = self.path.split("?")[0]
            if path == "/api/health":
                return self._json(200, bridge.health())
            if path == "/api/state":
                try:
                    return self._json(200, {"ok": True, "values": bridge.read_state()})
                except Exception as exc:
                    return self._json(503, {"ok": False, "error": f"读取失败：{exc}"})
            return self._json(404, {"ok": False, "error": "not found"})

        def do_POST(self):
            length = int(self.headers.get("Content-Length") or 0)
            body = self.rfile.read(length) if length > 0 else b""
            if not self._authorized():
                return self._json(401, {"ok": False, "error": "令牌错误"})
            if self.path.split("?")[0] != "/api/frame":
                return self._json(404, {"ok": False, "error": "not found"})
            if len(body) != FRAME_SIZE:
                return self._json(400, {"ok": False, "error": f"帧长必须是 {FRAME_SIZE} 字节"})
            try:
                result = bridge.apply_frame(body)
            except WriteDisabled as exc:
                return self._json(403, {"ok": False, "error": str(exc)})
            except FrameError as exc:
                return self._json(422, {"ok": False, "error": str(exc)})
            except TuneError as exc:
                return self._json(503, {"ok": False, "error": str(exc)})
            except Exception as exc:
                return self._json(500, {"ok": False, "error": f"写入失败：{exc}"})
            return self._json(200, result)

        def _authorized(self):
            if token is None:
                return True
            return hmac.compare_digest(self.headers.get("X-Token", ""), token)

        failures = {"count": 0}

        def _json(self, status, payload):
            if status >= 400:
                # 50 Hz 的失败会刷屏：前 3 次和之后每 200 次打一行，够定位问题。
                failures = Handler.failures
                failures["count"] += 1
                count = failures["count"]
                if count <= 3 or count % 200 == 0:
                    print(f"请求失败 [{count}] HTTP {status}：{payload.get('error', '')}", flush=True)
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            try:
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError):
                pass

    return Handler


# --------------------------------------------------------------------- 探针


class PyOcdProbe:
    def __init__(self, session):
        self.session = session
        self.target = session.target

    def read(self, address, size):
        return bytes(self.target.read_memory_block8(address, size))

    def write(self, address, data):
        self.target.write_memory_block8(address, data)

    def close(self):
        try:
            self.session.close()
        except Exception:
            pass


class RspClient:
    """OpenOCD GDB server 的最小 GDB-Remote 客户端，只用来读写内存。

    协议：`$<payload>#<两位十六进制校验>`，校验 = payload 各字节和 & 0xFF。
    OpenOCD 在 gdb_port（默认 3333）上说这套协议，目标保持运行、不 halt。
    """

    def __init__(self, host="127.0.0.1", port=3333, timeout=2.0):
        try:
            self.sock = socket.create_connection((host, port), timeout=timeout)
        except OSError as exc:
            raise TuneError(
                f"连不上 OpenOCD（{host}:{port}）：{exc}\n"
                "请先启动 OpenOCD，并确认它的 gdb_port 与 --ocd-port 一致；"
                "或改用 --backend pyocd。"
            ) from exc
        self.sock.settimeout(timeout)
        # 关掉 Nagle：每帧要发好几个小包，攒包会让每个包多等几十毫秒的 ACK。
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        self._buf = b""
        self._received = False
        # 握一次手：既是活性检查，也能识别“这个端口其实是 telnet 4444”。
        try:
            self.request("qSupported:multiprocess+")
        except TuneError as exc:
            self.close()
            if not self._received:
                raise TuneError(
                    f"连上了 {host}:{port}，但它立刻关闭了连接。\n"
                    "OpenOCD 0.12 及更早的 gdb_port 同一时间只接受一个客户端，"
                    "多半是 Cortex-Debug/mcu-debug 的调试会话还连着。\n"
                    "把调试会话停掉（OpenOCD 本身不用关）再启动 rc-tune；"
                    "或者用 --ocd-port 指到另一个 gdb_port。\n"
                    f"（原始错误：{exc}）"
                ) from exc
            raise

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass

    # -- 底层收发 ----------------------------------------------------

    def _send(self, raw):
        try:
            self.sock.sendall(raw)
        except OSError as exc:
            raise TuneError(f"向 OpenOCD 发送失败：{exc}") from exc

    def _fill(self):
        try:
            data = self.sock.recv(4096)
        except socket.timeout as exc:
            raise TuneError(
                "OpenOCD 无应答（超时）。确认 --ocd-port 指向的是 gdb_port，而不是 telnet 端口。"
            ) from exc
        except OSError as exc:
            raise TuneError(f"读取 OpenOCD 失败：{exc}") from exc
        if not data:
            raise TuneError(
                "OpenOCD 断开了连接（调试会话被停止、端口被别的客户端抢走，或目标复位了）。"
            )
        self._received = True
        self._buf += data

    def _byte(self):
        while not self._buf:
            self._fill()
        value, self._buf = self._buf[0], self._buf[1:]
        return value

    @staticmethod
    def _hex_digit(value):
        if 48 <= value <= 57:
            return value - 48
        if 97 <= value <= 102:
            return value - 87
        if 65 <= value <= 70:
            return value - 55
        raise TuneError("OpenOCD 应答的校验位不是十六进制，这个端口可能不是 GDB server")

    def _payload(self):
        data = bytearray()
        while True:
            value = self._byte()
            if value == ord("#"):
                break
            data.append(value)
        check = self._hex_digit(self._byte()) * 16 + self._hex_digit(self._byte())
        if check != sum(data) & 0xFF:
            self._send(b"-")
            raise TuneError("OpenOCD 应答校验失败（端口不像 GDB server，或链路被干扰）")
        self._send(b"+")
        return data.decode("ascii", "replace")

    def _reply(self):
        # 服务器可能夹带 '+'、'\n' 或空字节，找到下一个 '$' 才是正文。
        while True:
            value = self._byte()
            if value == ord("$"):
                return self._payload()
            if value in (ord("+"), ord("\n"), 0):
                continue
            raise TuneError(f"OpenOCD 应答异常：0x{value:02x}")

    # -- 一条命令 ----------------------------------------------------

    def request(self, payload):
        body = payload.encode("ascii")
        packet = b"$" + body + b"#" + f"{sum(body) & 0xFF:02x}".encode("ascii")
        for _ in range(3):
            self._send(packet)
            ack = self._byte()
            if ack == ord("+"):
                return self._reply()
            if ack == ord("-"):
                continue  # 服务器没收好，重发
            if ack == ord("$"):
                return self._payload()  # 某些实现不发 ack
            raise TuneError(f"OpenOCD 应答异常：0x{ack:02x}")
        raise TuneError("OpenOCD 连续拒收数据包，链路不可用")


class OpenOcdProbe:
    """通过已在运行的 OpenOCD（GDB server）读写内存，不抢占探针。"""

    MAX_READ = 1024
    MAX_WRITE = 512

    def __init__(self, host="127.0.0.1", port=3333, timeout=2.0):
        self.rsp = RspClient(host, port, timeout)

    def read(self, address, size):
        data = bytearray()
        while size > 0:
            chunk = min(size, self.MAX_READ)
            reply = self.rsp.request(f"m{address:08x},{chunk:x}")
            if not reply or reply.startswith("E"):
                raise TuneError(f"OpenOCD 拒绝读取 0x{address:08X}（{chunk} 字节）：{reply or '空应答'}")
            raw = bytes.fromhex(reply)
            if len(raw) != chunk:
                raise TuneError(f"OpenOCD 读取 0x{address:08X} 长度不符：{len(raw)} != {chunk}")
            data += raw
            address += chunk
            size -= chunk
        return bytes(data)

    def write(self, address, data):
        view = memoryview(bytes(data))
        while view:
            chunk = bytes(view[:self.MAX_WRITE])
            reply = self.rsp.request(f"M{address:08x},{len(chunk):x}:{chunk.hex()}")
            if reply != "OK":
                raise TuneError(f"OpenOCD 拒绝写入 0x{address:08X}（{len(chunk)} 字节）：{reply or '空应答'}")
            address += len(chunk)
            view = view[len(chunk):]

    def close(self):
        self.rsp.close()


def open_probe(backend="openocd", target=None, unique_id=None, ocd_host="127.0.0.1", ocd_port=3333):
    if backend == "openocd":
        # OpenOCD 刚起来时可能还留着上一个探测连接，重试几次再放弃。
        deadline = time.monotonic() + 2.0
        while True:
            try:
                return OpenOcdProbe(ocd_host, ocd_port)
            except TuneError:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(0.2)
    try:
        from pyocd.core.helpers import ConnectHelper
    except ImportError as exc:  # pragma: no cover
        raise TuneError("缺少 pyocd：pip install pyocd，或改用 --backend openocd") from exc
    session = ConnectHelper.session_with_chosen_probe(
        target_override=target,
        unique_id=unique_id,
        options={"connect_mode": "attach"},
    )
    if session is None:
        raise TuneError("没有找到调试探针（检查 DAPLink/ST-Link 与接线）")
    session.open()
    return PyOcdProbe(session)


# --------------------------------------------------------------------- 命令行


def lan_address():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("8.8.8.8", 80))
        return sock.getsockname()[0]
    except OSError:
        return None
    finally:
        sock.close()


def main(argv=None):
    parser = argparse.ArgumentParser(
        prog="tune.py",
        description="把 App 的 20 字节控制帧写进 STM32 的全局结构体（DAPLink/SWD，不 halt、不 reset）",
    )
    sub = parser.add_subparsers(dest="command", required=True)

    lister = sub.add_parser("list-probes", help="列出已连接的调试探针")
    lister.set_defaults(handler=_cmd_list)

    serve = sub.add_parser("serve", help="启动 HTTP 桥接器，等待 App 连接")
    serve.add_argument("--elf", required=True, help="带 -g 的固件 ELF")
    serve.add_argument("--symbol", default="g_rc", help="全局结构体名（默认 g_rc）")
    serve.add_argument("--backend", choices=("openocd", "pyocd"), default="openocd",
                       help="调试后端：openocd（连已运行的 OpenOCD，默认）或 pyocd（自己占探针）")
    serve.add_argument("--ocd-host", default="127.0.0.1", help="OpenOCD gdb_host（默认 127.0.0.1）")
    serve.add_argument("--ocd-port", type=int, default=3333, help="OpenOCD gdb_port（默认 3333）")
    serve.add_argument("--target", default="stm32h723vg", help="pyOCD 目标名，仅 --backend pyocd 时使用")
    serve.add_argument("--probe", default=None, help="探针 UID（多个探针时用）")
    serve.add_argument("--host", default="0.0.0.0", help="监听地址（默认 0.0.0.0）")
    serve.add_argument("--port", type=int, default=8765, help="监听端口（默认 8765）")
    serve.add_argument("--allow-write", action="store_true", help="允许写入（默认只读）")
    serve.add_argument(
        "--token",
        default=None,
        help="可选令牌：默认不校验；给了之后 App 要在 X-Token 里带同一个值",
    )
    serve.add_argument("--no-token", action="store_true", help=argparse.SUPPRESS)
    serve.add_argument("--watchdog-ms", type=int, default=300, help="断流多久后写安全值（默认 300）")
    serve.set_defaults(handler=_cmd_serve)

    args = parser.parse_args(argv)
    try:
        return args.handler(args) or 0
    except TuneError as exc:
        print(f"错误：{exc}")
        return 2
    except KeyboardInterrupt:
        return 130


def _cmd_list(_args):
    try:
        from pyocd.core.helpers import ConnectHelper
    except ImportError as exc:  # pragma: no cover
        raise TuneError("缺少 pyocd：pip install pyocd") from exc
    probes = ConnectHelper.list_connected_probes()
    if not probes:
        print("没有找到探针")
        return 1
    for probe in probes:
        print(f"{probe.unique_id}\t{probe.description}")
    return 0


def _cmd_serve(args):
    layout = parse_layout(args.elf, args.symbol)
    if not 0x20000000 <= layout.address < 0x40000000:
        raise TuneError(f"{args.symbol} 在 0x{layout.address:08X}，不是 STM32 的 SRAM，拒绝写入")
    if args.watchdog_ms < 50:
        raise TuneError("--watchdog-ms 不能小于 50")

    probe = open_probe(args.backend, args.target, args.probe, args.ocd_host, args.ocd_port)
    bridge = Bridge(layout, probe, write_enabled=args.allow_write, watchdog=args.watchdog_ms / 1000)
    token = args.token or None

    current = bridge.read_state()
    spans = ", ".join(f"{m.id}" for m in layout.members[:6])
    print(f"结构体   {layout.symbol} @0x{layout.address:08X}，{layout.size} 字节，{classify_region(layout.address)}")
    print(f"成员     {spans} …")
    print(f"当前值   {', '.join(f'{k}={v}' for k, v in list(current.items())[:6])}")
    if args.backend == "openocd":
        print(f"链路     OpenOCD {args.ocd_host}:{args.ocd_port}（连已有实例，不抢探针）")
    else:
        print(f"链路     pyOCD {args.target}{' · ' + args.probe if args.probe else ''}")
    print(f"写入     {'已开启（--allow-write）' if args.allow_write else '只读（加 --allow-write 才能写）'}")
    print(f"令牌     {token if token else '未启用（局域网内可直接连接）'}")
    host = "127.0.0.1" if args.host in ("0.0.0.0", "") else args.host
    shown = "0.0.0.0" if args.host in ("0.0.0.0", "") else args.host
    ip = lan_address() if args.host == "0.0.0.0" else host
    print(f"地址     http://{ip or shown}:{args.port}   ← App「面板设置 → 调试链路」")
    if args.allow_write:
        print("提示     g_rc 被蓝牙帧覆盖时以蓝牙为准；写入前请架空机器人。")

    try:
        httpd = ThreadingHTTPServer((args.host, args.port), make_handler(bridge, token))
    except OSError as exc:
        raise TuneError(f"监听 {args.host}:{args.port} 失败：{exc}\n端口被占用时，先关掉上一个桥接器窗口再启动。") from exc
    httpd.daemon_threads = True
    bridge.start_watchdog()
    try:
        httpd.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        print("\n已停止")
    finally:
        httpd.server_close()
        bridge.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())