"""Verify the regenerated Limbo display patches (offline, no build).

Reconstructs each file's fresh-clone pre-image from the workspace, then replays
what `make fetch` does:

    ui/sdl2.c    : sdl-display patch            -> workspace file
    ui/sdl2-2d.c : sdl-scale-sdl2-2d patch      -> workspace file
    ui/sdl2-gl.c : sdl-scale-sdl2-gl patch      -> workspace file
    ui/gtk.c     : gtk-vrr patch (main applied) -> workspace file

Every step must apply, a second run must be detected as "already applied", and
the result must be byte-identical to the workspace file.
"""
import os
import re
import sys

# paths are resolved from this file, so the tool can be run from anywhere
JNI_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.join(JNI_ROOT, "qemu") + os.sep
PATCH_DIR = os.path.join(JNI_ROOT, "patches") + os.sep

SDL_VRR_END = "    return interval;\n}\n#endif\n\n"

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

GTK_NEW = """    /* T = 1 / f = 1 [s*Hz] / f = 1000*1000 [ms*mHz] / f */
#ifdef __LIMBO__
    /* VRR：用户显式选定了目标频率时，输出节奏严格按它走（见上面的定义）。 */
    vc->gfx.dcl.update_interval = limbo_gtk_refresh_interval(refresh_rate);
#else
    vc->gfx.dcl.update_interval = refresh_rate ?
        MIN(1000 * 1000 / refresh_rate, GUI_REFRESH_INTERVAL_DEFAULT) :
        GUI_REFRESH_INTERVAL_DEFAULT;
#endif
}"""

GTK_OLD = """    /* T = 1 / f = 1 [s*Hz] / f = 1000*1000 [ms*mHz] / f */
    vc->gfx.dcl.update_interval = refresh_rate ?
        MIN(1000 * 1000 / refresh_rate, GUI_REFRESH_INTERVAL_DEFAULT) :
        GUI_REFRESH_INTERVAL_DEFAULT;
}"""


def read_text(path):
    with open(path, "r", encoding="utf-8", newline="") as fh:
        return fh.read()


def parse(patch_path):
    lines = read_text(patch_path).split("\n")
    files, cur, i = {}, None, 0
    while i < len(lines):
        line = lines[i]
        if line.startswith("+++ "):
            cur = line[4:].split("\t")[0][2:]
            files.setdefault(cur, [])
        elif line.startswith("@@") and cur is not None:
            m = re.match(r"@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@", line)
            body, i = [], i + 1
            while i < len(lines) and not lines[i].startswith(("@@", "diff --git", "--- ", "+++ ")):
                if lines[i] != "":
                    body.append(lines[i])
                i += 1
            files[cur].append({
                "old_start": int(m.group(1)),
                "new_start": int(m.group(3)),
                "new_len": int(m.group(4)) if m.group(4) else 1,
                "body": body,
                "added": sum(1 for b in body if b.startswith("+")),
                "removed": sum(1 for b in body if b.startswith("-")),
            })
            continue
        i += 1
    return files


def region_matches(region, body, new_view):
    j = 0
    for e in body:
        tag, text = e[0], e[1:]
        if (tag == "+" and not new_view) or (tag == "-" and new_view):
            continue
        if j >= len(region) or region[j] != text:
            return False
        j += 1
    return j == len(region)


def apply_patch(src_text, hunks, allow_already=True):
    out = src_text.split("\n")
    status = "applied"
    offset = 0
    for h in hunks:
        guess = h["old_start"] - 1 + offset
        old_len = sum(1 for e in h["body"] if e[0] in " -")
        start = None
        for probe in range(max(0, guess - 80), min(len(out), guess + 80)):
            if region_matches(out[probe:probe + old_len], h["body"], False):
                start = probe
                break
        if start is None:
            if allow_already:
                g = h["new_start"] - 1
                for probe in range(max(0, g - 80), min(len(out), g + 80)):
                    if region_matches(out[probe:probe + h["new_len"]], h["body"], True):
                        status = "already"
                        break
                else:
                    return None, "FAILED (@@ -%d)" % h["old_start"]
                continue
            return None, "FAILED (@@ -%d)" % h["old_start"]
        region = out[start:start + old_len]
        rebuilt, j = [], 0
        for e in h["body"]:
            tag, text = e[0], e[1:]
            if tag == " ":
                rebuilt.append(region[j])
                j += 1
            elif tag == "+":
                rebuilt.append(text)
            else:
                j += 1
        out[start:start + old_len] = rebuilt
        offset += h["added"] - h["removed"]
        status = "applied"
    return "\n".join(out), status


def sdl2_preimage():
    """Workspace ui/sdl2.c minus VRR (the tree the old scale patch targets),
    then minus that scale block => the fresh-clone pre-image."""
    text = read_text(ROOT + "ui/sdl2.c")

    # 1) drop the VRR helper block
    start = text.index("#ifdef __LIMBO__\n/*\n * Limbo：SDL 显示后端的可变刷新率")
    end = text.index(SDL_VRR_END, start) + len(SDL_VRR_END)
    out = text[:start] + text[end:]
    # 2) revert the two update_interval hunks
    assert SDL_IDLE_NEW in out and SDL_BUSY_NEW in out
    out = out.replace(SDL_IDLE_NEW, SDL_IDLE_OLD).replace(SDL_BUSY_NEW, SDL_BUSY_OLD)
    assert "limbo_sdl_refresh_rate" not in out
    # 3) drop the scale block (its own #ifdef ... #endif)
    return drop_limbo_block(out, "int limbo_sdl_scale_mode = -1;")


def drop_limbo_block(text, legend):
    """Remove the `#ifdef __LIMBO__ ... #endif` block that contains `legend`."""
    start = text.index(legend)
    opening = text.rindex("\n#ifdef __LIMBO__\n", 0, start) + 1
    end_marker = "\n#endif\n"
    end = text.index(end_marker, start) + len(end_marker)
    if text[end:end + 1] == "\n":
        end += 1
    return text[:opening] + text[end:]


def scale_block_preimage(rel):
    text = read_text(ROOT + rel)
    # both files start their Limbo scale helpers with this extern declaration
    return drop_limbo_block(text, "extern int limbo_sdl_scale_mode;")


def gtk_preimage():
    text = read_text(ROOT + "ui/gtk.c")
    start = text.index("#ifdef __LIMBO__\n/*\n * Variable refresh rate (VRR) target")
    marker = "    return MAX(1, 1000 * 1000 / mhz);\n}\n#endif\n\n"
    end = text.index(marker, start) + len(marker)
    out = text[:start] + text[end:]
    assert GTK_NEW in out
    out = out.replace(GTK_NEW, GTK_OLD, 1)
    assert "limbo_gtk_refresh_rate" not in out
    return out


def main():
    problems = []

    cases = [
        # (file, patch, pre-image, symbol that must only appear in the *result*)
        # the 2d/gl pre-images still carry the `extern int limbo_sdl_scale_mode;`
        # declaration; only the definition is added by those patches.
        ("ui/sdl2.c", "qemu-v11.0.0-limbo-sdl-display.patch",
         sdl2_preimage(), "limbo_sdl_scale_mode"),
        ("ui/sdl2-2d.c", "qemu-v11.0.0-limbo-sdl-scale-sdl2-2d.patch",
         scale_block_preimage("ui/sdl2-2d.c"), "int limbo_sdl_scale_mode = -1;"),
        ("ui/sdl2-gl.c", "qemu-v11.0.0-limbo-sdl-scale-sdl2-gl.patch",
         scale_block_preimage("ui/sdl2-gl.c"), "int limbo_sdl_scale_mode = -1;"),
        ("ui/gtk.c", "qemu-v11.0.0-limbo-gtk-vrr.patch", gtk_preimage(), None),
    ]

    for rel, patch_name, pre, must_not_contain in cases:
        if must_not_contain and must_not_contain in pre:
            problems.append(f"{rel}: pre-image already contains {must_not_contain}")
        hunks = parse(PATCH_DIR + patch_name)[rel]
        target = read_text(ROOT + rel)
        res, status = apply_patch(pre, hunks)
        print(f"{rel:12s} {patch_name:44s} fresh clone: {status}")
        if status != "applied":
            problems.append(f"{rel}: patch does not apply to the pre-image")
            continue
        if res != target:
            problems.append(f"{rel}: result != workspace file")
            import difflib
            diff = list(difflib.unified_diff(res.split("\n"), target.split("\n"),
                                             "patched", "workspace", lineterm="", n=1))
            print("\n".join(diff[:40]))
        else:
            print(f"{rel:12s} {'':44s} result == workspace file")
        _, again = apply_patch(res, hunks)
        if again != "already":
            problems.append(f"{rel}: re-run not detected as already applied ({again})")

    for p in problems:
        print("!!", p)
    print("RESULT:", "OK" if not problems else "PROBLEM")
    return 1 if problems else 0


sys.exit(main())
