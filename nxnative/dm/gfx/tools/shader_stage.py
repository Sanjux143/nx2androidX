#!/usr/bin/env python3
# A shader code area's stage: the one rule the package reader (shader_split.py) and the Vulkan translator (dm/android/tools/shader_vk.py) share.
# It is a module of its own so that the translator does not import the whole package reader: the Vulkan bundle's reuse key is the translator's own inputs, and the split's readers
# (shader_split.py, unityfs.py, uepak.py, ...) are none of them — a change to one of them must not cost a port the whole translation again.
import struct

GFX_MAGIC, CS_MAGIC = bytes.fromhex('78563412'), bytes.fromhex('79563412')
SPH_STAGE = {1: 'vs', 2: 'hs', 3: 'ds', 4: 'gs', 5: 'ps'}


def sph_stage(code):
    """A graphics code area's stage from its SPH word 0: shader type in bits 10..13. Catherine: Full Body's GLSLC (SDK 8.2.1, GPU code version 1.15) writes 0 there in every vertex shader
    (word 0 = 0x0061: SPH type 1 VTG, version 3), in its shaders_binary files and in what it compiles at run time, where every other game's vertex shaders have 1 (0x0461); Ryujinx's ShaderHeader
    reads a graphics header's 0 as a vertex shader as well, so it is one here"""
    w = struct.unpack_from('<I', code, 0x30)[0]
    return 'vs' if (w >> 10) & 0xf == 0 and w & 0x1f == 1 else SPH_STAGE.get((w >> 10) & 0xf)


def glslc_stage(ctl, code):
    """A GLSLC output's stage, from its code area's header (graphics: the SPH's stage bits), or compute when the code has no header and the control's thread group (+0x748) is filled"""
    headerless_cs = code[:4] not in (CS_MAGIC, GFX_MAGIC) and len(ctl) >= 0x754 and all(struct.unpack_from('<3I', ctl, 0x748))
    return 'cs' if code[:4] == CS_MAGIC or headerless_cs else sph_stage(code) if code[:4] == GFX_MAGIC and len(code) >= 0x34 else None
