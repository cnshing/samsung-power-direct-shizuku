#!/usr/bin/env bash
# 트리거 매트릭스 — 어떤 전이가 pass_through 를 지우는가.
#
#   ./tools/trigger-matrix.sh                          팔 1: gametools 죽은 상태
#   ./tools/trigger-matrix.sh --game <패키지>          팔 2: 게임 한 번 켰다 끈 뒤
#
# 잠금화면은 켜둔 채로 진행한다 (그게 실제 동작이다).
# 화면을 껐다 켜는 구간에서는 사용자가 직접 잠금을 풀 때까지 기다린다.
set -u
GAME=""
[ "${1:-}" = "--game" ] && GAME="${2:?패키지명}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; OUT="$ROOT/snapshots"; mkdir -p "$OUT"
ARM=$([ -n "$GAME" ] && echo after-game || echo no-game)
F="$OUT/trigger-matrix-$ARM.tsv"
printf 'action\tpt\tscreen\tlock\tgt_pid\tfg\n' > "$F"

read_state() {
  local d
  d=$(adb shell '
    echo "PT: $(settings get system pass_through)"
    dumpsys power  | grep -m1 -oE "mWakefulness=[A-Za-z]+"
    dumpsys window | grep -m1 -oE "mDreamingLockscreen=(true|false)"
    echo "GT: $(pidof com.samsung.android.game.gametools)"
    echo "FG: $(dumpsys activity activities | grep -m1 topResumedActivity | sed "s/.*u0 //;s/ .*//")"
  ' 2>/dev/null | tr -d '\r')
  R_PT=$(printf '%s' "$d" | sed -n 's/^PT: *//p' | head -1)
  R_WAKE=$(printf '%s' "$d" | grep -oE 'mWakefulness=[A-Za-z]+' | sed 's/.*=//')
  R_LOCK=$(printf '%s' "$d" | grep -oE 'mDreamingLockscreen=(true|false)' | sed 's/.*=//')
  R_GT=$(printf '%s' "$d" | sed -n 's/^GT: *//p' | head -1)
  R_FG=$(printf '%s' "$d" | sed -n 's/^FG: *//p' | head -1)
  [ -z "$R_GT" ]   && R_GT="(죽음)"
  [ -z "$R_LOCK" ] && R_LOCK="?"
  [ -z "$R_FG" ]   && R_FG="-"
}

HDR='%-30s %-3s %-8s %-6s %-8s %-40s %s\n'
row() {  # row <라벨> [기대판정메모]
  local label="$1"; local note="${2:-}"
  local verdict="유지"; [ "$R_PT" = 0 ] && verdict="★지워짐"
  printf "$HDR" "$label" "$R_PT" "$R_WAKE" "$R_LOCK" "$R_GT" "$R_FG" "$verdict $note"
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$label" "$R_PT" "$R_WAKE" "$R_LOCK" "$R_GT" "$R_FG" >> "$F"
}

set1() { adb shell settings put system pass_through 1 >/dev/null 2>&1; sleep 4; }
k()    { adb shell input keyevent "$1" >/dev/null 2>&1; }
app()  { adb shell monkey -p "$1" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; }

# 잠금이 풀릴 때까지 대기
wait_unlock() {
  for i in $(seq 60); do
    read_state
    [ "$R_LOCK" = false ] && [ "$R_WAKE" = Awake ] && return 0
    sleep 3
  done
  echo "  !!! 잠금 해제 대기 시간 초과"
  return 1
}

# act <라벨> <명령...>  — pt=1 세팅 후 전이 수행, 8초 뒤 관측
act() { local label="$1"; shift; set1; "$@"; sleep 8; read_state; row "$label"; }

echo "=== 팔: $ARM ==="
adb shell settings put system screen_off_timeout 1800000 >/dev/null 2>&1
k KEYCODE_WAKEUP; sleep 2
read_state
if [ "$R_LOCK" = true ]; then
  echo "잠금 상태다. 풀어달라. 기다린다..."
  wait_unlock || exit 1
fi
k KEYCODE_HOME; sleep 2; read_state
echo "시작: pt=$R_PT screen=$R_WAKE lock=$R_LOCK gametools=$R_GT fg=$R_FG"
echo

if [ -n "$GAME" ]; then
  echo "--- 게임을 켰다 끈다 (gametools 를 깨운다) ---"
  app "$GAME"; sleep 15; read_state
  echo "  게임 포그라운드: pt=$R_PT fg=$R_FG gametools=$R_GT"
  k KEYCODE_HOME; sleep 6; read_state
  echo "  홈 복귀:        pt=$R_PT gametools=$R_GT"
  echo
fi

printf "$HDR" ACTION pt screen lock gt_pid fg 판정
act "0. 대조군 (전이 없음)"        true

# ── 화면 끔/켬을 세 지점으로 분해. pt=1 은 맨 앞에서 한 번만 세팅한다 ──
set1
k KEYCODE_SLEEP; sleep 6; read_state; row "1. 화면 끔 직후"
k KEYCODE_WAKEUP; sleep 4; read_state; row "2. 화면 켬 (아직 잠금)"
echo "   ▶ 지금 잠금을 풀어달라 (지문/안면/PIN). 기다린다..."
if wait_unlock; then sleep 4; read_state; row "3. 잠금 해제 직후"; fi

act "4. 앱 실행 (설정)"            app com.android.settings
act "5. 앱→앱 (설정→Termux)"       app com.termux
act "6. 홈 버튼"                   k KEYCODE_HOME
act "7. 최근 앱 열기"              k KEYCODE_APP_SWITCH
k KEYCODE_BACK; sleep 2
act "8. 알림창 내리기"             adb shell cmd statusbar expand-notifications
adb shell cmd statusbar collapse >/dev/null 2>&1; sleep 2
act "9. 뒤로가기"                  k KEYCODE_BACK

if [ -n "$GAME" ]; then
  echo
  echo "--- 양성 대조군 ---"
  adb shell settings put system pass_through 0 >/dev/null 2>&1
  app "$GAME"; sleep 15; read_state; row "10. 게임 실행 (0→1 기대)"
  k KEYCODE_HOME; sleep 8;  read_state; row "11. 게임 이탈 (1→0 기대)"
fi

echo
echo "결과: $F"
