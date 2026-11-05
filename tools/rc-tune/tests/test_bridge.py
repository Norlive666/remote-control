import http.client
import json
import struct
import threading
from http.server import ThreadingHTTPServer

import pytest

from frames import make_frame
from tune import Bridge, FrameError, Member, StructLayout, WriteDisabled, make_handler

BASE = 0x20000000


class FakeProbe:
    """按字节暴露一小块“内存”，记录读写次数。"""

    def __init__(self):
        self.mem = bytearray(64)
        self.writes = 0
        self.reads = 0

    def read(self, address, size):
        assert BASE <= address and address + size <= BASE + len(self.mem)
        self.reads += 1
        return bytes(self.mem[address - BASE:address - BASE + size])

    def write(self, address, data):
        assert BASE <= address and address + len(data) <= BASE + len(self.mem)
        self.writes += 1
        self.mem[address - BASE:address - BASE + len(data)] = data

    def close(self):
        pass


def g_rc_layout():
    members = [Member(f"axes[{i}]", "int16", 2, i * 2) for i in range(4)]
    members += [Member(f"sliders[{i}]", "uint16", 2, 8 + i * 2) for i in range(4)]
    members += [
        Member("enabled", "bool", 1, 16),
        Member("stopped", "bool", 1, 17),
        Member("sequence", "uint8", 1, 18),
        Member("link_valid", "bool", 1, 19),
        Member("valid_frames", "uint32", 4, 20),
        Member("crc_errors", "uint32", 4, 24),
    ]
    return StructLayout("g_rc", BASE, 28, members)


def test_frame_writes_output_and_flags_but_keeps_counters():
    probe = FakeProbe()
    probe.mem[20:24] = (1234).to_bytes(4, "little")
    bridge = Bridge(g_rc_layout(), probe, write_enabled=True)

    result = bridge.apply_frame(make_frame(seq=9, axes=(100, -200, 300, -400), sliders=(0, 500, 1000, 250)))

    assert result["verified"] is True
    raw = probe.read(BASE, 28)
    assert struct.unpack_from("<4h", raw, 0) == (100, -200, 300, -400)
    assert struct.unpack_from("<4H", raw, 8) == (0, 500, 1000, 250)
    assert (raw[16], raw[17], raw[18], raw[19]) == (1, 0, 9, 1)
    assert int.from_bytes(raw[20:24], "little") == 1234


def test_stop_frame_clears_output_and_sets_stop_flag():
    probe = FakeProbe()
    bridge = Bridge(g_rc_layout(), probe, write_enabled=True)

    bridge.apply_frame(make_frame(axes=(0, 0, 0, 0), enabled=False, stopped=True))

    raw = probe.read(BASE, 20)
    assert raw[0:8] == bytes(8)
    assert (raw[16], raw[17], raw[19]) == (0, 1, 1)


def test_read_only_bridge_refuses_writes():
    probe = FakeProbe()
    bridge = Bridge(g_rc_layout(), probe, write_enabled=False)

    with pytest.raises(WriteDisabled):
        bridge.apply_frame(make_frame())
    assert probe.writes == 0


def test_bad_frame_writes_nothing():
    probe = FakeProbe()
    bridge = Bridge(g_rc_layout(), probe, write_enabled=True)

    with pytest.raises(FrameError):
        bridge.apply_frame(b"\x00" * 20)
    assert probe.writes == 0


def test_watchdog_neutralizes_after_signal_loss():
    now = [1000.0]
    probe = FakeProbe()
    bridge = Bridge(g_rc_layout(), probe, write_enabled=True, watchdog=0.3, clock=lambda: now[0])

    bridge.apply_frame(make_frame(axes=(1, 2, 3, 4), sliders=(9, 9, 9, 9)))
    now[0] += 0.1
    bridge.tick()
    assert probe.read(BASE, 8) != bytes(8), "未超时不应清零"

    now[0] += 0.5
    bridge.tick()
    raw = probe.read(BASE, 20)
    assert raw[0:8] == bytes(8)
    assert (raw[16], raw[17], raw[19]) == (0, 1, 0)

    writes = probe.writes
    bridge.tick()
    assert probe.writes == writes, "只清一次，不能反复写"


@pytest.fixture
def server():
    probe = FakeProbe()
    bridge = Bridge(g_rc_layout(), probe, write_enabled=True)
    httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(bridge, "tok"))
    thread = threading.Thread(target=httpd.serve_forever, daemon=True)
    thread.start()
    try:
        yield httpd.server_address[1], probe, bridge
    finally:
        httpd.shutdown()
        httpd.server_close()


def post(port, body, token="tok"):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    headers = {"Content-Type": "application/octet-stream"}
    if token is not None:
        headers["X-Token"] = token
    connection.request("POST", "/api/frame", body=body, headers=headers)
    response = connection.getresponse()
    payload = json.loads(response.read())
    connection.close()
    return response.status, payload


def test_http_frame_round_trip(server):
    port, probe, _ = server

    status, payload = post(port, make_frame(axes=(5, 6, 7, 8), enabled=True))

    assert status == 200
    assert payload["verified"] is True
    assert struct.unpack_from("<4h", probe.mem, 0) == (5, 6, 7, 8)


def test_http_rejects_bad_token(server):
    port, probe, _ = server

    status, payload = post(port, make_frame(), token="wrong")

    assert status == 401
    assert probe.writes == 0


def test_health_reports_symbol_and_mode(server):
    port, _, _ = server
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=5)
    connection.request("GET", "/api/health", headers={"X-Token": "tok"})
    response = connection.getresponse()
    payload = json.loads(response.read())
    connection.close()

    assert response.status == 200
    assert payload["symbol"] == "g_rc"
    assert payload["address"] == BASE
    assert payload["write_enabled"] is True

def test_two_slider_firmware_takes_the_first_two_sliders():
    """Vela_Gimbal 一类的工程只有 sliders[2]，协议仍发 4 个，不应该整帧被拒。"""
    members = [Member(f"axes[{i}]", "int16", 2, i * 2) for i in range(4)]
    members += [Member(f"sliders[{i}]", "uint16", 2, 8 + i * 2) for i in range(2)]
    members += [
        Member("enabled", "bool", 1, 12),
        Member("stopped", "bool", 1, 13),
        Member("sequence", "uint8", 1, 14),
        Member("link_valid", "bool", 1, 15),
        Member("valid_frames", "uint32", 4, 16),
        Member("crc_errors", "uint32", 4, 20),
    ]
    layout = StructLayout("g_rc", BASE, 24, members)
    probe = FakeProbe()
    bridge = Bridge(layout, probe, write_enabled=True)

    result = bridge.apply_frame(make_frame(seq=3, axes=(1, 2, 3, 4), sliders=(100, 200, 300, 400)))

    assert result["verified"] is True
    raw = probe.read(BASE, 24)
    assert struct.unpack_from("<2H", raw, 8) == (100, 200)
