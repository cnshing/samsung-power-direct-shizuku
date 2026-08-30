#!/usr/bin/env bash
# 비대화형 구간 샘플러.  ./tools/phase.sh <라벨> <초> [간격초]
# 삼성 dumpsys battery 의 'current now'(mA) 를 직접 읽는다.
set -u
LABEL="${1:?라벨}"; DUR="${2:?초}"; IV="${3:-10}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/snapshots"; mkdir -p "$OUT"
TSV="$OUT/supervised.tsv"
[ -s "$TSV" ] || printf 'phase\ttime\tpt\tlvl\tcur_mA\tcounter\tstatus\tchg_state\tcur_event\tmV\tdegC\tplug\tfg\n' > "$TSV"

read_batt() {
  local d
  d=$(adb shell 'dumpsys battery; echo "PT: $(settings get system pass_through)"; echo "FG: $(dumpsys activity activities | grep -m1 topResumedActivity | sed "s/.*u0 //;s/ .*//")"' 2>/dev/null | tr -d '\r')
  gg() { printf '%s' "$d" | grep -i "$1" | head -1 | sed 's/.*: *//'; }
  B_CUR=$(gg '^ *current now')
  B_CNT=$(gg '^ *Charge counter'); B_LVL=$(gg '^ *level'); B_ST=$(gg '^ *status')
  B_CHG=$(gg '^ *Charging state'); B_EV=$(gg '^ *batteryCurrentEvent')
  B_V=$(gg '^ *voltage'); B_T=$(gg '^ *temperature'); B_PT=$(gg '^PT'); B_FG=$(gg '^FG')
  B_PLUG=none
  printf '%s' "$d" | grep -qi 'USB powered: true' && B_PLUG=usb
  printf '%s' "$d" | grep -qi 'AC powered: true'  && B_PLUG=ac
}

read_batt; t0=$(date +%s); c0=$B_CNT; pt0=$B_PT; plug0=$B_PLUG; warn=""; sts=""; evs=""
sum=0; n=0; mn=999999; mx=-999999
echo "[$LABEL] 시작  pt=$pt0 plug=$plug0 lvl=$B_LVL cur=${B_CUR}mA ev=$B_EV fg=$B_FG"
printf '%-8s %-3s %-4s %8s %-3s %-3s %-11s %-6s %-5s %-5s %s\n' time pt lvl cur_mA st chg cur_event mV degC plug net_uAh
end=$(( t0 + DUR ))
while [ "$(date +%s)" -lt "$end" ]; do
  read_batt
  [ "$B_PT"   != "$pt0"   ] && warn="$warn pt:$pt0->$B_PT"
  [ "$B_PLUG" != "$plug0" ] && warn="$warn plug:$plug0->$B_PLUG"
  case " $sts " in *" $B_ST "*) ;; *) sts="$sts$B_ST ";; esac
  case " $evs " in *" $B_EV "*) ;; *) evs="$evs$B_EV ";; esac
  case "$B_CUR" in ''|*[!0-9-]*) ;; *)
      sum=$(( sum + B_CUR )); n=$(( n + 1 ))
      [ "$B_CUR" -lt "$mn" ] && mn=$B_CUR
      [ "$B_CUR" -gt "$mx" ] && mx=$B_CUR ;;
  esac
  printf '%-8s %-3s %-4s %8s %-3s %-3s %-11s %-6s %-5s %-5s %s\n' \
    "$(date +%H:%M:%S)" "$B_PT" "$B_LVL" "$B_CUR" "$B_ST" "$B_CHG" "$B_EV" "$B_V" "$B_T" "$B_PLUG" "$(( B_CNT - c0 ))"
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$LABEL" "$(date +%H:%M:%S)" "$B_PT" "$B_LVL" "$B_CUR" "$B_CNT" "$B_ST" "$B_CHG" "$B_EV" "$B_V" "$B_T" "$B_PLUG" "$B_FG" >> "$TSV"
  sleep "$IV"
done
read_batt; t=$(date +%s); net=$(( B_CNT - c0 )); el=$(( t - t0 )); ma=0
[ "$el" -gt 0 ] && ma=$(( net * 36 / el / 10 ))
avg=0; [ "$n" -gt 0 ] && avg=$(( sum / n ))
echo "=== [$LABEL] 전류 평균 ${avg} mA (min ${mn} / max ${mx}, n=${n})"
echo "    카운터 적분 ${net} µAh / ${el}s -> ${ma} mA   [두 값이 비슷해야 신뢰]"
echo "    status:${sts}| cur_event:${evs}| lvl ${B_LVL} temp ${B_T} fg ${B_FG}"
[ -n "$warn" ] && echo "!!! 구간 중 변화:$warn  -> 폐기" || echo "    구간 클린"
