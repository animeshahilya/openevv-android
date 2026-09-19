#!/system/bin/sh
# No-crash gate: every line of unicode-test.txt through the engine.
# Usage: sh unicode-gate.sh [langid]  (default 0x10000)
# Prints FAIL <lineno> for nonzero exits; summary at end.
LANGID=${1:-0x10000}
cd /data/local/tmp/eqtest || exit 99
i=0
skip=0
fail=0
while IFS= read -r line || [ -n "$line" ]; do
  i=$((i + 1))
  if [ -z "$line" ]; then
    skip=$((skip + 1))
    continue
  fi
  printf '%s' "$line" > u_line.txt
  if ! ./eloquick -L "$LANGID" -f u_line.txt -o u.wav 2>u_err.txt; then
    fail=$((fail + 1))
    echo "FAIL $i: $line"
  fi
done < unicode-test.txt
echo "unicode-gate[$LANGID]: lines=$i skipped_empty=$skip fails=$fail"
