# MAEI ablation study

Ablation study and benchmark of **MAEI**, *Mining high average-efficiency itemsets based on a compact list structure* (G. Li, X. Zhang, T. Shang, and S. Chen, *Engineering Applications of Artificial Intelligence*, 166 (2026), Article 113639). This project adapts the authors' implementation with switches for controlled ablation experiments; it is not an independent reimplementation of MAEI.

Original implementation: https://github.com/Nicco-zxw/MAEI

Authors: **Tran Nhat Huy (523H0142)** and **Dinh Bui Khanh Huy (523H0136)**.

## Quick start (for the reviewer)

1. Install a **JDK** (not only a JRE; JDK 21 or newer) and **Python 3.8+**, then `python -m pip install pandas matplotlib`. Check with `java -version`, `javac -version`, `python --version`.
2. Open a terminal in this folder and run (about 3 minutes, writes only to `results_smoke/`, leaves the real results untouched):

```bash
python python/validate.py                                  # same output as the authors' original code?  (expect all checks to pass)
python python/run_all.py --mode smoke --out results_smoke  # tiny run of every experiment
python python/analyze.py results_smoke/results.csv         # tables.md and fig_*.png appear in results_smoke/
```

3. To build the tables and figures of the run we report: `python python/analyze.py results_1800/results.csv` (writes `tables.md` and `fig_*.png` next to the CSV; see section 7).

On Linux/macOS use `python3`. Always give `--out` a new folder for test runs: without `--out` results are appended to `results/`.
If you copy this folder instead of cloning it, delete the `out/` folder first (compiled classes from another Java version cause `UnsupportedClassVersionError`; it is recompiled automatically).

### Status of the experiments

| Experiment | State |
|---|---|
| pruning, order, traversal, scalability | run with 3 repetitions (Accidents: 1), 1,800 s limit; 4 Accidents runs at `minae = 100` (NO_MAEP, NEITHER, HAEIMiner_mem, HAEIMiner_disk) are still missing |
| efficiency (same itemsets in every variant) | run, without Accidents |
| lazy EAECS | implemented and validated (same joins and itemsets as the original); full run on our machine pending |

(Update this table when the remaining runs finish.)

## 1. Requirements

- JDK 25 (also tested with JDK 21); both `java -version` and `javac -version` must work.
- Python 3.8 or newer.
- `pandas` and `matplotlib` for analysis: `python -m pip install pandas matplotlib`.
- Windows: use `python` or `py`; Linux/macOS: use `python3`.

The experiment launcher itself uses only the Python standard library. For long runs, connect the computer to power, close other demanding programs, and do not run multiple benchmark suites in parallel.

## 2. Running the experiments

Run commands from the repository root.

```bash
# Validate selected outputs against reference results
python python/validate.py

# Short smoke test
python python/run_all.py --mode smoke

# Quick experiment suite
python python/run_all.py --mode quick

# Full suite, 8 GiB heap, 1,800-second timeout per run
python python/run_all.py --mode full --heap 8192 --timeout 1800 --axes all --out results_1800

# Analyze the new run
python python/analyze.py results_1800/results.csv
```

Use a new output directory such as `results_1800` to keep new results separate from the bundled, older results. `--timeout 1800` is a limit of **1,800 seconds for each experiment run**, not for the entire suite. Running every axis may take many hours.

The runner appends one row per finished run to `results.csv` in the output directory. **Re-running the same command resumes**: runs that already have a row (including timed-out ones) are skipped, so `Ctrl+C` is safe. To retry a timed-out run, delete its row from the CSV first; for a completely clean run use a new `--out` folder.

### Command-line options

| Option | Purpose |
|---|---|
| `--mode smoke` | Short smoke-test configuration. |
| `--mode quick` | Reduced experiment configuration. |
| `--mode full` | Full experiment configuration. |
| `--heap 8192` | Set the JVM maximum heap to 8192 MB (8 GiB) for each run. |
| `--timeout 1800` | Set the timeout to 1,800 seconds per experiment run. |
| `--reps N` | Set the number of repetitions, where supported by the Java experiment drivers. |
| `--datasets chess mushroom` | Restrict experiments to the named datasets. |
| `--axes pruning order` | Select experiment axes. Multiple axis names can be supplied. |
| `--axes all` | Run all six experiment axes, including `efficiency` and `lazy`. |
| `--out DIR` | Set the output directory. |
| `--java PATH` | Path to the Java executable; the launcher derives `javac` from the same directory when possible. |

The actual accepted axis names are `pruning`, `order`, `traversal`, `scalability`, `efficiency`, and `lazy`. The default axes are `pruning`, `order`, `traversal`, and `scalability`; `efficiency` and `lazy` are opt-in unless `--axes all` is used.

### One experiment or one mining run

```bash
# Run one experiment driver directly
java -cp out ExpPruning --mode quick

# Run one mining configuration directly
java -cp out RunOne -algo MAEI -data data/chess_DBUtility.txt -stock data/chess_stock.txt -minae 20
```

## 3. Experiment axes and configurations

An **experiment axis** selects a controlled comparison. It is different from a runtime option such as `--timeout` or `--heap`.

| Axis / Java driver | What changes | Configurations |
|---|---|---|
| `pruning` / `ExpPruning` | Whether MAEP and EAECP are enabled | `FULL`, `NO_EAECP`, `NO_MAEP`, `NEITHER`, `HAEIMiner_mem`, `HAEIMiner_disk` |
| `order` / `ExpOrder` | Item ordering | `AEUB_ASC`, `AEUB_DESC`, `INVESTMENT_ASC`, `RANDOM` |
| `traversal` / `ExpTraversal` | Search traversal | DFS and BFS |
| `scalability` / `ExpScalability` | Fraction of transactions used | 20%–100%; full-mode experiment |
| `efficiency` / `ExpEfficiency` | Efficiency values of mined itemsets | Compares `ae(X)` and `e(X) = ae(X) · |X|`; one run per variant, output to `efficiency.csv` |
| `lazy` / `ExpLazy` | EAECS construction strategy | `FULL`, `FULL_LAZY`, `NO_EAECP` |

### What the configurations mean

- **`FULL`**: MAEP and EAECP are enabled.
- **`NO_EAECP`**: EAECP is disabled, so EAECS is not built for that pruning step.
- **`NO_MAEP`**: MAEP is disabled and the implementation uses the looser `aeub` bound instead of `mae`.
- **`NEITHER`**: both MAEP and EAECP are disabled.
- **`HAEIMiner_mem`**: HAEIMiner baseline keeps the database in memory.
- **`HAEIMiner_disk`**: HAEIMiner baseline re-reads the file for each scan.
- **`AEUB_ASC`**: ascending `aeub` order, described in this project as the order used by the paper's MAEI.
- **`AEUB_DESC`**: descending `aeub` order.
- **`INVESTMENT_ASC`**: ascending investment order, associated in this project with MAEI-v2.
- **`RANDOM`**: seeded random item order.
- **`FULL_LAZY`**: computes an EAECS pair value when first requested, then caches it, rather than precomputing every pair during the second scan. This is lazy EAECS computation, **not** lazy construction of individual AE-Lists.

For the lazy-EAECS comparison, `FULL` and `FULL_LAZY` are expected to produce the same joins and itemsets; `eaecs_ms` records EAECS computation time. Validate this expectation against the results rather than treating it as automatically guaranteed.

## 4. Measurements and interpreting results

`run_all.py` compiles the Java sources when needed, writes `system_info.txt`, and starts the selected experiment drivers one after another. Each experiment run uses the configured heap; experiment drivers write result records to CSV.

- **`join_count`** is deterministic and useful for comparing MAEI pruning variants. For HAEIMiner, the corresponding count represents database scans, not MAEI join operations; these counts are not directly comparable.
- **Runtime** for short runs includes JVM startup and warm-up. Prefer medians and longer runs when comparing performance.
- **`minae` thresholds** should not be compared numerically across different datasets as if they represented the same workload. Consider itemset counts and dataset characteristics.
- **Peak heap** is sampled every 5 ms and may include memory the JVM has not collected.
- **Efficiency analysis** checks whether variants and baselines found the same itemsets and values using a fingerprint and the sum of `ae`. It generates `fig_efficiency.png`; timing columns in `efficiency.csv` are blank because this experiment compares output values rather than runtime.
- Older `results.csv` files may not contain the `eaecs_ms` field if they were created before lazy-EAECS timing was added.

## 5. Project layout

```text
src/       AlgoHAEI (authors' MAEI plus switches), Element, UtilityList,
           AlgoHAEIMiner, RunOne, ExperimentBase, and Exp*.java
python/    run_all.py, analyze.py, validate.py, generate_stock.py
data/      chess, mushroom, foodmart, accidents, and data/README.md
results/   results.csv, efficiency.csv, system_info.txt, validation.txt,
           tables.md, and generated figures
AlgoHAEI.functional.diff
           Summary of changes to the authors' AlgoHAEI class
```

Place the report file in the repository root. The exact files present in a results directory depend on which experiments and analysis steps have been run.

## 6. Implementation changes

The adapted `AlgoHAEI.java` is intended to preserve the original implementation's results under default settings. `validate.py` compares selected cases against reference values recorded from the unmodified program (commit `e284fc7`), including itemsets, `ae` values, and join counts. Passing these checks supports agreement on those cases; it does not prove correctness for every dataset and configuration. `Element` and `UtilityList` are based on the authors' helper classes, with documentation added.

| Change | Purpose |
|---|---|
| `useMAEP`, `useEAECP`, `useBFS`, `orderStrategy`, `maxTransactions` | Add experiment controls while keeping original behavior as the default. |
| `useMAEP = false` | Uses `calAeub(X)` instead of `calMae(X)` to disable MAEP with a weaker bound. |
| `useEAECP = false` | Skips EAECS construction for the EAECP ablation. |
| `buildOrderKeys()` / `compareByOrder()` | Implements order keys and item-ID tie-breaking. |
| `HAEIMineBFS()` | Implements breadth-first traversal. |
| `maxTransactions` in both scans | Supports transaction-fraction scalability experiments. |
| `lazyEAECS`, `lazyAllows()`, `lazyPairValue()`, `eaecsAllows()` | Computes EAECS pair values on demand from AE-Lists and caches them. |
| Separate EAECS accumulation loop and `eaecsNanos` | Measures EAECS computation time and reports `eaecs_ms`, without intending to change computed values. |

The item-order tie-break is important because ordering is applied in three separate sorts. Equal keys must be resolved consistently to avoid invalid ordering behavior. In Foodmart, 86 of 1,559 items share an investment value; before the tie-break fix, one itemset with efficiency 6.99 was lost. The authors' `aeub` order does not tie in the same way.

## 7. Data and result files

See `data/README.md` for dataset sources and how stock files were generated. `*_DBUtility.txt` uses the SPMF utility-file format; stock files store item investment values `inv(i)`, following the authors' code.

The bundled results are from an earlier run on Windows 10 with an Intel Core i5-6400 (4 cores), 16 GB RAM, OpenJDK 21 (Temurin), an 8 GB heap, three repetitions (one for Accidents), and a 900-second per-run limit. Refer to `results/system_info.txt` for the recorded environment. `results_1800/` holds the newer run with a 1,800-second limit (use its `system_info.txt`, tables and figures when reporting results); `results/` is the earlier 900-second run. Keep them separate.

## 8. Credits and limitations

`AlgoHAEI`, `Element`, and `UtilityList` are based on the original authors' work. `AlgoHAEIMiner` is a reimplementation from the published description of HAEIMiner (Yildirim, 2024); the MAEI authors' repository does not include that baseline implementation or the MAEI-v1/v2/v3 variants, so those baselines cannot be validated against the authors' code. Stock files follow the authors' generation method but do not use their unseeded random values, so absolute results may differ from the paper.

Runtime depends on hardware, JVM warm-up, and operating-system scheduling. Peak heap is sampled and may include uncollected garbage. HAEIMiner's `join_count` represents database scans, not MAEI join calls, so the counts are not directly comparable.
