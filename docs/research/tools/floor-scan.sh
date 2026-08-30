#!/usr/bin/env bash
# 저잔량 바닥 임계값 스캔.
# 충전하면서 60초마다 pass_through=1 을 쓰고, 살아남는지 / 전류가 죽는지 본다.
# 화면 상태를 매번 기록한다 (꺼져 있으면 그 샘플은 무효).
set -u
STOP_LVL="${1:-35}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/snapshots"; mkdir -p "$OUT"
F="$OUT/floor-scan.tsv"
printf 'time\tlvl\tscreen\tpt_after\tcur_mA\tstatus\tplug\tfg\n' | tee "$F"

snap() {
  local d
  d=$(adb shell 'dumpsys battery; echo "PT: $(settings get system pass_through)"; dumpsys power | grep -m1 -oE "mWakefulness=[A-Za-z]+"; echo "FG: $(dumpsys activity activities | grep -m1 topResumedActivity | sed "s/.*u0 //;s/ .*//")"' 2>/dev/null | tr -d '\r')
  gg() { printf '%s' "$d" | grep -i "$1" | head -1 | sed 's/.*[:=] *//'; }
  S_CUR=$(gg '^ *current now'); S_LVL=$(gg '^ *level'); S_ST=$(gg '^ *status')
  S_PT=$(gg '^PT'); S_FG=$(gg '^FG'); S_WAKE=$(gg 'mWakefulness')
  S_PLUG=none
  printf '%s' "$d" | grep -qi 'USB powered: true' && S_PLUG=usb
  printf '%s' "$d" | grep -qi 'AC powered: true'  && S_PLUG=ac
}

echo "충전기 연결 대기..."
for i in $(seq 150); do
  snap; [ "$S_PLUG" != none ] && { echo ">>> 감지 $(date +%H:%M:%S) lvl=$S_LVL"; break; }
  sleep 2
done

while :; do
  snap
  [ -n "$S_LVL" ] && [ "$S_LVL" -ge "$STOP_LVL" ] && { echo "목표 잔량 $STOP_LVL% 도달, 종료"; break; }
  adb shell settings put system pass_through 1 >/dev/null 2>&1
  sleep 20
  snap
  mark=""
  [ "$S_WAKE" != "Awake" ] && mark="  <<< 화면 꺼짐, 무효"
  [ "$S_PT" = 0 ] && mark="$mark  <<< pt 가 0 으로 되돌려짐"
  line=$(printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s' \
    "$(date +%H:%M:%S)" "$S_LVL" "$S_WAKE" "$S_PT" "$S_CUR" "$S_ST" "$S_PLUG" "$S_FG")
  echo "$line$mark" | tee -a "$F"
  sleep 40
done
echo "=== 스캔 종료. pass_through 현재값: $(adb shell settings get system pass_through | tr -d '\r')"
