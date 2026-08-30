#!/usr/bin/env bash
# samsung-power-direct — 상태 스냅샷
# 사용법:  ./tools/snapshot.sh <라벨>
#   예:    ./tools/snapshot.sh A-충전기없음-게임없음
# 각 단계마다 한 번씩 돌리고, 마지막에 ./tools/diff.sh 로 비교한다.

set -u
LABEL="${1:?라벨을 넣어라. 예: A-charger-off}"
OUT="$(dirname "$0")/../snapshots"
mkdir -p "$OUT"
F="$OUT/$LABEL.txt"

a() { adb shell "$@" 2>&1; }

{
  echo "### label: $LABEL"
  echo "### time: $(date -Iseconds)"
  echo
  echo "===== settings system ====="
  a settings list system | sort
  echo
  echo "===== settings global ====="
  a settings list global | sort
  echo
  echo "===== settings secure ====="
  a settings list secure | sort
  echo
  echo "===== battery uevent ====="
  a cat /sys/class/power_supply/battery/uevent
  echo
  echo "===== usb/charger power_supply ====="
  for p in usb ac wireless pogo otg battery; do
    echo "--- $p ---"
    a cat "/sys/class/power_supply/$p/uevent"
  done
  echo
  echo "===== 관심 노드 개별 ====="
  for n in current_now current_avg status charge_type online \
           direct_charging_status direct_charging_step direct_charging_iin \
           batt_slate_mode store_mode batt_current_event siop_level \
           batt_charging_source input_suspend charging_enabled; do
    printf '%-26s = %s\n' "$n" "$(a cat /sys/class/power_supply/battery/$n)"
  done
  echo
  echo "===== 게임런처가 게임으로 아는 패키지 ====="
  a content query --uri content://com.samsung.android.game.gamehome.data/applist
  echo
  echo "===== 게임 관련 패키지 설치 여부 ====="
  a pm list packages | grep -Ei 'game|honeyplay|goodlock' | sort
  echo
  echo "===== 포그라운드 앱 ====="
  a "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'"
} > "$F"

echo "저장됨: $F"
grep -E '^(system|global|secure)?.*pass_through' "$F" || echo "(pass_through 문자열 없음)"
