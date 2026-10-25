# Ablation results (medians over repetitions)

Cells are `runtime (s) / join calls`; for HAEIMiner the second number is database scans. `timeout` = the run did not finish within the time limit.


## Stand-alone contribution of each pruning strategy (join calls, % change vs NEITHER)

| Dataset | minae | NEITHER | MAEP only (NO_EAECP) | EAECP only (NO_MAEP) | Both (FULL) |
|---|---|---|---|---|---|

## Extra peak heap (MB) at each dataset's lowest tested threshold

| Variant | chess | mushroom | foodmart |
|---|---|---|---|
| FULL | - | - | - |
| NO_EAECP | - | - | - |
| NO_MAEP | - | - | - |
| NEITHER | - | - | - |
| HAEIMiner_mem | - | - | - |
| HAEIMiner_disk | - | - | - |

## Lazy EAECS (middle columns: whole-run time; right columns: time spent on EAECS only)

`ratio` = FULL_LAZY / FULL (below 1: lazy is faster). `NO_EAECP/FULL` above 1 means EAECP pays off, below 1 means building and using EAECS costs more than it saves. `same joins` checks that FULL and FULL_LAZY made the same number of joins.


### chess

| minae | joins FULL | same joins | FULL (s) | FULL_LAZY (s) | NO_EAECP (s) | ratio | NO_EAECP/FULL | EAECS eager (ms) | EAECS lazy (ms) | pairs share |
|---|---|---|---|---|---|---|---|---|---|---|
| 50 | 277 | yes | 1.12 | 0.80 | 0.82 | 0.71 | 0.73 | 73 | 43 | 0.58 |
| 20 | 880 | yes | 1.31 | 1.30 | 1.12 | 0.99 | 0.85 | 171 | 102 | 0.60 |
| 10 | 2,489 | yes | 1.76 | 1.69 | 1.56 | 0.96 | 0.89 | 342 | 176 | 0.51 |
| 5 | 10,301 | yes | 4.76 | 4.62 | 4.19 | 0.97 | 0.88 | 530 | 214 | 0.40 |

### mushroom

| minae | joins FULL | same joins | FULL (s) | FULL_LAZY (s) | NO_EAECP (s) | ratio | NO_EAECP/FULL | EAECS eager (ms) | EAECS lazy (ms) | pairs share |
|---|---|---|---|---|---|---|---|---|---|---|
| 10 | 249 | yes | 1.45 | 1.33 | 1.54 | 0.92 | 1.06 | 412 | 292 | 0.71 |
| 5 | 1,141 | yes | 2.55 | 2.15 | 2.18 | 0.84 | 0.85 | 657 | 475 | 0.72 |
| 3 | 3,852 | yes | 3.41 | 2.65 | 3.10 | 0.78 | 0.91 | 735 | 365 | 0.50 |
| 2 | 10,693 | yes | 5.29 | 4.75 | 5.56 | 0.90 | 1.05 | 712 | 340 | 0.48 |
| 1 | 54,423 | yes | 13.97 | 13.92 | 14.22 | 1.00 | 1.02 | 787 | 391 | 0.50 |

### foodmart

| minae | joins FULL | same joins | FULL (s) | FULL_LAZY (s) | NO_EAECP (s) | ratio | NO_EAECP/FULL | EAECS eager (ms) | EAECS lazy (ms) | pairs share |
|---|---|---|---|---|---|---|---|---|---|---|
| 5 | 688 | yes | 0.36 | 0.70 | 0.49 | 1.92 | 1.35 | 22 | 222 | 10.15 |
| 2 | 1,757 | yes | 0.42 | 0.59 | 0.55 | 1.42 | 1.31 | 31 | 159 | 5.10 |
| 1 | 2,203 | yes | 0.63 | 1.27 | 0.71 | 2.02 | 1.13 | 48 | 502 | 10.36 |
| 0.5 | 2,944 | yes | 0.74 | 1.58 | 1.39 | 2.14 | 1.89 | 88 | 889 | 10.12 |
| 0.2 | 6,041 | yes | 0.78 | 2.34 | 1.29 | 2.99 | 1.65 | 106 | 1348 | 12.77 |

## Efficiency values of the mined itemsets

`ae(X)` is the average efficiency, `e(X) = ae(X)·|X|` the efficiency (utility / investment). "Agree" means that every configuration compared found exactly the same set of itemsets (same fingerprint) and the same total of ae(X) (relative difference below 1e-9).

| Dataset | minae | Itemsets | ae min | ae mean | ae max | e(X) mean | e(X) max | Mean size (max) | Configurations compared | Agree? |
|---|---|---|---|---|---|---|---|---|---|---|
| chess | 50 | 158 | 50.17 | 136.4 | 908.8 | 336.8 | 908.8 | 3.01 (6) | 3 | yes |
| chess | 20 | 474 | 20.01 | 65.37 | 908.8 | 189.1 | 908.8 | 3.61 (7) | 3 | yes |
| chess | 10 | 1,155 | 10.01 | 34.79 | 908.8 | 112.4 | 908.8 | 4.07 (7) | 3 | yes |
| chess | 5 | 3,248 | 5 | 16.66 | 908.8 | 60.09 | 908.8 | 4.48 (8) | 3 | yes |
| foodmart | 5 | 592 | 5 | 46.4 | 1038 | 49.24 | 1038 | 1.42 (3) | 3 | yes |
| foodmart | 2 | 1,923 | 2 | 16.48 | 1038 | 19.73 | 1038 | 1.90 (4) | 3 | yes |
| foodmart | 1 | 2,629 | 1 | 12.45 | 1038 | 15.28 | 1038 | 1.95 (5) | 3 | yes |
| foodmart | 0.5 | 3,387 | 0.5 | 9.821 | 1038 | 12.13 | 1038 | 1.90 (5) | 3 | yes |
| foodmart | 0.2 | 5,433 | 0.2001 | 6.24 | 1038 | 7.807 | 1038 | 2.00 (5) | 3 | yes |
| mushroom | 10 | 121 | 10.24 | 137.2 | 3880 | 209 | 3880 | 2.29 (4) | 3 | yes |
| mushroom | 5 | 286 | 5.014 | 61.92 | 3880 | 98.32 | 3880 | 2.44 (4) | 3 | yes |
| mushroom | 3 | 581 | 3 | 32.4 | 3880 | 53.9 | 3880 | 2.66 (5) | 3 | yes |
| mushroom | 2 | 1,158 | 2.001 | 17.44 | 3880 | 30.86 | 3880 | 2.95 (6) | 3 | yes |
| mushroom | 1 | 5,494 | 1 | 4.706 | 3880 | 10.59 | 3880 | 3.80 (7) | 3 | yes |

Agreement checked in 14 groups (dataset, threshold, fraction of the data): 14 agree, 0 do not.
