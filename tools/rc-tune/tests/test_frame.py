import struct

import pytest

from frames import make_frame
from tune import FrameError, decode_frame


def test_decodes_axes_sliders_and_flags():
    frame = decode_frame(make_frame(seq=9, axes=(100, -200, 300, -400), sliders=(0, 500, 1000, 250)))

    assert frame.seq == 9
    assert frame.axes == [100, -200, 300, -400]
    assert frame.sliders == [0, 500, 1000, 250]
    assert frame.active is True
    assert frame.stopped is False


def test_emergency_stop_has_no_output():
    frame = decode_frame(make_frame(axes=(0, 0, 0, 0), sliders=(0, 0, 0, 0), enabled=False, stopped=True))

    assert frame.active is False
    assert frame.stopped is True


def test_rejects_bad_crc():
    body = bytearray(make_frame())
    body[18] ^= 0xFF

    with pytest.raises(FrameError, match="CRC"):
        decode_frame(bytes(body))


def test_rejects_output_when_not_enabled():
    body = bytearray(make_frame(enabled=False))
    struct.pack_into("<h", body, 4, 500)
    body[18:20] = struct.pack("<H", 0)  # 先清零，下面重新算
    from tune import crc16

    body[18:20] = crc16(body, 2, 18).to_bytes(2, "little")

    with pytest.raises(FrameError, match="必须为 0"):
        decode_frame(bytes(body))


def test_rejects_wrong_length_and_magic():
    with pytest.raises(FrameError, match="20"):
        decode_frame(b"\xa5\x5a")
    body = bytearray(make_frame())
    body[0] = 0x00
    with pytest.raises(FrameError, match="帧头"):
        decode_frame(bytes(body))