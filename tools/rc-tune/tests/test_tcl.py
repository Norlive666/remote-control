"""OpenOCD Tcl RPC tests using a scripted TCP server."""

import socket
import threading
from contextlib import contextmanager

import pytest

from tune import TuneError, open_probe


@contextmanager
def tcl_server(exchanges):
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        server.listen()
        server.settimeout(2)
        errors = []

        def run():
            try:
                conn, _ = server.accept()
                with conn, conn.makefile("rb") as stream:
                    conn.settimeout(2)
                    for command, replies in exchanges:
                        request = bytearray()
                        while (byte := stream.read(1)) and byte != b"\x1a":
                            request += byte
                        assert byte == b"\x1a" and command in request.decode("ascii")
                        for reply in replies:
                            conn.sendall(reply)
            except Exception as exc:
                errors.append(exc)

        worker = threading.Thread(target=run, daemon=True)
        worker.start()
        try:
            yield server.getsockname()[1]
        finally:
            worker.join(timeout=3)
            assert not worker.is_alive()
            if errors:
                raise errors[0]


HELLO = ("version", [b"rc-tune-ok Open On-Chip Debugger 0.12.0\x1a"])
BASE = 0x20000000


def test_tcl_reads_and_writes_bytes_on_the_same_connection():
    with tcl_server([
        HELLO,
        ("write_memory 0x20000000 8 {0xa5 0x5a 0xff}", [b"rc-tune-ok \x1a"]),
        ("read_memory 0x20000000 8 3", [b"rc-tune-ok 0xa5 ", b"0x5a 0xff\x1a"]),
    ]) as port:
        probe = open_probe("openocd-tcl", ocd_port=port)
        try:
            probe.write(BASE, b"\xa5\x5a\xff")
            assert probe.read(BASE, 3) == b"\xa5\x5a\xff"
        finally:
            probe.close()


def test_tcl_reports_a_rejected_write():
    with tcl_server([
        HELLO,
        ("write_memory", [b"rc-tune-error target memory unavailable\x1a"]),
    ]) as port:
        probe = open_probe("openocd-tcl", ocd_port=port)
        try:
            with pytest.raises(TuneError, match="target memory unavailable"):
                probe.write(BASE, b"\x01")
        finally:
            probe.close()


def test_tcl_rejects_a_short_read():
    with tcl_server([HELLO, ("read_memory", [b"rc-tune-ok 0xa5\x1a"])]) as port:
        probe = open_probe("openocd-tcl", ocd_port=port)
        try:
            with pytest.raises(TuneError, match="长度不符"):
                probe.read(BASE, 2)
        finally:
            probe.close()


def test_tcl_disconnect_during_write_requires_restarting_bridge():
    with tcl_server([HELLO, ("write_memory", [])]) as port:
        probe = open_probe("openocd-tcl", ocd_port=port)
        try:
            with pytest.raises(TuneError, match="重启桥接器"):
                probe.write(BASE, b"\x01")
            with pytest.raises(TuneError, match="重启桥接器"):
                probe.read(BASE, 1)
        finally:
            probe.close()
