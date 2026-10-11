"""Regenerate the Limbo display patches (ui/sdl2.c, ui/sdl2-2d.c, ui/sdl2-gl.c,
ui/gtk.c) as clean unified diffs.

The workspace QEMU tree is the source of truth: it already carries the scale
patch and the VRR code.  Every generated patch gets the *fresh-clone* pre-image
as its "before" side, so `git apply` finds its context on a new clone:

    main patch -> sdl2-2d / sdl2-gl / sdl2-display / gtk-vrr

The old standalone VRR patches never applied (their hunk headers and context
came from a different tree), and `git apply --check ... && git apply ...`
silently skipped them, so the engine was built without any refresh-rate control
and stayed at QEMU's idle pace (~33-40 fps).
"""
import difflib
import os
import sys

# paths are resolved from this file, so the tool can be run from anywhere
JNI_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(JNI_ROOT, "qemu") + os.sep
PATCH_DIR = os.path.join(JNI_ROOT, "patches") + os.sep


def read_text(path):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def write_patch(path, body):
    with open(path, "w", encoding="utf-8", newline="") as fh:
        fh.write(body)


SDL_IDLE_NEW = """            if (scon->idle_counter >= SDL2_MAX_IDLE_COUNT) {
#ifdef __LIMBO__
                scon->dcl.update_interval = limbo_sdl_refresh_interval(true);
#else
                scon->dcl.update_interval = GUI_REFRESH_INTERVAL_DEFAULT;
#endif
            }"""
SDL_IDLE_OLD = """            if (scon->idle_counter >= SDL2_MAX_IDLE_COUNT) {
                scon->dcl.update_interval = GUI_REFRESH_INTERVAL_DEFAULT;
            }"""

SDL_BUSY_NEW = """        scon->idle_counter = 0;
#ifdef __LIMBO__
        scon->dcl.update_interval = limbo_sdl_refresh_interval(false);
#else
        scon->dcl.update_interval = SDL2_REFRESH_INTERVAL_BUSY;
#endif
    }"""
SDL_BUSY_OLD = """        scon->idle_counter = 0;
        scon->dcl.update_interval = SDL2_REFRESH_INTERVAL_BUSY;
    }"""


def sdl2_preimage(sdl):
    """Workspace ui/sdl2.c minus the VRR code and minus the scale block."""
    # VRR helper block (ends right after the helper function's #endif)
    opener = "#ifdef __LIMBO__\n/*\n * Limbo：SDL 显示后端的可变刷新率"
    start = sdl.index(opener)
    end = sdl.index("    return interval;\n}\n#endif\n\n", start) + \
        len("    return interval;\n}\n#endif\n\n")
    out = sdl[:start] + sdl[end:]
    # the two update_interval hunks
    assert SDL_IDLE_NEW in out, "idle VRR hunk not found"
    assert SDL_BUSY_NEW in out, "busy VRR hunk not found"
    out = out.replace(SDL_IDLE_NEW, SDL_IDLE_OLD).replace(SDL_BUSY_NEW, SDL_BUSY_OLD)
    assert "limbo_sdl_refresh_interval" not in out, "VRR code left behind"

    # scale block
    opener = "#ifdef __LIMBO__\n/*\n * Limbo：SDL 显示后端的缩放模式"
    start = out.index(opener)
    end = out.index("int limbo_sdl_scale_mode = -1;\n#endif\n\n", start) + \
        len("int limbo_sdl_scale_mode = -1;\n#endif\n\n")
    return out[:start] + out[end:]


def helper_block_preimage(text):
    """Fresh-clone pre-image of ui/sdl2-2d.c / ui/sdl2-gl.c (drop the whole
    leading `#ifdef __LIMBO__ ... #endif` helper block)."""
    start = text.index("#ifdef __LIMBO__\n")
    end = text.index("\n#endif\n", start) + len("\n#endif\n")
    if text[end:end + 1] == "\n":
        end += 1
    return text[:start] + text[end:]


def gtk_preimage(gtk):
    """Workspace ui/gtk.c minus the VRR global/helper and its update_interval use."""
    opener = "#ifdef __LIMBO__\n/*\n * Variable refresh rate (VRR) target"
    start = gtk.index(opener)
    end = gtk.index("    return MAX(1, 1000 * 1000 / mhz);\n}\n#endif\n\n", start) + \
        len("    return MAX(1, 1000 * 1000 / mhz);\n}\n#endif\n\n")
    out = gtk[:start] + gtk[end:]

    new_body = """    /* T = 1 / f = 1 [s*Hz] / f = 1000*1000 [ms*mHz] / f */
#ifdef __LIMBO__
    /* VRR：用户显式选定了目标频率时，输出节奏严格按它走（见下面的注释）。 */
    vc->gfx.dcl.update_interval = limbo_gtk_refresh_interval(refresh_rate);
#else
    vc->gfx.dcl.update_interval = refresh_rate ?
        MIN(1000 * 1000 / refresh_rate, GUI_REFRESH_INTERVAL_DEFAULT) :
        GUI_REFRESH_INTERVAL_DEFAULT;
#endif
}"""
    old_body = """    /* T = 1 / f = 1 [s*Hz] / f = 1000*1000 [ms*mHz] / f */
    vc->gfx.dcl.update_interval = refresh_rate ?
        MIN(1000 * 1000 / refresh_rate, GUI_REFRESH_INTERVAL_DEFAULT) :
        GUI_REFRESH_INTERVAL_DEFAULT;
}"""
    assert new_body in out, "gtk update_interval body not found"
    out = out.replace(new_body, old_body, 1)
    assert "limbo_gtk_refresh_rate" not in out
    return out


def make_diff(rel, pre, post):
    return "".join(difflib.unified_diff(
        pre.splitlines(keepends=True), post.splitlines(keepends=True),
        fromfile="a/" + rel, tofile="b/" + rel, n=3))


def emit(name, rel, pre, post):
    body = make_diff(rel, pre, post)
    assert body, f"{rel}: empty diff"
    write_patch(PATCH_DIR + name,
                "diff --git a/%s b/%s\n" % (rel, rel) + body)
    added = sum(1 for l in body.splitlines()
                if l.startswith("+") and not l.startswith("+++"))
    print(f"{name:44s} {rel:14s} {added:4d} added line(s)")


def main():
    sdl = read_text(ROOT + "ui/sdl2.c")
    sdl2d = read_text(ROOT + "ui/sdl2-2d.c")
    sdlgl = read_text(ROOT + "ui/sdl2-gl.c")
    gtk = read_text(ROOT + "ui/gtk.c")

    pre_sdl = sdl2_preimage(sdl)
    assert "limbo_sdl_scale_mode" not in pre_sdl and "limbo_sdl_refresh_rate" not in pre_sdl

    emit("qemu-v11.0.0-limbo-sdl-display.patch", "ui/sdl2.c", pre_sdl, sdl)
    emit("qemu-v11.0.0-limbo-sdl-scale-sdl2-2d.patch", "ui/sdl2-2d.c",
         helper_block_preimage(sdl2d), sdl2d)
    emit("qemu-v11.0.0-limbo-sdl-scale-sdl2-gl.patch", "ui/sdl2-gl.c",
         helper_block_preimage(sdlgl), sdlgl)
    emit("qemu-v11.0.0-limbo-gtk-vrr.patch", "ui/gtk.c", gtk_preimage(gtk), gtk)
    return 0


sys.exit(main())
