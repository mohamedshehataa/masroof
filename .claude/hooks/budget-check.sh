#!/usr/bin/env bash
# UserPromptSubmit: warn (never block) when cumulative AI spend in docs/SDLC_METRICS.md passes
# the budget (SDLC_AI_BUDGET_USD from .env, else .env.example, else 300). stdout is added as context.
HOOK_NAME="budget-check"
# shellcheck source=lib.sh
. "${BASH_SOURCE[0]%/*}/lib.sh"
read_input
cd "$PROJECT_DIR" 2>/dev/null || exit 0
metrics="${MASROOF_METRICS_FILE:-docs/SDLC_METRICS.md}"
[ -f "$metrics" ] || exit 0
spend="$(grep -E '^\|[[:space:]]*AI spend \(USD\)' "$metrics" | head -1 | awk -F'|' '{gsub(/[[:space:]]/,"",$3); print $3}')"
[ -z "$spend" ] && exit 0
budget=""
for f in .env .env.example; do
  [ -f "$f" ] && budget="$(grep -E '^SDLC_AI_BUDGET_USD=' "$f" | head -1 | cut -d= -f2 | sed -E 's/[[:space:]]*#.*$//' | tr -d '[:space:]"')"
  [ -n "$budget" ] && break
done
budget="${MASROOF_AI_BUDGET_USD:-${budget:-300}}"
# "1,234.50", "$350", "350 USD", "EGP 350" and Arabic-Indic digits are how people write money: awk would
# read them as 1, 0 or nothing. Arabic-Indic/Persian digits and separators are built at runtime (ASCII source).
for i in 0 1 2 3 4 5 6 7 8 9; do
  printf -v ch "\\xd9\\xa$i"; spend="${spend//"$ch"/$i}"; budget="${budget//"$ch"/$i}"
  printf -v ch "\\xdb\\xb$i"; spend="${spend//"$ch"/$i}"; budget="${budget//"$ch"/$i}"
done
printf -v ch '\xd9\xab'; spend="${spend//"$ch"/.}"   # Arabic decimal separator
printf -v ch '\xd9\xac'; spend="${spend//"$ch"/,}"   # Arabic thousands separator
spend="${spend//[,\$[:space:][:alpha:]]/}"; budget="${budget//[,\$[:space:][:alpha:]]/}"
num_re='^[0-9]+([.][0-9]+)?$'
[[ "$spend" =~ $num_re ]] || exit 0
[[ "$budget" =~ $num_re ]] || budget=300
awk -v s="$spend" -v b="$budget" 'BEGIN{ if (s+0 > b+0) exit 0; else if (s+0 > 0.8*(b+0)) exit 3; else exit 1 }'
case $? in
  0) echo "WARNING: SDLC AI BUDGET EXCEEDED: cumulative spend \$$spend > budget \$$budget (SDLC_AI_BUDGET_USD). This is an S16 human checkpoint: add an entry to docs/ESCALATIONS.md and prefer cheaper models/fewer loops until the owner raises the budget." ;;
  3) echo "INFO: SDLC AI spend \$$spend is above 80% of the \$$budget budget." ;;
esac
exit 0
