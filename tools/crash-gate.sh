#!/system/bin/sh
# Crash-word battery: each line through enus+dede, require rc=0, report sizes.
# Suspiciously tiny WAVs (<4KB) flag remainder-dropped utterances.
cd /data/local/tmp/eqtest || exit 99
i=0
fail=0
while IFS= read -r line || [ -n "$line" ]; do
  i=$((i + 1))
  if [ -z "$line" ]; then continue; fi
  printf '%s' "$line" > c_line.txt
  for lang in 0x10000 0x40000; do
    if ./eloquick -L "$lang" -f c_line.txt -o c.wav 2>c_err.txt; then
      sz=$(stat -c %s c.wav 2>/dev/null || wc -c < c.wav)
      tag=""
      if [ "$sz" -lt 4000 ]; then tag=" SMALL?"; fi
      echo "ok $i [$lang] ${sz}B${tag} :: $line"
    else
      fail=$((fail + 1))
      echo "FAIL $i [$lang] rc<>0 :: $line :: $(cat c_err.txt)"
    fi
  done
done < crash-battery.txt
echo "crash-battery: lines=$i fails=$fail"
