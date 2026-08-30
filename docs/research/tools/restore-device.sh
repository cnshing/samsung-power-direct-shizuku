#!/usr/bin/env bash
# 실험 중 바꾼 기기 설정 원복 (2026-08-30 실험 기준 원값)
set -u
adb shell settings put system screen_off_timeout 60000
adb shell settings put system screen_brightness_mode 0
adb shell settings put system screen_brightness 61
echo "원복 완료: screen_off_timeout=60000 brightness_mode=0 brightness=61"
echo "pass_through 현재값: $(adb shell settings get system pass_through | tr -d '\r')"
