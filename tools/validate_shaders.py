#!/usr/bin/env python3
"""Offline check of every shader program the mod builds.

Mirrors Shaders.source() in the mod (inject #defines after the #version line, resolve #include "x"), writes the
result to a temp dir and runs glslangValidator on it. Default target is desktop OpenGL (what the port uses);
pass --vulkan to check the original Vulkan sources instead.

usage: validate_shaders.py <shader dir> [--vulkan]
"""
import subprocess
import sys
import tempfile
from pathlib import Path

# (file, stage, defines) - keep in sync with the ComputeProgram/GraphicsProgram constructors in the Java code.
COMPUTE = [
    "args.comp", "emit.comp", "clear_box.comp", "p2g.comp", "classify.comp", "faces.comp", "divergence.comp",
    "project.comp", "extrapolate.comp", "potential.comp", "g2p.comp", "ww_update.comp", "inflow.comp", "probe.comp",
    "shift.comp", "remove.comp", "merge.comp", "blockfill.comp", "mpm_clear.comp", "mpm_p2g.comp", "mpm_grid.comp",
    "mpm_g2p.comp", "fine_clear.comp", "blur.comp", "nets_vertices.comp", "nets_quads.comp", "mesh_args.comp",
    "sheet_fill.comp", "zb_splat.comp", "zb_field.comp", "mg_flags0.comp", "mg_coarsen.comp", "mg_smooth.comp",
    "mg_restrict.comp", "mg_prolong.comp", "cg_init.comp", "cg_apply.comp", "cg_dot.comp", "cg_reduce.comp",
    "cg_update.comp", "cg_direction.comp", "mg_coarse.comp", "grain_cull.comp",
]
PROGRAMS = [(c, {}) for c in COMPUTE] + [("blockfill.comp", {"MPM": "1"})]
DROPLETS = {"DROPLETS": "1", "VIEW_BINDING": "3"}
GRAPHICS = [
    ("mesh.vert", "mesh_front.frag", {}),
    ("particle.vert", "droplet_front.frag", DROPLETS),
    ("mesh.vert", "mesh_thickness.frag", {}),
    ("particle.vert", "droplet_thickness.frag", DROPLETS),
    ("particle.vert", "whitewater.frag", {"VIEW_BINDING": "4"}),
    ("fullscreen.vert", "composite.frag", {}),
    ("grain.vert", "grain.frag", {}),
]


def preprocess(root: Path, name: str, seen: set) -> str:
    out = []
    for line in (root / name).read_text().replace("\r\n", "\n").split("\n"):
        t = line.strip()
        if t.startswith("#include"):
            inc = t[t.index('"') + 1:t.rindex('"')]
            if inc not in seen:
                seen.add(inc)
                out.append(preprocess(root, inc, seen))
        else:
            out.append(line)
    return "\n".join(out)


def source(root: Path, name: str, defines: dict) -> str:
    text = (root / name).read_text().replace("\r\n", "\n")
    nl = text.index("\n")
    head = text[:nl + 1] + "".join(f"#define {k} {v}\n" for k, v in defines.items())
    (root / ".__tmp").write_text(head + text[nl + 1:])
    try:
        return preprocess(root, ".__tmp", set())
    finally:
        (root / ".__tmp").unlink()


def check(root: Path, tmp: Path, name: str, defines: dict, vulkan: bool) -> tuple[bool, str]:
    tag = "_".join(f"{k}{v}" for k, v in defines.items())
    out = tmp / (name.replace(".", f"{'_' + tag if tag else ''}."))
    out.write_text(source(root, name, defines))
    cmd = ["glslangValidator"]
    cmd += ["-V", "--target-env", "vulkan1.2"] if vulkan else []
    cmd += ["-o", str(tmp / "out.spv")] if vulkan else []
    cmd.append(str(out))
    r = subprocess.run(cmd, capture_output=True, text=True)
    msg = (r.stdout + r.stderr).strip()
    return r.returncode == 0, msg


def main() -> int:
    root = Path(sys.argv[1])
    vulkan = "--vulkan" in sys.argv
    failures = 0
    checked = set()
    with tempfile.TemporaryDirectory() as t:
        tmp = Path(t)
        jobs = [(n, d) for n, d in PROGRAMS]
        for v, f, d in GRAPHICS:
            jobs += [(v, d), (f, d)]
        for name, defines in jobs:
            key = (name, tuple(sorted(defines.items())))
            if key in checked:
                continue
            checked.add(key)
            ok, msg = check(root, tmp, name, defines, vulkan)
            if not ok:
                failures += 1
                print(f"FAIL {name} {defines}\n{msg}\n")
        linked = 0
        if not vulkan:
            for v, f, d in GRAPHICS:
                vs = tmp / f"link_{len(d)}_{v}"
                fs = tmp / f"link_{len(d)}_{f}"
                vs.write_text(source(root, v, d))
                fs.write_text(source(root, f, d))
                r = subprocess.run(["glslangValidator", "-l", str(vs), str(fs)], capture_output=True, text=True)
                if r.returncode != 0:
                    failures += 1
                    print(f"LINK FAIL {v}+{f} {d}\n{(r.stdout + r.stderr).strip()}\n")
                else:
                    linked += 1
    print(f"{len(checked)} shader stages checked, {linked} programs linked, {failures} failures ({'vulkan' if vulkan else 'opengl'})")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
