#!/usr/bin/env python3
# Android build of the host (the counterpart of dm/host/Makefile): the same host and graphics sources, the Darwin compatibility layer (dm/android/compat)
# and the Android front end / graphics backend (dm/android/*.c), built with the NDK's clang for arm64.
#   build.py --game <title ID> [--ndk <NDK dir>] [--out <dir>] [--jobs N] [--exe] [--lib] [--only-compile] [--release]
# --release: no debug information, the library stripped, DM_RELEASE defined (the app ignores DM_CTL), outputs in dm/android/out/<title ID>-release.
# Outputs in <out> (default dm/android/out/<title ID>): dmhost (an executable for adb shell runs: headless, no window) and libdmhost.so (the app's NativeActivity).
# Objects are rebuilt when a source or a header it includes changed (clang -MMD dependency files).
import argparse, concurrent.futures as cf, os, platform, re, subprocess, sys
from pathlib import Path

DM = Path(__file__).resolve().parent.parent          # dm/
ap = argparse.ArgumentParser()
ap.add_argument('--game', required=True)
ap.add_argument('--ndk', default=os.environ.get('ANDROID_NDK_HOME') or str(DM.parent.parent / 'tools/android-ndk-r27c'))
ap.add_argument('--out')
ap.add_argument('--api', default='31')
ap.add_argument('--jobs', type=int, default=os.cpu_count() or 8)
ap.add_argument('--exe', action='store_true'); ap.add_argument('--lib', action='store_true')
ap.add_argument('--play', action='store_true', help='also libdmplay.so: the host statically linked with the player\'s JNI entry (jni_android.c), one per game, for the app\'s :play process')
ap.add_argument('--no-lsfg', action='store_true', help='build without the frame generation (NX_LSFG): the host has no lsfg/ code (the drawer then reports "not in this build")')
ap.add_argument('--only-compile', action='store_true', help='compile every source and report errors, no link')
ap.add_argument('--keep-going', action='store_true')
ap.add_argument('-O', default='2')
ap.add_argument('--gfx', default='vk', choices=('vk', 'null'), help='graphics backend: Vulkan or the null device')
ap.add_argument('--release', action='store_true', help='a build to keep installed: no debug information, stripped, no DM_CTL')
ap.add_argument('--adrenotools', action='store_true', help='also libadrenotools.so and its hooks (third_party/libadrenotools), which the APK carries with a custom Vulkan driver')
a = ap.parse_args()
if not (a.exe or a.lib or a.play): a.exe = a.lib = True

import platform as _pf, shutil as _sh, stat as _st0

def host_dir(base, tool):
    """The NDK's host folder for a tool under base. On arm64 Linux the NDK ships x86-64 host tools only, so a folder named linux-aarch64 with an arm64 build of the tool beside the NDK's sysroot
    (see docs/DEVELOPMENT_GUIDE.md) is used when it holds the tool; otherwise linux-x86_64, as before (an x86_64 machine, or arm64 with qemu-user / box64), so a missing folder fails as it did"""
    if os.name == 'nt': return 'windows-x86_64'
    if not sys.platform.startswith('linux'): return 'darwin-x86_64'
    if _pf.machine().lower() in ('aarch64', 'arm64') and (Path(a.ndk) / base / 'linux-aarch64' / tool).exists(): return 'linux-aarch64'
    return 'linux-x86_64'

host = host_dir('toolchains/llvm/prebuilt', 'bin/clang')
# An ARM64 phone (Linux, aarch64): Google's NDK ships host tools for x86-64 only, so the compiler is the
# phone's own clang (on PATH) and the NDK gives the Android sysroot and the compiler-rt runtime libs.
NX2A_ARM_HOST = sys.platform.startswith('linux') and _pf.machine().lower() in ('aarch64', 'arm64')
bin_ = Path(a.ndk) / 'toolchains/llvm/prebuilt' / host / 'bin'
CC = str(bin_ / ('clang.exe' if os.name == 'nt' else 'clang'))
if NX2A_ARM_HOST:
    _sysroot = next(Path(a.ndk).glob('toolchains/llvm/prebuilt/*/sysroot'), None)
    _res = next(iter(sorted(Path(a.ndk).glob('toolchains/llvm/prebuilt/*/lib/clang/*'))), None)   # the NDK's resource dir: the builtin headers and the compiler-rt
    _sys_clang = _sh.which('clang')
    if not (_sysroot and _res and _sys_clang):
        sys.exit('aarch64 host: need the NDK (its sysroot and runtime libs) and clang on PATH')
    # The flags go on the command line, not into a wrapper script: the wrapper's #!/bin/sh is the phone's own shell, which
    # cannot load the rootfs's libraries (the guest's LD_LIBRARY_PATH names them) and dies before the compiler runs
    CC = _sys_clang
    CC_EXTRA = [f'--sysroot={_sysroot}', f'-resource-dir={_res}', '-rtlib=compiler-rt']
else:
    CC_EXTRA = []
TARGET = f'--target=aarch64-linux-android{a.api}'
out = Path(a.out) if a.out else DM / 'android/out' / (a.game + ('-release' if a.release else ''))
DBG = [] if a.release else ['-g']
obj = out / 'obj'; gen = out / 'gen'; obj.mkdir(parents=True, exist_ok=True); (gen / a.game).mkdir(parents=True, exist_ok=True)

# the game profile header, as the Makefile makes it
subprocess.run([sys.executable, str(DM / 'games/game.py'), 'header', a.game, str(gen / a.game / 'game_profile.h')], check=True)
# and its Android keys (android.tsv): the on-screen gamepad's layout
subprocess.run([sys.executable, str(DM / 'android/tools/android_profile.py'), 'header', a.game, str(gen / a.game / 'android_profile.h')], check=True)

# the backend's own small shaders (dm/android/shaders): compiled with the NDK's glslc, embedded as gen/<name>_<stage>.spv.h
GLSLC = Path(a.ndk) / 'shader-tools' / host_dir('shader-tools', 'glslc') / ('glslc.exe' if os.name == 'nt' else 'glslc')
if NX2A_ARM_HOST and host_dir('shader-tools', 'glslc') != 'linux-aarch64':   # the NDK's shader-tools are host binaries for x86-64: the phone's own glslc
    _glslc = _sh.which('glslc')
    if not _glslc: sys.exit('aarch64 host: glslc not found (install the shaderc tools, or put glslc on PATH)')
    GLSLC = Path(_glslc)
for sh in sorted((DM / 'android/shaders').glob('*.*')):
    hdr = gen / a.game / f'{sh.stem}_{sh.suffix[1:]}.spv.h'
    if hdr.exists() and hdr.stat().st_mtime >= sh.stat().st_mtime: continue
    spv = gen / f'{sh.name}.spv'
    subprocess.run([str(GLSLC), '--target-env=vulkan1.1', '-O', str(sh), '-o', str(spv)], check=True)
    words = spv.read_bytes(); n = len(words) // 4
    import struct as _st
    body = ', '.join(f'{w:#010x}' for w in _st.unpack(f'<{n}I', words))
    hdr.write_text(f'// generated from dm/android/shaders/{sh.name} by build.py (glslc)\nstatic const uint32_t {sh.stem}_{sh.suffix[1:]}_spv[] = {{{body}}};\n')

def srcs(*globs): return [p for g in globs for p in sorted(DM.glob(g))]
HOST = [p for p in srcs('host/*.c') if p.name not in ('hle_movie_avc.c',)]   # VideoToolbox: dm/android/movie_avc.c instead
GFX = srcs('gfx/*.c', 'gfx/nvn/*.c', 'gfx/tex/*.c')
PORT = [p for p in srcs('android/compat/*.c', 'android/*.c') if not (p.name == 'gfx_null.c' and a.gfx != 'null') and not (p.name.startswith('gfx_vk') and a.gfx != 'vk') and not p.name in ('jni_android.c', 'dm_framegen.c', 'vka_shim.c', 'front_android.c')]   # one graphics backend; jni_android.c goes into --play's link, dm_framegen.c + vka_shim.c into every target's (their list below); front_android.c is upstream's APK front, the player front (front_player.c) replaces it
ASM = srcs('host/entry.S', 'gfx/nvn/nvn_entry.S', 'gfx/nvn/nvn_thunks.S')
VPX = srcs('third_party/libvpx/**/*.c')
ZSTD = srcs('third_party/zstd/zstddeclib.c')   # zstd's decompressor: the shader bundle's SPIR-V (gfx_vk_shader.c bundle_spv)

INC = ['-include', str(DM / 'android/compat/include/darwin_compat.h'), f'-I{DM / "android/compat/include"}', f'-I{DM / "host"}', f'-I{DM / "gfx/nvn"}', f'-I{DM / "gfx/tex"}', f'-I{DM / "gfx/nvn/include"}',
       f'-I{DM / "third_party/vulkan-headers/include"}', f'-I{gen / a.game}', f'-I{DM / "third_party/libvpx"}', f'-I{DM / "third_party/stb_vorbis"}', f'-I{DM / "third_party/zstd"}', f'-I{DM / "android"}']
CFLAGS = [TARGET, f'-O{a.O}'] + DBG + ['-fPIC', '-fno-omit-frame-pointer', '-ffixed-x18', '-Wall', '-Wno-unused-function', '-Wno-unused-variable', '-Wno-unused-but-set-variable',
          '-Wno-format', '-Wno-deprecated-declarations', '-Wno-gnu-folding-constant', '-Werror=implicit-function-declaration', '-Werror=return-type',
          '-Werror=incompatible-pointer-types', '-Werror=int-conversion', '-D_GNU_SOURCE', '-DDM_ANDROID=1', '-fno-strict-aliasing', '-fvisibility=hidden'] + (['-DDM_RELEASE=1'] if a.release else []) + ([] if a.no_lsfg else ['-DNX_LSFG=1'])
VPX_FLAGS = [TARGET, '-O2'] + DBG + ['-fPIC', f'-I{DM / "third_party/libvpx"}', f'-I{DM / "third_party/libvpx/config"}', '-w']
ZSTD_FLAGS = [TARGET, '-O2'] + DBG + ['-fPIC', '-fvisibility=hidden', '-DZSTDLIB_VISIBLE=', '-DZSTDERRORLIB_VISIBLE=', '-w']   # its API stays inside libdmhost.so

import hashlib, shutil
_stamp = obj / 'flags.stamp'; _flags = hashlib.sha256(repr((CFLAGS, INC, VPX_FLAGS, ZSTD_FLAGS, CC)).encode()).hexdigest()
if not _stamp.exists() or _stamp.read_text() != _flags:   # compiler flags changed: every object is rebuilt
    shutil.rmtree(obj, ignore_errors=True); obj.mkdir(parents=True, exist_ok=True); _stamp.write_text(_flags)

def dep_stale(o, d, s):
    if not o.exists() or o.stat().st_mtime < s.stat().st_mtime: return True
    if not d.exists(): return True
    t = o.stat().st_mtime
    txt = d.read_text(errors='replace').replace('\\\n', ' ')
    for f in re.split(r'(?<!\\)\s+', txt.split(':', 1)[1] if ':' in txt else ''):
        f = f.replace('\\ ', ' ').strip()
        if f and Path(f).exists() and Path(f).stat().st_mtime > t: return True
    return False

def compile_one(s):
    rel = s.relative_to(DM); o = obj / (str(rel).replace(os.sep, '_').replace('/', '_') + '.o'); d = o.with_suffix('.d')
    src = s
    if s.suffix == '.S':   # Mach-O assembly → ELF
        src = gen / (s.stem + '.elf.S')
        if not src.exists() or src.stat().st_mtime < s.stat().st_mtime:
            subprocess.run([sys.executable, str(DM / 'android/tools/darwin2elf.py'), str(s), str(src)], check=True)
    if not dep_stale(o, d, src): return s, o, 0, ''
    flags = VPX_FLAGS if 'third_party/libvpx' in str(rel).replace(os.sep, '/') else ZSTD_FLAGS if 'third_party/zstd' in str(rel).replace(os.sep, '/') else [TARGET] + DBG if s.suffix == '.S' else CFLAGS + INC
    r = subprocess.run([CC] + CC_EXTRA + flags + ['-MMD', '-MF', str(d), '-c', str(src), '-o', str(o)], capture_output=True, text=True, errors='replace')
    return s, o, r.returncode, r.stderr

def build(files):
    objs, failed = [], []
    with cf.ThreadPoolExecutor(a.jobs) as ex:
        for s, o, rc, err in ex.map(compile_one, files):
            objs.append(o)
            if rc: failed.append(s); print(f'--- {s.relative_to(DM)}\n{err.strip()}\n', file=sys.stderr)
            elif err.strip() and '--verbose' in sys.argv: print(err, file=sys.stderr)
    return objs, failed

# the frame generation travels with every target (the present calls it); its GPL chain only when built in
all_files = HOST + GFX + PORT + ASM + srcs('android/dm_framegen.c') + (srcs('android/lsfg/*.c', 'android/vka_shim.c') if not a.no_lsfg else [])
PLAYS = srcs('android/jni_android.c')   # the player's JNI entry: compiled always (the errors surface here too), linked only for --play
objs, failed = build(all_files)
pobjs, pfailed = build(PLAYS)
vobjs, vfailed = build(VPX + ZSTD)
failed += pfailed + vfailed
print(f'compiled {len(all_files) + len(PLAYS) + len(VPX) + len(ZSTD)} sources, {len(failed)} failed' + (': ' + ', '.join(str(f.relative_to(DM)) for f in failed) if failed else ''))
if failed and not a.keep_going: sys.exit(1)
if a.only_compile: sys.exit(1 if failed else 0)

LIBS = ['-landroid', '-llog', '-lvulkan', '-laaudio', '-lnativewindow', '-lmediandk', '-lz', '-lm', '-ldl']   # -lz: the gamepad theme's PNGs (overlay_theme.c)
LDFLAGS = [TARGET, '-fuse-ld=lld', '-Wl,--error-limit=0', '-Wl,-z,max-page-size=16384', '-Wl,--build-id', '-Wl,-z,nostart-stop-gc']
if a.play:   # libdmplay.so: the whole host + the JNI entry, statically linked (jni_android.c's header comment)
    r = subprocess.run([CC] + CC_EXTRA + LDFLAGS + ['-shared', '-Wl,-soname,libdmplay.so', '-o', str(out / 'libdmplay.so')] + [str(o) for o in objs + pobjs + vobjs] + LIBS, capture_output=True, text=True, errors='replace')
    print(r.stderr.strip() if r.returncode else f'linked {out / "libdmplay.so"}', file=sys.stderr if r.returncode else sys.stdout)
    if r.returncode: sys.exit(1)
    if a.release:   # the static symbol table goes too (the dynamic one stays: the JNI surface)
        _strip = (bin_ / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip'))
        if NX2A_ARM_HOST:
            _strip = Path(_sh.which('llvm-strip') or _sh.which('llvm-strip-18') or _sh.which('strip') or _strip)
        subprocess.run([str(_strip), '--strip-unneeded', str(out / 'libdmplay.so')], check=True)
        print(f'stripped {out / "libdmplay.so"}: {(out / "libdmplay.so").stat().st_size // 1024} KB')
if a.exe:
    r = subprocess.run([CC] + CC_EXTRA + LDFLAGS + ['-pie', '-o', str(out / 'dmhost')] + [str(o) for o in objs + vobjs] + LIBS, capture_output=True, text=True, errors='replace')
    print(r.stderr.strip() if r.returncode else f'linked {out / "dmhost"}', file=sys.stderr if r.returncode else sys.stdout)
    if r.returncode: sys.exit(1)
if a.lib:
    r = subprocess.run([CC] + CC_EXTRA + LDFLAGS + ['-shared', '-Wl,-soname,libdmhost.so', '-o', str(out / 'libdmhost.so')] + [str(o) for o in objs + vobjs] + LIBS, capture_output=True, text=True, errors='replace')
    print(r.stderr.strip() if r.returncode else f'linked {out / "libdmhost.so"}', file=sys.stderr if r.returncode else sys.stdout)
    if r.returncode: sys.exit(1)
    if a.release:   # the static symbol table goes too (the dynamic one stays: ANativeActivity_onCreate)
        _strip = (bin_ / ('llvm-strip.exe' if os.name == 'nt' else 'llvm-strip'))
        if NX2A_ARM_HOST:   # the NDK's llvm-strip is an x86-64 binary: the phone's own
            _strip = Path(_sh.which('llvm-strip') or _sh.which('llvm-strip-18') or _sh.which('strip') or _strip)
        subprocess.run([str(_strip), '--strip-unneeded', str(out / 'libdmhost.so')], check=True)
        print(f'stripped {out / "libdmhost.so"}: {(out / "libdmhost.so").stat().st_size // 1024} KB')

# --adrenotools: libadrenotools.so (gfx_vk_core.c vk_init loads a custom Vulkan driver through it, DM_VK_DRIVER) and the hooks it loads from the APK's library folder,
# as its CMake files build them: linkernsbypass inside libadrenotools and libhook_impl, the three small hooks over libhook_impl with -z global
if a.adrenotools:
    AT = DM / 'third_party/libadrenotools'; LN = AT / 'lib/linkernsbypass'
    ATF = [TARGET, '-O2'] + DBG + ['-fPIC', '-w', f'-I{AT / "include"}', f'-I{AT}', f'-I{LN}']
    ATL = ['-fuse-ld=lld', '-shared', '-Wl,-z,max-page-size=16384', '-static-libstdc++', '-landroid', '-llog', '-ldl']
    CXX = str(bin_ / ('clang++.exe' if os.name == 'nt' else 'clang++'))
    libs = [('libadrenotools.so', [AT / 'src/driver.cpp', AT / 'src/bcenabler.cpp', LN / 'android_linker_ns.cpp', LN / 'elf_soname_patcher.cpp'], []),
            ('libhook_impl.so', [AT / 'src/hook/hook_impl.cpp', LN / 'android_linker_ns.cpp', LN / 'elf_soname_patcher.cpp'], ['-fvisibility=hidden'])]
    libs += [(f'lib{h}.so', [AT / f'src/hook/{h}.c'], ['-Wl,-z,global', f'-L{out}', '-lhook_impl']) for h in ('main_hook', 'file_redirect_hook', 'gsl_alloc_hook')]
    newest = max(p.stat().st_mtime for p in AT.rglob('*') if p.is_file())
    for name, files, extra in libs:
        o = out / name
        if o.exists() and o.stat().st_mtime >= newest: continue
        r = subprocess.run([CXX if any(f.suffix == '.cpp' for f in files) else CC] + CC_EXTRA + ATF + [str(f) for f in files] + ['-o', str(o), f'-Wl,-soname,{name}'] + ATL + extra,
                           capture_output=True, text=True, errors='replace')
        print(r.stderr.strip() if r.returncode else f'linked {o}', file=sys.stderr if r.returncode else sys.stdout)
        if r.returncode: sys.exit(1)
