"""
Builds app/src/main/assets/autoeq.tsv, the headphone correction profiles
bundled with the app, from a checkout of https://github.com/jaakkopasanen/AutoEq
(MIT). Only the ParametricEQ.txt files are needed:

    git clone --filter=blob:none --no-checkout --depth 1 https://github.com/jaakkopasanen/AutoEq.git
    cd AutoEq
    git config core.longpaths true          # Windows: some paths are long
    git sparse-checkout init --no-cone
    echo "/results/**/*ParametricEQ.txt" > .git/info/sparse-checkout
    git checkout
    python tools/autoeq_import.py path/to/AutoEq   (run from android/)

One profile per line, tab-separated:
    name, source, kind ("over-ear", "711 in-ear", ...), preamp dB, filters
Filters are space-separated "<type><Fc>,<gain dB>,<Q>", type L = low shelf,
H = high shelf, P = peaking. See data/HeadphoneProfiles.kt.
"""
import pathlib
import re
import subprocess
import sys

FILTER = re.compile(r"^Filter \d+: ON (PK|LSC|HSC) Fc ([\d.]+) Hz Gain (-?[\d.]+) dB Q ([\d.]+)$")
PREAMP = re.compile(r"^Preamp: (-?[\d.]+) dB$")
TYPES = {"PK": "P", "LSC": "L", "HSC": "H"}
# Shown first when a headphone was measured by several sources.
PREFERRED = ["oratory1990", "crinacle", "Rtings", "Innerfidelity"]


def num(text):
    value = float(text)
    return str(int(value)) if value == int(value) else f"{value:g}"


def main(autoeq_root):
    root = pathlib.Path(autoeq_root)
    results = root / "results"
    rows = []
    for path in results.rglob("*ParametricEQ.txt"):
        parts = path.relative_to(results).parts  # source, kind, name, file
        if len(parts) != 4:
            continue
        source, kind, name = parts[0], parts[1], parts[2]
        preamp, filters = None, []
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if m := PREAMP.match(line):
                preamp = num(m.group(1))
            elif m := FILTER.match(line):
                kind_code = TYPES[m.group(1)]
                filters.append(f"{kind_code}{num(m.group(2))},{num(m.group(3))},{num(m.group(4))}")
        if preamp is None or not filters or "\t" in name:
            continue
        rows.append((name, source, kind, preamp, " ".join(filters)))

    def order(row):
        rank = PREFERRED.index(row[1]) if row[1] in PREFERRED else len(PREFERRED)
        return (row[0].casefold(), rank, row[1].casefold(), row[2])

    rows.sort(key=order)
    try:
        commit = subprocess.run(["git", "-C", str(root), "rev-parse", "--short", "HEAD"],
                                capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        commit = "unknown"
    out = pathlib.Path("app/src/main/assets/autoeq.tsv")
    with out.open("w", encoding="utf-8", newline="\n") as f:
        f.write(f"# AutoEq {commit}, MIT License, Copyright (c) 2018-2022 Jaakko Pasanen\n")
        for row in rows:
            f.write("\t".join(row) + "\n")
    print(f"{len(rows)} profiles -> {out}")


if __name__ == "__main__":
    main(sys.argv[1])
