#!/usr/bin/env bash
# sysfs가 막혔을 때의 대체 측정.
# dumpsys battery 의 Charge counter(µAh)·level·온도를 주기적으로 찍는다.
# 충전기 꽂힌 채로 Charge counter 가 평평하면 = 배터리로 전류가 안 들어가고 있다.
#
# 사용법: ./tools/watch-battery.sh [간격초] [라벨]
#   예:   ./tools/watch-battery.sh 20 D-game-on
# Ctrl-C 로 종료. 결과는 snapshots/battery-<라벨>.tsv

set -u
IV="${1:-20}"
LABEL="${2:-run}"
OUT="$(dirname "$0")/../snapshots"
mkdir -p "$OUT"
F="$OUT/battery-$LABEL.tsv"

# 카운터 양자화 단위(µAh). Tab S9+ = 10090 (설계용량의 0.1%).
# 다른 기기면 첫 실행 로그의 증분 최소값으로 바꿀 것.
QUANT=10090

printf 'time\tpass_through\tlevel\tcounter_uAh\tstatus\tvolt_mV\ttemp_0.1C\tplug\tdelta\tcum\tmA_avg\n' | tee "$F"

prev=""; first=""; t0=$(date +%s); cum=0; plug_prev=""
while true; do
  d=$(adb shell dumpsys battery 2>/dev/null)
  pt=$(adb shell settings get system pass_through 2>/dev/null | tr -d '\r')
  g() { printf '%s' "$d" | grep -i "$1" | head -1 | sed 's/.*: *//' | tr -d '\r'; }
  lvl=$(g '^ *level'); cnt=$(g 'Charge counter'); st=$(g '^ *status')
  vt=$(g '^ *voltage'); tp=$(g '^ *temperature')
  plug="none"
  printf '%s' "$d" | grep -qi 'USB powered: true' && plug="usb"
  printf '%s' "$d" | grep -qi 'AC powered: true'  && plug="ac"

  d=""; ma=""
  if [ -n "$cnt" ]; then
    [ -z "$first" ] && first="$cnt"
    [ -n "$prev" ] && d=$((cnt - prev))
    cum=$((cnt - first))
    el=$(( $(date +%s) - t0 ))
    # 구간 전체 평균 전류(mA) = 누적 µAh / 경과시간 * 3600 / 1000
    [ "$el" -gt 0 ] && ma=$(( cum * 36 / el / 10 ))
  fi
  line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' \
    "$(date +%H:%M:%S)" "$pt" "$lvl" "$cnt" "$st" "$vt" "$tp" "$plug" \
    "${d:+Δ$d}" "${cum:+$cum}" "${ma:+${ma}mA}")
  # 충전기 연결 상태가 바뀌면 경고 — 측정이 오염된다
  if [ -n "$plug_prev" ] && [ "$plug" != "$plug_prev" ]; then
    line="$line	<<< PLUG $plug_prev->$plug 측정오염"
  fi
  plug_prev="$plug"
  [ -n "$cnt" ] && prev="$cnt"
  echo "$line" | tee -a "$F"
  sleep "$IV"
done
