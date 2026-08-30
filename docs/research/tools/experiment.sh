#!/usr/bin/env bash
# samsung-power-direct — 위상 기반 실험 러너
#
# 충전기 착탈 타이밍·pass_through 값·안정화 대기·구간 길이를 스크립트가 강제한다.
# 각 구간의 순 증분(µAh)과 평균 전류(mA)를 구간별로 요약해서 마지막에 표로 뽑는다.
#
#   ./tools/experiment.sh e2-adb          # 게임 없이 ADB 단독 (본 실험)
#   ./tools/experiment.sh e1-game         # 게임 켠 채로 (정답지 확보)
#   DUR=180 ./tools/experiment.sh e2-adb  # 구간 길이 바꾸기 (기본 300초)
#
# 환경변수: DUR(구간 초, 기본 300) INTERVAL(샘플 초, 기본 15) SETTLE(안정화 초, 기본 30)

set -u
PLAN="${1:?플랜 이름: e1-game | e2-adb}"
DUR="${DUR:-300}"
INTERVAL="${INTERVAL:-15}"
SETTLE="${SETTLE:-30}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/snapshots"; mkdir -p "$OUT"
TSV="$OUT/exp-$PLAN.tsv"
: > "$TSV"

adbs() { adb shell "$@" 2>/dev/null | tr -d '\r'; }

# ── 화면/밝기 고정 (부하를 상수로 만든다). 종료 시 원복 ──────────────────
OLD_TIMEOUT=$(adbs settings get system screen_off_timeout)
OLD_BMODE=$(adbs settings get system screen_brightness_mode)
OLD_BRIGHT=$(adbs settings get system screen_brightness)
restore() {
  echo
  echo "── 원복 중 ──"
  [ "$OLD_TIMEOUT" != "null" ] && adbs settings put system screen_off_timeout "$OLD_TIMEOUT"
  [ "$OLD_BMODE"   != "null" ] && adbs settings put system screen_brightness_mode "$OLD_BMODE"
  [ "$OLD_BRIGHT"  != "null" ] && adbs settings put system screen_brightness "$OLD_BRIGHT"
  echo "화면 설정 원복됨. pass_through 는 그대로 두었으니 필요하면 직접 0으로."
}
trap restore EXIT

adbs settings put system screen_off_timeout 1800000   # 30분
adbs settings put system screen_brightness_mode 0     # 자동밝기 끔
adbs settings put system screen_brightness 100        # 고정
echo "화면: 30분 유지 / 자동밝기 끔 / 밝기 100 고정"

# ── 배터리 1회 읽기 ────────────────────────────────────────────────────
# 전역: B_CNT B_LVL B_ST B_V B_T B_PLUG B_PT
read_batt() {
  local d
  d=$(adb shell 'dumpsys battery; echo "PT: $(settings get system pass_through)"' 2>/dev/null | tr -d '\r')
  gg() { printf '%s' "$d" | grep -i "$1" | head -1 | sed 's/.*: *//'; }
  B_CNT=$(gg 'Charge counter'); B_LVL=$(gg '^ *level'); B_ST=$(gg '^ *status')
  B_V=$(gg '^ *voltage'); B_T=$(gg '^ *temperature'); B_PT=$(gg '^PT')
  B_PLUG=none
  printf '%s' "$d" | grep -qi 'USB powered: true' && B_PLUG=usb
  printf '%s' "$d" | grep -qi 'AC powered: true'  && B_PLUG=ac
}

PHASE_NAMES=(); PHASE_RES=()

# phase <라벨> <기대plug: ac|none> <pt: 0|1|keep> <설명>
phase() {
  local label="$1" want="$2" pt="$3" desc="$4"
  echo
  echo "════════════════════════════════════════════════════════════"
  echo "  구간 $label — $desc"
  echo "════════════════════════════════════════════════════════════"

  if [ "$pt" != "keep" ]; then
    adbs settings put system pass_through "$pt"
    echo "  pass_through := $pt"
  fi

  while :; do
    if [ "$want" = none ]; then
      read -r -p "  ▶ 충전기를 [뽑고] Enter: " _ </dev/tty
    else
      read -r -p "  ▶ 충전기를 [꽂고] Enter: " _ </dev/tty
    fi
    read_batt
    if [ "$want" = none ] && [ "$B_PLUG" != none ]; then
      echo "  ✗ 아직 plug=$B_PLUG 로 보인다. 다시."; continue
    fi
    if [ "$want" != none ] && [ "$B_PLUG" = none ]; then
      echo "  ✗ 아직 안 꽂힌 것으로 보인다. 다시."; continue
    fi
    break
  done

  echo "  안정화 ${SETTLE}초 대기 (PD 재협상·상태보고 지연 버림)..."
  sleep "$SETTLE"

  local t0 c0 pt0 plug0 t c
  read_batt; t0=$(date +%s); c0=$B_CNT; pt0=$B_PT; plug0=$B_PLUG
  local warn="" sts=""
  echo "  샘플링 ${DUR}초 (${INTERVAL}초 간격)"
  printf '  %-8s %-3s %-4s %-10s %-3s %-6s %-5s %-5s %s\n' \
    time pt lvl counter st mV degC plug net
  local end=$(( t0 + DUR ))
  while [ "$(date +%s)" -lt "$end" ]; do
    read_batt; t=$(date +%s); c=$B_CNT
    [ "$B_PT"   != "$pt0"   ] && warn="$warn pass_through:$pt0→$B_PT"
    [ "$B_PLUG" != "$plug0" ] && warn="$warn plug:$plug0→$B_PLUG"
    case " $sts " in *" $B_ST "*) ;; *) sts="$sts $B_ST";; esac
    printf '  %-8s %-3s %-4s %-10s %-3s %-6s %-5s %-5s %s\n' \
      "$(date +%H:%M:%S)" "$B_PT" "$B_LVL" "$B_CNT" "$B_ST" "$B_V" "$B_T" "$B_PLUG" \
      "$(( c - c0 ))"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
      "$label" "$(date +%H:%M:%S)" "$B_PT" "$B_LVL" "$c" "$B_ST" "$B_V" "$B_T" "$B_PLUG" >> "$TSV"
    sleep "$INTERVAL"
  done

  read_batt; t=$(date +%s); c=$B_CNT
  local net=$(( c - c0 )) el=$(( t - t0 )) ma=0
  [ "$el" -gt 0 ] && ma=$(( net * 36 / el / 10 ))
  echo "  ── $label: 순증분 ${net} µAh / ${el}초 → 평균 ${ma} mA  (status:${sts})"
  [ -n "$warn" ] && echo "  ⚠ 구간 중 상태 변화:$warn  ← 이 구간은 버릴 것"
  PHASE_NAMES+=("$label"); PHASE_RES+=("$net|$el|$ma|$sts|$warn")
}

case "$PLAN" in
  e2-adb)
    echo "== E2: 게임 없이 ADB 단독으로 pass_through 제어 =="
    echo "   시작 전: 게임 완전 종료, 배터리 55~70%, PPS 충전기 준비"
    adbs am force-stop com.pjsekai.kr
    phase U1 none 0 "무충전 기준선 — 시스템 부하(방전 속도) 측정"
    phase C1 ac   0 "충전 기준선 — pass_through=0"
    phase B1 ac   1 "★ ADB로 pass_through=1, 충전기 연결"
    phase U2 none 1 "pt=1 인 채로 뽑음 — 이 값이 방전에 영향 주는지"
    phase B2 ac   1 "★ 재현 — 다시 꽂고 pt=1"
    phase C2 ac   0 "충전 복귀 — 되돌아오는지 + 잔량 드리프트 보정"
    ;;
  e1-game)
    echo "== E1: 게임 켠 채로 (공식 기능의 정답지 확보) =="
    echo "   시작 전: 게임을 실행해서 포그라운드에 두고, 화면 켜둘 것"
    read -r -p "   게임 실행했으면 Enter: " _ </dev/tty
    phase gU1 none keep "게임 구동 중, 무충전 — 시스템 부하 측정"
    phase gB1 ac   keep "게임 구동 중, 충전기 연결 (pt는 게임이 알아서 1)"
    phase gU2 none keep "다시 뽑음 — 재현성"
    phase gB2 ac   keep "다시 꽂음 — 재현성"
    phase gC1 ac   0    "게임 부스터 토글 OFF 강제 (ADB) — 진짜 충전 속도"
    ;;
  *) echo "알 수 없는 플랜: $PLAN"; exit 1;;
esac

echo
echo "════════════════ 요약 ($PLAN) ════════════════"
printf '%-6s %12s %8s %10s  %s\n' 구간 순증분µAh 초 평균mA 비고
i=0
for n in "${PHASE_NAMES[@]}"; do
  IFS='|' read -r net el ma sts warn <<< "${PHASE_RES[$i]}"
  printf '%-6s %12s %8s %9smA  status:%s%s\n' "$n" "$net" "$el" "$ma" "$sts" "${warn:+  ⚠$warn}"
  i=$((i+1))
done
echo
echo "원자료: $TSV"
echo
echo "판정 요령:"
echo "  · U1(방전) 과 B1/B2(꽂고 pt=1) 를 비교한다."
echo "      B ≈ 0            → 진짜 바이패스 (시스템이 충전기에서 먹음)"
echo "      B ≈ U1 (음수)    → 충전만 멈춘 것. 배터리가 시스템을 먹임 → 수명에 나쁨"
echo "      B ≈ C1 (양수)    → pass_through 가 안 먹은 것"
echo "  · C1 과 C2 가 비슷해야 잔량 드리프트가 결과를 오염시키지 않았다는 뜻."
