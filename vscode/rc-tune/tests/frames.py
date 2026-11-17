"""按 protocol/README.md 构造 20 字节控制帧，供测试复用。"""

import struct

from tune import crc16


def make_frame(seq=7, axes=(0, 0, 0, 0), sliders=(0, 0, 0, 0), enabled=True, stopped=False, version=2):
    body = bytearray(20)
    body[0:2] = b"\xa5\x5a"
    body[2] = version
    body[3] = seq & 0xFF
    for i, value in enumerate(axes):
        struct.pack_into("<h", body, 4 + i * 2, value)
    packed = 0
    for i, value in enumerate(sliders):
        packed |= (value & 0x3FF) << (10 * i)
    body[12:17] = packed.to_bytes(5, "little")
    body[17] = (1 if enabled and not stopped else 0) | (2 if stopped else 0)
    body[18:20] = crc16(body, 2, 18).to_bytes(2, "little")
    return bytes(body)