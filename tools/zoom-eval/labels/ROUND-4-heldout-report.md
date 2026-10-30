# Crop model fit

6 labelled papers, 2 weight sets tried. Score: lower is better (see SPEC.md).

| paper | targets | target scale |
| --- | --- | --- |
| 1806.07572 | 8 (1 moved, 7 good: stay put) | 0.805 (weight 0.5) |
| 1902.10186 | 8 (8 good: stay put) | 0.845 (weight 0.5) |
| 1905.00537 | 7 (1 moved, 6 good: stay put) | 0.810 (weight 0.5) |
| 2009.03300 | 4 (4 moved) | 0.840 (weight 0.5) |
| maldacena-9711200 | 6 (6 good: stay put) | 0.795 (weight 0.5) |
| perelman-0211159 | 8 (6 good: stay put, 2 moved) | 0.755 (weight 1.0) |

## Best weights

Score 29.32 with the defaults, **26.65** with furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000`

Given weights (furniture+marks, `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000`): 26.65

| paper | scale now → fitted (target) | zoom units | page units | total |
| --- | --- | --- | --- | --- |
| 1806.07572 | 0.815 → 0.805 (0.805) | 1.00 → 0.00 | 1.29 → 4.71 | 2.29 → **4.71** |
| 1902.10186 | 0.850 → 0.845 (0.845) | 0.50 → 0.00 | 0.00 → 0.00 | 0.50 → **0.00** |
| 1905.00537 | 0.815 → 0.810 (0.810) | 0.50 → 0.00 | 2.44 → 4.42 | 2.94 → **4.42** |
| 2009.03300 | 0.880 → 0.840 (0.840) | 4.00 → 0.00 | 2.05 → 0.50 | 6.05 → **0.50** |
| maldacena-9711200 | 0.800 → 0.795 (0.795) | 0.50 → 0.00 | 0.00 → 0.00 | 0.50 → **0.00** |
| perelman-0211159 | 0.685 → 0.685 (0.755) | 14.00 → 14.00 | 3.03 → 3.03 | 17.03 → **17.03** |

## Held out

Each paper scored with the best weights fitted on the others: does the fit carry over?

| held-out paper | defaults | fitted without it | weights |
| --- | --- | --- | --- |
| 1806.07572 | 2.29 | 4.71 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| 1902.10186 | 0.50 | 0.00 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| 1905.00537 | 2.94 | 4.42 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| 2009.03300 | 6.05 | 6.05 | reader: `(defaults)` |
| maldacena-9711200 | 0.50 | 0.00 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| perelman-0211159 | 17.03 | 17.03 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| **all** | 29.32 | **32.21** | |

## Agreement in principle (furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000`)

A page agrees if its window centre is within 8 pt of the owner's (or of the crop they called good) and it cuts at most 0.25% of the page's ink that the owner's crop keeps. A paper's zoom agrees within 0.015 of the owner's scale.

| paper | zoom: model (owner) | pages agreeing | pages that don't |
| --- | --- | --- | --- |
| 1806.07572 | 0.805 (0.805) agrees | 7 of 8 | p1: 20 pt cuts 2.70% |
| 1902.10186 | 0.845 (0.845) agrees | 8 of 8 |  |
| 1905.00537 | 0.810 (0.810) agrees | 6 of 7 | p1: 20 pt cuts 2.42% |
| 2009.03300 | 0.840 (0.840) agrees | 4 of 4 |  |
| maldacena-9711200 | 0.795 (0.795) agrees | 6 of 6 |  |
| perelman-0211159 | 0.685 (0.755) **off** | 6 of 8 | p15: 10 pt cuts 0.38%, p16: 11 pt cuts 0.54% |
| **all** | | **37 of 41** | |

## Pages that change

| paper | page | target | centre error (pt) | ideal ink cut (%) | units |
| --- | --- | --- | --- | --- | --- |
| 1806.07572 | 1 | moved (100%) | 12.1 → 20.0 | 0.04 → 2.70 | 1.25 → 4.71 (worse) |
| 1905.00537 | 1 | moved (100%) | 13.6 → 20.0 | 1.08 → 2.42 | 2.44 → 4.42 (worse) |
| 2009.03300 | 2 | moved (25%) | 20.5 → 5.0 | 0.00 → 0.00 | 0.51 → **0.12** (better) |
| 2009.03300 | 3 | moved (25%) | 20.5 → 5.0 | 0.00 → 0.00 | 0.51 → **0.12** (better) |
| 2009.03300 | 4 | moved (25%) | 20.5 → 5.0 | 0.00 → 0.00 | 0.51 → **0.12** (better) |
| 2009.03300 | 5 | moved (25%) | 20.5 → 5.0 | 0.00 → 0.00 | 0.51 → **0.12** (better) |

## Where the defaults lose

| paper | page | target | centre error (pt) | ideal ink cut (%) | units |
| --- | --- | --- | --- | --- | --- |
| 1806.07572 | 1 | moved (100%) | 12.1 | 0.04 | 1.25 |
| 1806.07572 | 4 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 5 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 6 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 8 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 11 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 15 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1806.07572 | 19 | good: stay put | 8.1 | 0.00 | 0.01 |
| 1902.10186 | 1 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 2 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 3 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 4 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 5 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 10 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 11 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1902.10186 | 13 | good: stay put | 1.6 | 0.00 | 0.00 |
| 1905.00537 | 1 | moved (100%) | 13.6 | 1.08 | 2.44 |
| 1905.00537 | 6 | good: stay put | 6.4 | 0.00 | 0.00 |
| 1905.00537 | 7 | good: stay put | 6.4 | 0.00 | 0.00 |
| 1905.00537 | 8 | good: stay put | 6.4 | 0.00 | 0.00 |
| 1905.00537 | 9 | good: stay put | 6.4 | 0.00 | 0.00 |
| 1905.00537 | 10 | good: stay put | 6.4 | 0.00 | 0.00 |
| 1905.00537 | 16 | good: stay put | 6.4 | 0.00 | 0.00 |
| 2009.03300 | 2 | moved (25%) | 20.5 | 0.00 | 0.51 |
| 2009.03300 | 3 | moved (25%) | 20.5 | 0.00 | 0.51 |
| 2009.03300 | 4 | moved (25%) | 20.5 | 0.00 | 0.51 |
| 2009.03300 | 5 | moved (25%) | 20.5 | 0.00 | 0.51 |
| maldacena-9711200 | 2 | good: stay put | 2.3 | 0.00 | 0.00 |
| maldacena-9711200 | 3 | good: stay put | 2.3 | 0.00 | 0.00 |
| maldacena-9711200 | 4 | good: stay put | 2.3 | 0.00 | 0.00 |
| maldacena-9711200 | 5 | good: stay put | 2.3 | 0.00 | 0.00 |
| maldacena-9711200 | 7 | good: stay put | 2.3 | 0.00 | 0.00 |
| maldacena-9711200 | 8 | good: stay put | 2.3 | 0.00 | 0.00 |
| perelman-0211159 | 16 | moved (100%) | 11.2 | 0.54 | 1.66 |
| perelman-0211159 | 15 | moved (100%) | 10.0 | 0.38 | 1.38 |
| perelman-0211159 | 2 | good: stay put | 0.0 | 0.00 | 0.00 |
| perelman-0211159 | 3 | good: stay put | 0.0 | 0.00 | 0.00 |
| perelman-0211159 | 4 | good: stay put | 0.0 | 0.00 | 0.00 |
| perelman-0211159 | 6 | good: stay put | 0.0 | 0.00 | 0.00 |
| perelman-0211159 | 7 | good: stay put | 0.0 | 0.00 | 0.00 |
| perelman-0211159 | 13 | good: stay put | 0.0 | 0.00 | 0.00 |

## Top 10 weight sets

| score | weights |
| --- | --- |
| 26.65 | furniture+marks: `autoMargin=0.01000, beta=0.5000, delta=0.005000, epsilon=0.000, gamma=0.1000, kappa=0.2000, zeta=0.5000` |
| 29.32 | reader: `(defaults)` |
