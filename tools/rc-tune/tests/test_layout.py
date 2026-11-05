import shutil
import subprocess

import pytest

from tune import TuneError, classify_region, parse_layout

SOURCE = r"""
#include <stdint.h>
struct TuneFixture {
    volatile float kp;
    int32_t limit;
    uint16_t tag;
    uint16_t pad;
    int16_t arr[4];
    uint8_t raw[3];
};
volatile struct TuneFixture g_fixture;
int main(void) { return (int)g_fixture.limit; }
"""


@pytest.fixture(scope="module")
def elf(tmp_path_factory):
    gcc = shutil.which("gcc")
    if gcc is None:
        pytest.skip("需要 gcc 生成带 DWARF 的夹具")
    folder = tmp_path_factory.mktemp("fixture")
    source = folder / "fixture.c"
    source.write_text(SOURCE)
    binary = folder / "fixture"
    subprocess.run([gcc, "-g", "-O0", "-no-pie", "-o", str(binary), str(source)], check=True)
    return binary


def test_expands_members_and_arrays(elf):
    layout = parse_layout(elf, "g_fixture")

    assert layout.size == 24
    assert [m.id for m in layout.members] == [
        "kp", "limit", "tag", "pad",
        "arr[0]", "arr[1]", "arr[2]", "arr[3]",
        "raw[0]", "raw[1]", "raw[2]",
    ]
    members = layout.by_id
    assert (members["kp"].offset, members["kp"].size, members["kp"].type) == (0, 4, "float32")
    assert (members["limit"].offset, members["limit"].type) == (4, "int32")
    assert (members["arr[2]"].offset, members["arr[2]"].size) == (16, 2)
    assert (members["raw[2]"].offset, members["raw[2]"].type) == (22, "uint8")
    assert layout.address > 0


def test_missing_symbol_explains_how_to_fix(elf):
    with pytest.raises(TuneError, match="找不到"):
        parse_layout(elf, "no_such_symbol")


def test_classifies_h7_memory_regions():
    assert "DTCM" in classify_region(0x200001A0)
    assert "AXI" in classify_region(0x24000100)
    assert "SRAM" in classify_region(0x30000000)