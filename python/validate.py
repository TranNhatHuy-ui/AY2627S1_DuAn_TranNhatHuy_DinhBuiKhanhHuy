#!/usr/bin/env python3
"""
Checks the modified AlgoHAEI (src/) against the authors' UNMODIFIED code, using reference values that were recorded once from
the authors' own program (so the original source does not have to be shipped):

  * the authors' MAEI, https://github.com/Nicco-zxw/MAEI, commit e284fc700817f1de59a9ff75de72607ab96934af (2025-12-29),
    file src/AlgoHAEI.java, run on the files in data/ with the thresholds below.

For every case the default configuration (FULL) must reproduce the reference EXACTLY: number of itemsets, the fingerprint of the set of
itemsets, the minimum / maximum / sum of the average-efficiency values ae(X) (compared as exact doubles) and the join count.
Every other variant (pruning off, other item orders, BFS) and both HAEIMiner baselines must find the same itemsets (same fingerprint)
and ae values equal to the reference within a relative difference of 1e-9. Writes results/validation.txt.

    python validate.py
"""
import json, os, subprocess, sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "src")
OUT = os.path.join(ROOT, "out")

# itemset_hash: order-independent 64-bit fingerprint of the set of itemsets (one splitmix64-based hash per itemset, summed; see RunOne.java).
# ae_sum: the ae values added left to right in the order the authors' program wrote them.
REFERENCE = {
    "chess":    dict(minae=50, itemsets=158, joins=277, ae_min=50.17441860465119, ae_max=908.7906976744114,
                     ae_sum=21552.551275890604, itemset_hash=9046394167275406880),
    "mushroom": dict(minae=10, itemsets=121, joins=249, ae_min=10.243096819293953, ae_max=3879.599999999994,
                     ae_sum=16595.83623860645, itemset_hash=3232613181780933980),
    "foodmart": dict(minae=5, itemsets=592, joins=688, ae_min=5.0, ae_max=1038.2857142857142,
                     ae_sum=27469.272816715846, itemset_hash=-4007043517984739624),
}
DATA = {"chess": ("data/chess_DBUtility.txt", "data/chess_stock.txt"),
        "mushroom": ("data/mushroom_DBUtility.txt", "data/mushroom_stock.txt"),
        "foodmart": ("data/foodmart_DBUtility.txt", "data/foodmart_stock.txt")}
VARIANTS = [("FULL (default)", []), ("FULL_LAZY (lazy EAECS)", ["-lazy", "on"]), ("NO_EAECP", ["-eaecp", "off"]), ("NO_MAEP", ["-maep", "off"]),
            ("NEITHER", ["-maep", "off", "-eaecp", "off"]), ("order AEUB_DESC", ["-order", "AEUB_DESC"]),
            ("order INVESTMENT_ASC", ["-order", "INVESTMENT_ASC"]), ("order RANDOM", ["-order", "RANDOM"]),
            ("BFS", ["-traversal", "BFS"]), ("HAEIMiner (memory)", ["-algo", "HAEIMiner"]),
            ("HAEIMiner (disk scans)", ["-algo", "HAEIMiner", "-disk", "on"])]


def main():
    """Runs every variant on the three reference cases, compares with REFERENCE, prints and saves the report; exit code 1 on any failure."""
    if not os.path.exists(os.path.join(OUT, "RunOne.class")):
        os.makedirs(OUT, exist_ok=True)
        subprocess.run(["javac", "-d", OUT] + [os.path.join(SRC, f) for f in os.listdir(SRC) if f.endswith(".java")], check=True)
    lines = ["Reference: the authors' unmodified AlgoHAEI.java (github.com/Nicco-zxw/MAEI, commit e284fc700817f1de59a9ff75de72607ab96934af)."]
    bad = 0
    for name, ref in REFERENCE.items():
        data, stock = (os.path.join(ROOT, f) for f in DATA[name])
        if not os.path.exists(data):
            lines.append("\n%s: data not found, skipped" % name); continue
        lines.append("\n%s  minae=%s   reference: %d itemsets, %d joins" % (name, ref["minae"], ref["itemsets"], ref["joins"]))
        for label, extra in VARIANTS:
            p = subprocess.run(["java", "-cp", OUT, "RunOne", "-data", data, "-stock", stock, "-minae", str(ref["minae"])] + extra,
                               capture_output=True, text=True)
            r = json.loads(p.stdout.strip().splitlines()[-1])
            default = label == "FULL (default)"
            same_sets = r["haei_count"] == ref["itemsets"] and r["itemset_hash"] == ref["itemset_hash"]
            worst = max(abs(r[k] - ref[k]) / max(abs(ref[k]), 1e-300) for k in ("ae_min", "ae_max", "ae_sum"))
            same_ae = same_sets and (worst == 0.0 if default else worst <= 1e-9)
            note = ""
            if default:
                note = "  <- must equal the authors' original exactly (itemsets, ae values AND join count)"
                bad += not (same_sets and same_ae and r["join_count"] == ref["joins"])
            else:
                bad += not (same_sets and same_ae)
            lines.append("  %-24s same itemsets: %s   same ae values: %s (max relative difference %.1e)   joins/scans: %d%s" % (
                label, "YES" if same_sets else "NO ", "YES" if same_ae else "NO ", worst, r["join_count"], note))
    lines.append("\nRESULT: " + ("ALL CHECKS PASSED" if bad == 0 else "%d CHECK(S) FAILED" % bad))
    text = "\n".join(lines)
    print(text)
    os.makedirs(os.path.join(ROOT, "results"), exist_ok=True)
    with open(os.path.join(ROOT, "results", "validation.txt"), "w") as f:
        f.write(text + "\n")
    sys.exit(0 if bad == 0 else 1)


if __name__ == "__main__":
    main()
