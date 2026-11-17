"""DWARF 里「定义 DIE 只带 DW_AT_specification」的情况（ST 工程常见）。"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import tune


class Attr:
    def __init__(self, value, form="DW_FORM_ref4"):
        self.value = value
        self.form = form


class Die:
    def __init__(self, tag, offset, **attributes):
        self.tag = tag
        self.offset = offset
        self.attributes = {name: value for name, value in attributes.items()}


class CU:
    """够用的假 CU：ref1/2/4/8 存 CU 内偏移，ref_addr 存全局偏移。"""

    cu_offset = 0x1000

    def __init__(self, dies):
        self.dies = {die.offset: die for die in dies}

    def get_DIE_from_refaddr(self, ref):
        if ref not in self.dies:
            raise KeyError(ref)
        return self.dies[ref]

    def iter_DIEs(self):
        return iter(sorted(self.dies.values(), key=lambda die: die.offset))


def make_cu():
    struct = Die("DW_TAG_structure_type", 0x3000)
    decl = Die(
        "DW_TAG_variable",
        0x1000,
        DW_AT_name=Attr(b"g_rc", form="DW_FORM_strp"),
        DW_AT_type=Attr(0x2000),
        DW_AT_declaration=Attr(True, form="DW_FORM_flag_present"),
    )
    definition = Die(
        "DW_TAG_variable",
        0x1800,
        DW_AT_specification=Attr(0x0000),
        DW_AT_location=Attr([3, 0x74, 0x4E, 0x00, 0x20], form="DW_FORM_exprloc"),
    )
    return CU([decl, definition, struct]), decl, definition, struct


def test_variable_name_follows_specification():
    cu, _, definition, _ = make_cu()
    assert tune._variable_name(cu, definition) == "g_rc"


def test_variable_name_direct():
    cu, decl, _, _ = make_cu()
    assert tune._variable_name(cu, decl) == "g_rc"


def test_type_ref_falls_back_to_declaration():
    cu, _, definition, struct = make_cu()
    ref = tune._type_ref(cu, definition)
    assert tune._unwrap(cu, ref) is struct


def test_find_variable_accepts_definition_die():
    cu, _, definition, _ = make_cu()

    class Dwarf:
        def iter_CUs(self):
            return iter([cu])

    found_cu, found_die, address = tune._find_variable(Dwarf(), "g_rc")
    assert found_cu is cu
    assert found_die is definition
    assert address == 0x20004E74


def test_global_ref_addr_is_used_as_is():
    cu, decl, _, _ = make_cu()
    attr = Attr(0x1000, form="DW_FORM_ref_addr")
    assert tune._die_from_ref(cu, attr) is decl