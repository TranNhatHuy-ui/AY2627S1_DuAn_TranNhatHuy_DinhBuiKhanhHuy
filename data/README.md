# Datasets

Every dataset has two files: `<name>_DBUtility.txt` (one transaction per line, `items:transactionUtility:utilities`, the SPMF utility format)
and `<name>_stock.txt` (one `item investment` pair per line). The authors' code reads the stock value directly as the item's TOTAL
investment inv(i); it is not multiplied by any quantity.

| Dataset | Transactions | Items | Avg length | Density | Source of the utility file | Stock file |
|---|---|---|---|---|---|---|
| chess | 3,196 | 75 | 37.0 | 49.33% | SPMF chess utility file | generated with the authors' GenInvest method |
| mushroom | 8,124 | 119 | 23.0 | 19.33% | SPMF `mushroom_utility_spmf.txt` (synthetic utilities) | generated, seed 42 (`python/generate_stock.py`) |
| foodmart | 4,141 | 1,559 | 4.42 | 0.28% | SPMF `foodmart.txt` (real utilities) | generated, seed 42 |
| accidents | 340,183 | 468 | 33.8 | 7.22% | copied unchanged from the authors' repository | copied unchanged from the authors' repository |

GenInvest method: `round(10000 + N(0,1) * 10000)`, redrawn as `round(100 + N(0,1) * 25)` until positive. The authors' generator is unseeded, so
our stock files reproduce the METHOD, not the exact numbers of the paper's experiments; absolute figures will not match the paper's.
Every transaction's utility total was checked against its stated transactionUtility. `accidents_DBUtility.txt` is about 66 MB: GitHub warns above
50 MB but accepts files up to 100 MB.
