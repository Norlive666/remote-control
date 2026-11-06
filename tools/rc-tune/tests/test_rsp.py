"""OpenOCD GDB server 客户端：用本地假服务器验证 RSP 拆包/封包。"""

import socket
import sys
import threading
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from tune import OpenOcdProbe, TuneError

BASE = 0x20000000


def frame(payload: str, corrupt: bool = False) -> bytes:
    data = payload.encode()
    check = f"{sum(data) & 0xFF:02x}"
    if corrupt:
        check = "00" if check != "00" else "01"
    return b"$" + data + b"#" + check.encode()


class FakeGdbServer:
    """只实现 qSupported / m / M，够桥接器用了。"""

    def __init__(self, corrupt_first: bool = False, size: int = 256, close_immediately: bool = False):
        self.mem = bytearray(size)
        self.corrupt_first = corrupt_first
        self.close_immediately = close_immediately
        self.requests: list[str] = []
        self._sock = socket.socket()
        self._sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self):
        conn, _ = self._sock.accept()
        if self.close_immediately:  # 模拟 OpenOCD gdb_port 客户端已满
            conn.close()
            return
        buf = b""
        try:
            while True:
                data = conn.recv(4096)
                if not data:
                    return
                buf += data
                while True:
                    start = buf.find(b"$")
                    if start < 0:
                        break
                    end = buf.find(b"#", start)
                    if end < 0 or len(buf) < end + 3:
                        break
                    payload = buf[start + 1:end].decode()
                    buf = buf[end + 3:]
                    self.requests.append(payload)
                    broken = self.corrupt_first and len(self.requests) == 1
                    conn.sendall(b"+")
                    conn.sendall(frame(self._reply(payload), corrupt=broken))
        finally:
            conn.close()
            self._sock.close()

    def _reply(self, payload: str) -> str:
        if payload.startswith("qSupported"):
            return "PacketSize=1000;qXfer:memory-map:read+"
        if payload.startswith("m"):
            address, length = (int(part, 16) for part in payload[1:].split(","))
            chunk = self.mem[address - BASE:address - BASE + length]
            if len(chunk) != length:
                return "E01"
            return bytes(chunk).hex()
        if payload.startswith("M"):
            head, data = payload[1:].split(":")
            address, length = (int(part, 16) for part in head.split(","))
            raw = bytes.fromhex(data)
            if len(raw) != length:
                return "E01"
            self.mem[address - BASE:address - BASE + length] = raw
            return "OK"
        return ""


def test_reads_bytes_at_address():
    server = FakeGdbServer()
    server.mem[0:4] = b"\x01\x02\x03\x04"
    probe = OpenOcdProbe("127.0.0.1", server.port)
    try:
        assert probe.read(BASE, 4) == b"\x01\x02\x03\x04"
        assert server.requests[0].startswith("qSupported")
    finally:
        probe.close()


def test_busy_gdb_port_explains_the_single_client_limit():
    server = FakeGdbServer(close_immediately=True)

    with pytest.raises(TuneError) as raised:
        OpenOcdProbe("127.0.0.1", server.port)

    message = str(raised.value)
    assert "只接受一个客户端" in message
    assert "--ocd-port" in message


def test_writes_bytes_and_reports_ok():
    server = FakeGdbServer()
    probe = OpenOcdProbe("127.0.0.1", server.port)
    try:
        probe.write(BASE + 8, b"\xde\xad\xbe\xef")
    finally:
        probe.close()
    assert bytes(server.mem[8:12]) == b"\xde\xad\xbe\xef"
    assert server.requests[-1] == "M20000008,4:deadbeef"


def test_reads_in_chunks_larger_than_one_packet():
    server = FakeGdbServer(size=4096)
    server.mem[0:2000] = bytes((i % 251) for i in range(2000))
    probe = OpenOcdProbe("127.0.0.1", server.port)
    try:
        assert probe.read(BASE, 2000) == bytes(server.mem[0:2000])
    finally:
        probe.close()
    assert sum(1 for r in server.requests if r.startswith("m")) == 2


def test_read_past_memory_reports_openocd_error():
    server = FakeGdbServer(size=16)
    probe = OpenOcdProbe("127.0.0.1", server.port)
    try:
        with pytest.raises(TuneError) as info:
            probe.read(BASE + 8, 16)
    finally:
        probe.close()
    assert "E01" in str(info.value)


def test_connection_refused_explains_openocd():
    sock = socket.socket()
    sock.bind(("127.0.0.1", 0))
    port = sock.getsockname()[1]
    sock.close()

    with pytest.raises(TuneError) as info:
        OpenOcdProbe("127.0.0.1", port)

    assert "OpenOCD" in str(info.value)
    assert str(port) in str(info.value)


def test_bad_checksum_is_reported():
    server = FakeGdbServer(corrupt_first=True)

    with pytest.raises(TuneError) as info:
        OpenOcdProbe("127.0.0.1", server.port)

    assert "校验" in str(info.value)

def test_open_probe_builds_openocd_backend():
    from tune import open_probe

    server = FakeGdbServer()
    server.mem[0:2] = b"\xaa\xbb"
    probe = open_probe("openocd", ocd_host="127.0.0.1", ocd_port=server.port)
    try:
        assert probe.read(BASE, 2) == b"\xaa\xbb"
    finally:
        probe.close()


def test_bridge_writes_g_rc_through_openocd():
    """整条新链路：Bridge → OpenOcdProbe → RSP → g_rc 内存，计数器不能被踩。"""
    from frames import make_frame
    from tune import Bridge, Member, StructLayout, open_probe

    server = FakeGdbServer()
    members = [Member(f"axes[{i}]", "int16", 2, 2 * i) for i in range(4)]
    members += [Member(f"sliders[{i}]", "uint16", 2, 8 + 2 * i) for i in range(4)]
    members += [
        Member("enabled", "bool", 1, 16),
        Member("stopped", "bool", 1, 17),
        Member("sequence", "uint8", 1, 18),
        Member("link_valid", "bool", 1, 19),
        Member("valid_frames", "uint32", 4, 20),
        Member("crc_errors", "uint32", 4, 24),
    ]
    layout = StructLayout("g_rc", BASE, 28, members)
    server.mem[20:24] = (4321).to_bytes(4, "little")

    probe = open_probe("openocd", ocd_host="127.0.0.1", ocd_port=server.port)
    bridge = Bridge(layout, probe, write_enabled=True)
    try:
        result = bridge.apply_frame(make_frame(seq=5, axes=(10, 20, 30, 40), sliders=(0, 1, 2, 3), enabled=True))
    finally:
        bridge.close()

    assert result["verified"] is True
    assert int.from_bytes(server.mem[0:2], "little", signed=True) == 10
    assert [int.from_bytes(server.mem[8 + 2 * i:10 + 2 * i], "little") for i in range(4)] == [0, 1, 2, 3]
    assert server.mem[16] == 1 and server.mem[18] == 5
    assert server.mem[20:24] == (4321).to_bytes(4, "little")
