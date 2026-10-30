#!/bin/bash
# Score hand-picked candidates on rounds 1-3 (no search), one report dir each.
cd "$(dirname "$0")" 2>/dev/null
i=0
while read -r name args; do
  [ -z "$name" ] && continue
  i=$((i+1))
  out=$HOME/sortes-review/fable/cands/$name
  mkdir -p "$out"
  JAVA_OPTS="-Xmx768m -Djava.awt.headless=true" nice /tmp/fable-zoom-eval/bin/zoom-eval fit --trials=0 --variants=furniture+marks \
    --beta=0.1 --gamma=0.1 --delta2=0.02 --allowance=0.01 --zoomIgnoredPages=0 --autoMaxPageCut=1 --autoMaxLost=0.005 --blockGap=2 \
    $args --out="$out" > "$out/log" 2>&1
  score=$(grep -o 'Given weights.*' "$out/report.md")
  agree=$(grep -o '\*\*all\*\* | | \*\*[0-9]* of [0-9]*\*\*' "$out/report.md")
  echo "$name: $score | $agree"
done <<'LIST'
a_z1_m02 --kappa=0.2 --zeta=1 --autoMargin=0.02 --epsilon=0
b_z1_m0 --kappa=0.2 --zeta=1 --autoMargin=0 --epsilon=0
c_z1_m01 --kappa=0.2 --zeta=1 --autoMargin=0.01 --epsilon=0
d_z05_m01 --kappa=0.2 --zeta=0.5 --autoMargin=0.01 --epsilon=0
e_z05_m0 --kappa=0.2 --zeta=0.5 --autoMargin=0 --epsilon=0
f_z2_m0 --kappa=0.2 --zeta=2 --autoMargin=0 --epsilon=0
g_k01_z05_m01 --kappa=0.1 --zeta=0.5 --autoMargin=0.01 --epsilon=0
h_eps300 --kappa=0.2 --zeta=0.5 --autoMargin=0.01 --epsilon=300
i_budget_k02 --kappa=0.2 --zeta=0 --autoMargin=0.02 --epsilon=0
j_budget_k02_eps --kappa=0.2 --zeta=0 --autoMargin=0.02 --epsilon=300
LIST
