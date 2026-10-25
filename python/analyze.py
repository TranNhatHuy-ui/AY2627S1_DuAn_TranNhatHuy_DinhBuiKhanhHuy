#!/usr/bin/env python3
"""
Turns results/results.csv into tables (results/tables.md) and figures (results/*.png).

    pip install pandas matplotlib
    python analyze.py                       # reads ../results/results.csv
    python analyze.py path/to/results.csv

Runtime / memory are medians over the successful repetitions. join_count is deterministic (for HAEIMiner it is the
number of database scans, which is NOT comparable with MAEI's join count - it is only shown in the tables).
The last section compares the efficiency values ae(X) and e(X) of the mined itemsets across all configurations (needs results.csv written by the
current code and/or efficiency.csv from `python run_all.py --axes efficiency`).
peak_heap_mb is the sampled peak heap in use; "extra heap" = peak minus the heap in use before the run.
"""
import os, sys
import pandas as pd
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

from matplotlib.ticker import ScalarFormatter, NullFormatter, FuncFormatter


def minae_axis(a, values, xlabel="minae (decreasing →)"):
    """Log x-axis that shows the minae values really used for this dataset as tick labels (each dataset has its own thresholds,
    so every dataset gets its own panel and its own axis); thresholds decrease to the right, i.e. more itemsets to the right."""
    vals = sorted(set(float(v) for v in values))
    a.set_xscale("log"); a.set_xticks(vals)
    a.xaxis.set_major_formatter(FuncFormatter(lambda v, _: f"{v:g}")); a.xaxis.set_minor_formatter(NullFormatter())
    a.invert_xaxis(); a.set_xlabel(xlabel); a.tick_params(axis="x", labelsize=8)


def ratio_axis(a, ylabel, vals, headroom=False):
    """Linear y-axis for a ratio with a reference line at 1; the range always contains 1 and all values (plus room for labels)."""
    vals = [float(v) for v in vals] + [1.0]
    a.set_ylim(min(vals) * 0.88, max(vals) * (1.22 if headroom else 1.12))
    a.axhline(1, color="black", lw=.9); a.grid(alpha=.3); a.set_ylabel(ylabel)


HERE = os.path.dirname(os.path.abspath(__file__))
CSV = sys.argv[1] if len(sys.argv) > 1 else os.path.join(HERE, "..", "results", "results.csv")
OUT = os.path.dirname(os.path.abspath(CSV))   # tables and figures go next to results.csv
os.makedirs(OUT, exist_ok=True)

df = pd.read_csv(CSV)
if "eaecs_ms" not in df.columns:   # results.csv written before the lazy-EAECS experiment existed
    df["eaecs_ms"] = float("nan")
for c in ["runtime_ms", "peak_heap_mb", "baseline_heap_mb", "join_count", "haei_count", "candidates", "minae", "frac", "eaecs_ms"]:
    df[c] = pd.to_numeric(df[c], errors="coerce")
df["extra_heap_mb"] = df["peak_heap_mb"] - df["baseline_heap_mb"]
key = ["axis", "dataset", "minae", "variant", "order", "traversal", "frac"]
ok = df[df.status == "ok"]
g = ok.groupby(key, dropna=False).agg(runtime_s=("runtime_ms", lambda x: x.median() / 1000),
                                       extra_heap_mb=("extra_heap_mb", "median"),
                                       eaecs_ms=("eaecs_ms", "median"),
                                       joins=("join_count", "first"), haei=("haei_count", "first"),
                                       candidates=("candidates", "first"), reps=("runtime_ms", "count")).reset_index()
bad = df[df.status != "ok"].groupby(key, dropna=False).status.first().reset_index()
datasets = [d for d in ["chess", "mushroom", "foodmart", "accidents"] if d in set(df.dataset)]
VARS = ["FULL", "NO_EAECP", "NO_MAEP", "NEITHER", "HAEIMiner_mem", "HAEIMiner_disk"]
COL = {"FULL": "#1b9e77", "NO_EAECP": "#7570b3", "NO_MAEP": "#d95f02", "NEITHER": "#e7298a",
       "HAEIMiner_mem": "#666666", "HAEIMiner_disk": "#000000"}
md = ["# Ablation results (medians over repetitions)\n",
      "Cells are `runtime (s) / join calls`; for HAEIMiner the second number is database scans. `timeout` = the run did not finish within the time limit.\n"]

# ---------------- pruning ----------------
P = g[g.axis == "pruning"]
Pb = bad[bad.axis == "pruning"]
for d in datasets:
    s, sb = P[P.dataset == d], Pb[Pb.dataset == d]
    if s.empty and sb.empty: continue
    ms = sorted(set(s.minae) | set(sb.minae), reverse=True)
    md.append(f"\n## Pruning variants - {d}\n")
    md.append("| Variant | " + " | ".join(f"minae {m:g}" for m in ms) + " |")
    md.append("|---|" + "---|" * len(ms))
    hae = s[s.variant == "FULL"].set_index("minae").haei
    md.append("| *HAEIs found* | " + " | ".join(f"{int(hae[m]):,}" if m in hae.index else "?" for m in ms) + " |")
    for v in VARS:
        cells = []
        for m in ms:
            r = s[(s.variant == v) & (s.minae == m)]
            if len(r): cells.append(f"{r.runtime_s.iloc[0]:.2f} / {int(r.joins.iloc[0]):,}")
            elif len(sb[(sb.variant == v) & (sb.minae == m)]): cells.append("timeout")
            else: cells.append("-")
        md.append(f"| {v} | " + " | ".join(cells) + " |")

# contribution table at the middle threshold
md.append("\n## Stand-alone contribution of each pruning strategy (join calls, % change vs NEITHER)\n")
md.append("| Dataset | minae | NEITHER | MAEP only (NO_EAECP) | EAECP only (NO_MAEP) | Both (FULL) |\n|---|---|---|---|---|---|")
for d in datasets:
    s = P[P.dataset == d]
    for m in sorted(set(s.minae), reverse=True):
        r = s[s.minae == m].set_index("variant").joins
        if all(v in r.index for v in ["NEITHER", "NO_EAECP", "NO_MAEP", "FULL"]):
            n = r["NEITHER"]
            md.append(f"| {d} | {m:g} | {int(n):,} | {int(r['NO_EAECP']):,} ({(r['NO_EAECP']/n-1)*100:.0f}%) | "
                      f"{int(r['NO_MAEP']):,} ({(r['NO_MAEP']/n-1)*100:.0f}%) | {int(r['FULL']):,} ({(r['FULL']/n-1)*100:.0f}%) |")

# figure: runtime and joins vs minae
if datasets:
    fig, ax = plt.subplots(2, len(datasets), figsize=(4.6 * len(datasets), 7), squeeze=False)
    for j, d in enumerate(datasets):
        s = P[P.dataset == d]
        for v in VARS:
            r = s[s.variant == v].sort_values("minae")
            if len(r):
                ax[0, j].plot(r.minae, r.runtime_s, marker="o", label=v, color=COL[v])
                if v in ("FULL", "NO_EAECP", "NO_MAEP", "NEITHER"):
                    ax[1, j].plot(r.minae, r.joins, marker="o", label=v, color=COL[v])
        ax[0, j].set_title(d); ax[0, j].set_ylabel("runtime (s)"); ax[1, j].set_ylabel("join calls")
        for i in range(2):
            ax[i, j].set_yscale("log"); minae_axis(ax[i, j], s.minae)
            ax[i, j].grid(alpha=.3)
        if j == 0: ax[0, j].legend(fontsize=7); ax[1, j].legend(fontsize=7)
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "fig_pruning.png"), dpi=140); plt.close()

# ---------------- ratio figures: does EAECP pay off? / where does MAEI overtake the baseline? ----------------
# One panel per dataset, each with its own x-axis showing the minae values actually used for that dataset.
def runtime_ratio(s, num, den):
    """Median runtime of variant `num` divided by that of `den`, per minae (only where both finished)."""
    a = s[s.variant == num].set_index("minae").runtime_s
    b = s[s.variant == den].set_index("minae").runtime_s
    r = (a / b).dropna()
    return r.sort_index(ascending=False)

if datasets and not P.empty:
    md.append("\n## Runtime ratios per threshold\n")
    md.append("`NO_EAECP / FULL` above 1: EAECP pays for building EAECS; below 1: it does not. "
              "`HAEIMiner_mem / FULL` above 1: MAEI is faster than the two-phase baseline. `timeout` = the baseline did not finish.\n")
    md.append("| Dataset | minae | NO_EAECP / FULL | HAEIMiner_mem / FULL |\n|---|---|---|---|")
    figs = [("fig_eaecp_pays.png", "NO_EAECP", "time(NO_EAECP) / time(FULL)", "Does EAECP pay for building EAECS?  (<1: it does not)"),
            ("fig_vs_baseline.png", "HAEIMiner_mem", "time(HAEIMiner_mem) / time(FULL)", "Where MAEI overtakes the two-phase baseline  (>1: MAEI faster)")]
    for fname, num, ylabel, title in figs:
        fig, ax = plt.subplots(1, len(datasets), figsize=(4.4 * len(datasets), 3.9), squeeze=False)
        for j, d in enumerate(datasets):
            s_, sb_ = P[P.dataset == d], Pb[Pb.dataset == d]
            r = runtime_ratio(s_, num, "FULL")
            a = ax[0, j]
            if len(r):
                a.plot(r.index, r.values, marker="o", color="#1b9e77" if num == "NO_EAECP" else "#d95f02")
                for x, y in zip(r.index, r.values):
                    a.annotate(f"{y:.2f}", (x, y), textcoords="offset points", xytext=(0, 7), ha="center", fontsize=7)
            all_m = sorted(set(s_.minae) | set(sb_.minae))
            to = sorted(set(sb_[sb_.variant == num].minae) & set(s_[s_.variant == "FULL"].minae))
            minae_axis(a, all_m)
            ratio_axis(a, ylabel if j == 0 else "", r.values if len(r) else [], headroom=bool(to))
            for m in to:      # baseline timed out where FULL finished: MAEI is at least this much faster, mark it at the top
                a.scatter([m], [a.get_ylim()[1] * 0.9], marker="^", color="red", zorder=5)
                a.annotate("timeout", (m, a.get_ylim()[1] * 0.9), textcoords="offset points", xytext=(0, -12), ha="center", fontsize=7, color="red")
            a.set_title(d)
        fig.suptitle(title, fontsize=10)
        plt.tight_layout(); plt.savefig(os.path.join(OUT, fname), dpi=140); plt.close()
    for d in datasets:
        s_, sb_ = P[P.dataset == d], Pb[Pb.dataset == d]
        r1, r2 = runtime_ratio(s_, "NO_EAECP", "FULL"), runtime_ratio(s_, "HAEIMiner_mem", "FULL")
        to2 = set(sb_[sb_.variant == "HAEIMiner_mem"].minae)
        for m in sorted(set(s_.minae) | set(sb_.minae), reverse=True):
            c1 = f"{r1[m]:.2f}" if m in r1.index else "-"
            c2 = f"{r2[m]:.2f}" if m in r2.index else ("timeout" if m in to2 else "-")
            md.append(f"| {d} | {m:g} | {c1} | {c2} |")

# ---------------- order ----------------
O = g[g.axis == "order"]
if not O.empty:
    md.append("\n## Item ordering (FULL, middle threshold) - join calls / runtime (s)\n")
    orders = ["AEUB_ASC", "AEUB_DESC", "INVESTMENT_ASC", "RANDOM"]
    md.append("| Order | " + " | ".join(datasets) + " |\n|---|" + "---|" * len(datasets))
    for o in orders:
        cells = []
        for d in datasets:
            r = O[(O.dataset == d) & (O.order == o)]
            cells.append(f"{int(r.joins.iloc[0]):,} / {r.runtime_s.iloc[0]:.2f}" if len(r) else "-")
        md.append(f"| {o} | " + " | ".join(cells) + " |")
    fig, ax = plt.subplots(1, 2, figsize=(11, 4))
    w = 0.2
    for k, o in enumerate(orders):
        for i, col in enumerate(["joins", "runtime_s"]):
            vals = [O[(O.dataset == d) & (O.order == o)][col].iloc[0] if len(O[(O.dataset == d) & (O.order == o)]) else 0 for d in datasets]
            ax[i].bar([x + (k - 1.5) * w for x in range(len(datasets))], vals, w, label=o)
    for i, t in enumerate(["join calls (log)", "runtime (s)"]):
        ax[i].set_xticks(range(len(datasets))); ax[i].set_xticklabels(datasets); ax[i].set_title(t)
    ax[0].set_yscale("log"); ax[0].legend(fontsize=8)
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "fig_order.png"), dpi=140); plt.close()

# ---------------- traversal + memory ----------------
T = g[g.axis == "traversal"]
if not T.empty:
    md.append("\n## DFS vs BFS (FULL, middle threshold) - join calls / runtime (s) / extra heap (MB)\n")
    md.append("| Traversal | " + " | ".join(datasets) + " |\n|---|" + "---|" * len(datasets))
    for t in ["DFS", "BFS"]:
        cells = []
        for d in datasets:
            r = T[(T.dataset == d) & (T.traversal == t)]
            cells.append(f"{int(r.joins.iloc[0]):,} / {r.runtime_s.iloc[0]:.2f} / {r.extra_heap_mb.iloc[0]:.1f}" if len(r) else "-")
        md.append(f"| {t} | " + " | ".join(cells) + " |")
md.append("\n## Extra peak heap (MB) at each dataset's lowest tested threshold\n")
md.append("| Variant | " + " | ".join(datasets) + " |\n|---|" + "---|" * len(datasets))
for v in VARS:
    cells = []
    for d in datasets:
        s = P[(P.dataset == d) & (P.variant == v)]
        cells.append(f"{s.sort_values('minae').extra_heap_mb.iloc[0]:.1f}" if len(s) else "-")
    md.append(f"| {v} | " + " | ".join(cells) + " |")

# ---------------- scalability ----------------
S = g[g.axis == "scalability"]
if not S.empty:
    sd = [d for d in datasets if d in set(S.dataset)]
    md.append("\n## Scalability (fraction of transactions used) - runtime (s) / extra heap (MB)\n")
    for d in sd:
        s = S[S.dataset == d]
        fr = sorted(set(s.frac))
        md.append(f"\n**{d}** (minae {s.minae.iloc[0]:g})\n")
        md.append("| Variant | " + " | ".join(f"{int(f*100)}%" for f in fr) + " |\n|---|" + "---|" * len(fr))
        for v in VARS:
            cells = []
            for f in fr:
                r = s[(s.variant == v) & (s.frac == f)]
                cells.append(f"{r.runtime_s.iloc[0]:.2f} / {r.extra_heap_mb.iloc[0]:.1f}" if len(r) else "-")
            if any(c != "-" for c in cells): md.append(f"| {v} | " + " | ".join(cells) + " |")
    fig, ax = plt.subplots(2, len(sd), figsize=(4.6 * len(sd), 7), squeeze=False)
    for j, d in enumerate(sd):
        for v in VARS:
            r = S[(S.dataset == d) & (S.variant == v)].sort_values("frac")
            if len(r):
                ax[0, j].plot(r.frac * 100, r.runtime_s, marker="o", label=v, color=COL[v])
                ax[1, j].plot(r.frac * 100, r.extra_heap_mb, marker="o", label=v, color=COL[v])
        ax[0, j].set_title(d); ax[0, j].set_ylabel("runtime (s)"); ax[1, j].set_ylabel("extra heap (MB)")
        for i in range(2): ax[i, j].set_xlabel("% of transactions"); ax[i, j].grid(alpha=.3)
        if j == 0: ax[0, j].legend(fontsize=7)
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "fig_scalability.png"), dpi=140); plt.close()

# ---------------- lazy EAECS (python run_all.py --axes lazy) ----------------
# FULL builds EAECS for every item pair in the second scan; FULL_LAZY computes EAECS(x,y) only when the search first asks for the pair;
# NO_EAECP does not use EAECS. FULL and FULL_LAZY must make exactly the same joins (same EAECS values); only the time spent on EAECS differs.
Z = g[g.axis == "lazy"]
if not Z.empty:
    md.append("\n## Lazy EAECS (middle columns: whole-run time; right columns: time spent on EAECS only)\n")
    md.append("`ratio` = FULL_LAZY / FULL (below 1: lazy is faster). `NO_EAECP/FULL` above 1 means EAECP pays off, below 1 means building and using EAECS costs more than it saves. "
              "`same joins` checks that FULL and FULL_LAZY made the same number of joins.\n")
    fig, ax = plt.subplots(1, len(datasets), figsize=(4.4 * len(datasets), 3.8), squeeze=False)
    for j, d in enumerate(datasets):
        s = Z[Z.dataset == d]
        if s.empty: continue
        md.append(f"\n### {d}\n")
        md.append("| minae | joins FULL | same joins | FULL (s) | FULL_LAZY (s) | NO_EAECP (s) | ratio | NO_EAECP/FULL | EAECS eager (ms) | EAECS lazy (ms) | pairs share |")
        md.append("|---|---|---|---|---|---|---|---|---|---|---|")
        xs, r_lazy, r_no = [], [], []
        for m in sorted(set(s.minae), reverse=True):
            f = s[(s.minae == m) & (s.variant == "FULL")]
            l = s[(s.minae == m) & (s.variant == "FULL_LAZY")]
            n = s[(s.minae == m) & (s.variant == "NO_EAECP")]
            if f.empty or l.empty:
                md.append(f"| {m:g} | - | - | - | - | - | - | - | - | - | - |"); continue
            f, l = f.iloc[0], l.iloc[0]
            nt = n.runtime_s.iloc[0] if len(n) else float("nan")
            same = "yes" if int(f.joins) == int(l.joins) and int(f.haei) == int(l.haei) else "**NO**"
            md.append(f"| {m:g} | {int(f.joins):,} | {same} | {f.runtime_s:.2f} | {l.runtime_s:.2f} | {nt:.2f} | {l.runtime_s / f.runtime_s:.2f} | "
                      f"{nt / f.runtime_s:.2f} | {f.eaecs_ms:.0f} | {l.eaecs_ms:.0f} | {l.eaecs_ms / f.eaecs_ms:.2f} |" if f.eaecs_ms > 0 else
                      f"| {m:g} | {int(f.joins):,} | {same} | {f.runtime_s:.2f} | {l.runtime_s:.2f} | {nt:.2f} | {l.runtime_s / f.runtime_s:.2f} | {nt / f.runtime_s:.2f} | - | - | - |")
            xs.append(m); r_lazy.append(l.runtime_s / f.runtime_s); r_no.append(nt / f.runtime_s)
        ax[0, j].plot(xs, r_lazy, marker="o", label="FULL_LAZY / FULL", color="#1b9e77")
        ax[0, j].plot(xs, r_no, marker="s", label="NO_EAECP / FULL", color="#7570b3")
        ax[0, j].axhline(1, color="grey", lw=.8); ax[0, j].grid(alpha=.3)
        minae_axis(ax[0, j], s.minae)
        ax[0, j].set_title(d); ax[0, j].set_ylabel("runtime ratio")
        if j == 0: ax[0, j].legend(fontsize=8)
    plt.tight_layout(); plt.savefig(os.path.join(OUT, "fig_lazy.png"), dpi=140); plt.close()

# ---------------- efficiency values (ae and e) of the mined itemsets ----------------
# ae(X) = average efficiency, e(X) = ae(X) * |X| = utility / investment. Every correct variant must find the SAME itemsets with the
# SAME values, so besides describing the values this section checks that all configurations agree (fingerprint of the itemset set +
# sum of ae). The values come from results.csv (runs made with the current code) and efficiency.csv (python run_all.py --axes efficiency).
AE_COLS = ["ae_min", "ae_max", "ae_mean", "ae_sum", "eff_max", "eff_mean", "size_mean", "size_max"]
base_dir = os.path.dirname(os.path.abspath(CSV))
frames = []
for name in ("results.csv", "efficiency.csv"):
    path = os.path.join(base_dir, name)
    if not os.path.exists(path):
        continue
    f = pd.read_csv(path, dtype=str)
    if "itemset_hash" not in f.columns:
        continue
    f = f[(f.status == "ok") & f.itemset_hash.notna() & (f.itemset_hash != "")].copy()
    for c in AE_COLS + ["minae", "frac", "haei_count"]:
        f[c] = pd.to_numeric(f[c], errors="coerce")
    frames.append(f)
md.append("\n## Efficiency values of the mined itemsets\n")
if not frames or sum(len(f) for f in frames) == 0:
    md.append("No efficiency data found. Run `python python/run_all.py --mode quick --axes efficiency` and then `python python/analyze.py` again.\n")
else:
    ae = pd.concat(frames, ignore_index=True)
    ae["config"] = ae.variant + "|" + ae.order + "|" + ae.traversal
    md.append("`ae(X)` is the average efficiency, `e(X) = ae(X)·|X|` the efficiency (utility / investment). \"Agree\" means that every configuration "
              "compared found exactly the same set of itemsets (same fingerprint) and the same total of ae(X) (relative difference below 1e-9).\n")
    rows, bad = [], []
    for (d_, m_, fr_), grp in ae.groupby(["dataset", "minae", "frac"]):
        n_hash = grp.itemset_hash.nunique()
        hi, lo = grp.ae_sum.max(), grp.ae_sum.min()
        spread = (hi - lo) / max(abs(hi), 1e-300)
        agree = (n_hash == 1) and (spread <= 1e-9)
        ref = grp.iloc[0]
        rows.append(dict(dataset=d_, minae=m_, frac=fr_, itemsets=int(ref.haei_count), ae_min=ref.ae_min, ae_mean=ref.ae_mean,
                         ae_max=ref.ae_max, eff_mean=ref.eff_mean, eff_max=ref.eff_max, size_mean=ref.size_mean,
                         size_max=ref.size_max, n_cfg=grp.config.nunique(), agree=agree, spread=spread))
        if not agree:
            bad.append((d_, m_, fr_, grp))
    R = pd.DataFrame(rows)
    full = R[R.frac == 1.0].sort_values(["dataset", "minae"], ascending=[True, False])
    g4 = lambda v: "%.4g" % v
    md.append("| Dataset | minae | Itemsets | ae min | ae mean | ae max | e(X) mean | e(X) max | Mean size (max) | Configurations compared | Agree? |")
    md.append("|---|---|---|---|---|---|---|---|---|---|---|")
    for _, r in full.iterrows():
        md.append(f"| {r.dataset} | {r.minae:g} | {r.itemsets:,} | {g4(r.ae_min)} | {g4(r.ae_mean)} | {g4(r.ae_max)} | {g4(r.eff_mean)} | {g4(r.eff_max)} | "
                  f"{r.size_mean:.2f} ({int(r.size_max)}) | {r.n_cfg} | {'yes' if r.agree else '**NO**'} |")
    part = R[R.frac < 1.0]
    md.append("")
    md.append(f"Agreement checked in {len(R)} groups (dataset, threshold, fraction of the data): {int(R.agree.sum())} agree, {int((~R.agree).sum())} do not."
              + (f" Of these, {len(part)} are partial-data scalability groups." if len(part) else ""))
    for d_, m_, fr_, grp in bad:
        md.append(f"\n**MISMATCH** in {d_}, minae {m_:g}, fraction {fr_:g}: " +
                  "; ".join(f"{h[-6:]}: " + ", ".join(sorted(set(x.config for x in gg.itertuples()))) for h, gg in grp.groupby("itemset_hash")))
    # figure: how the efficiency level of the results changes with the threshold
    if len(full):
        ds_ae = [d for d in datasets if d in set(full.dataset)]
        from matplotlib.ticker import ScalarFormatter, NullFormatter
        fig, ax = plt.subplots(2, len(ds_ae), figsize=(4.6 * len(ds_ae), 7), squeeze=False)
        for j, d_ in enumerate(ds_ae):
            r = full[full.dataset == d_].sort_values("minae")
            for i, (a, b, la, lb, yl) in enumerate([("ae_mean", "ae_max", "mean", "max", "average efficiency ae(X)"),
                                                     ("eff_mean", "eff_max", "mean", "max", "efficiency e(X) = ae(X)·|X|")]):
                ax[i, j].plot(r.minae, r[a], marker="o", label=la, color="#1b9e77")
                ax[i, j].plot(r.minae, r[b], marker="s", label=lb, color="#d95f02")
                bad_r = r[~r.agree]
                if len(bad_r):
                    ax[i, j].scatter(bad_r.minae, bad_r[b], s=160, facecolors="none", edgecolors="red", label="variants disagree")
                ax[i, j].set_xscale("log"); ax[i, j].set_yscale("log"); ax[i, j].invert_xaxis(); ax[i, j].grid(alpha=.3)
                ax[i, j].set_xticks(list(r.minae)); ax[i, j].xaxis.set_major_formatter(ScalarFormatter()); ax[i, j].xaxis.set_minor_formatter(NullFormatter())
                ax[i, j].set_xlabel("minae"); ax[i, j].set_ylabel(yl); ax[i, j].set_title(d_ if i == 0 else "")
            if j == 0: ax[0, j].legend(fontsize=8)
        plt.tight_layout(); plt.savefig(os.path.join(OUT, "fig_efficiency.png"), dpi=140); plt.close()

open(os.path.join(OUT, "tables.md"), "w").write("\n".join(md) + "\n")
print("wrote", os.path.join(OUT, "tables.md"), "and figures in", OUT)
