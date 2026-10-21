#!/usr/bin/env python3
"""Checks the translations in resources/i18n/ against the source code.

Every interface string is written in English inside tr("..."), Tr("...") or
QT_TR_NOOP("...") in qt_player/ and common/. Each resources/i18n/<code>.json
maps those English strings to one language. This script lists, per language:

  missing   strings in the code that the file doesn't translate (they show in English)
  unused    entries the code no longer has (safe to delete)
  mismatch  translations whose %1 / %2 placeholders differ from the English

Run from the project root:

  python scripts/i18n_check.py           report (exit code 1 if anything is missing or mismatched)
  python scripts/i18n_check.py --keys    print every English string, as a JSON template
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SOURCES = [ROOT / "qt_player", ROOT / "common"]
I18N = ROOT / "resources" / "i18n"

# Qt's own button texts (QMessageBox, QDialogButtonBox, text-field menus) are
# looked up the same way; the files may translate these too.
QT_STRINGS = {
    "OK", "Cancel", "Close", "&Yes", "&No", "Yes", "No", "Apply", "Save", "&OK", "&Cancel", "&Close",
    "&Undo", "&Redo", "Cu&t", "&Copy", "&Paste", "Delete", "Select All",
}

CALL = re.compile(r"\b(?:tr|Tr|QT_TR_NOOP)\(\s*")
LITERAL = re.compile(r'"((?:[^"\\\n]|\\.)*)"')
GAP = re.compile(r"(?:\s|//[^\n]*)*")
PLACEHOLDER = re.compile(r"%\d")


def unescape(body):
    out = []
    i = 0
    while i < len(body):
        c = body[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        n = body[i + 1]
        if n == "u":
            out.append(chr(int(body[i + 2:i + 6], 16)))
            i += 6
        elif n == "x":
            m = re.match(r"[0-9a-fA-F]+", body[i + 2:])
            out.append(chr(int(m.group(0), 16)))
            i += 2 + len(m.group(0))
        else:
            out.append({"n": "\n", "t": "\t", '"': '"', "'": "'", "\\": "\\"}.get(n, n))
            i += 2
    # The literals are UTF-8 in the source; \x escapes would be bytes of it.
    return "".join(out)


def source_strings():
    found = {}
    for folder in SOURCES:
        for path in sorted(folder.rglob("*")):
            if path.suffix not in (".cpp", ".hpp"):
                continue
            text = path.read_text(encoding="utf-8")
            for call in CALL.finditer(text):
                pos = call.end()
                parts = []
                while True:
                    m = LITERAL.match(text, pos)
                    if not m:
                        break
                    parts.append(unescape(m.group(1)))
                    pos = GAP.match(text, m.end()).end()
                if parts:
                    line = text.count("\n", 0, call.start()) + 1
                    found.setdefault("".join(parts), f"{path.relative_to(ROOT)}:{line}")
    return found


def main():
    # The strings include "·", "…" and Chinese; a Windows console or pipe
    # defaults to a codepage that can't print them.
    sys.stdout.reconfigure(encoding="utf-8")
    strings = source_strings()
    if "--keys" in sys.argv:
        print(json.dumps({key: "" for key in sorted(strings)}, ensure_ascii=False, indent=2))
        return 0

    problems = 0
    print(f"{len(strings)} strings in the code")
    for path in sorted(I18N.glob("*.json")):
        table = json.loads(path.read_text(encoding="utf-8"))
        missing = [k for k in strings if not table.get(k)]
        unused = [k for k in table if k not in strings and k not in QT_STRINGS]
        mismatch = [k for k, v in table.items()
                    if v and sorted(PLACEHOLDER.findall(k)) != sorted(PLACEHOLDER.findall(v))]
        print(f"\n{path.name}: {len(table)} entries, {len(missing)} missing, {len(unused)} unused, "
              f"{len(mismatch)} placeholder mismatches")
        for k in missing:
            print(f"  missing   {k!r}  ({strings[k]})")
        for k in unused:
            print(f"  unused    {k!r}")
        for k in mismatch:
            print(f"  mismatch  {k!r} -> {table[k]!r}")
        problems += len(missing) + len(mismatch)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
