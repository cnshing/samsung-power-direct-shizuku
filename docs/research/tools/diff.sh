#!/usr/bin/env bash
# 두 스냅샷의 차이만 뽑는다.  사용법: ./tools/diff.sh <라벨1> <라벨2>
set -u
D="$(dirname "$0")/../snapshots"
diff -u "$D/${1:?}.txt" "$D/${2:?}.txt" | grep -E '^[-+][^-+]' | sort -k1.2 || echo "(차이 없음)"
