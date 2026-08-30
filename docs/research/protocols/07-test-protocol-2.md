# 2차 테스트 — ADB 단독 트리거가 실제로 먹는가

전제: 06에서 `pass_through` = 우회충전 토글임이 확인됨.
이제 **게임 없이 그 값을 켰을 때 진짜로 충전이 멈추는지**를 본다.

측정은 `sysfs`가 막혔으므로 `dumpsys battery`의 **Charge counter(µAh)** 증분으로 한다.
충전기가 꽂혀 있는데 counter가 평평하면 = 배터리로 전류가 안 들어가고 있다.

준비: **배터리를 만충 근처로 두지 말 것.** 70~85% 정도가 이상적이다.
(만충 근처면 원래도 충전이 느려서 구분이 안 된다.)

---

## Step 1. 기준선 — 게임 없이, 그냥 충전

PPS 충전기 꽂고, 게임 끄고, **화면 켜둔 채로** (화면 자동꺼짐 시간 넉넉히):

```sh
adb shell settings get system pass_through     # 0 이어야 정상
./tools/watch-battery.sh 20 base-charging
```

**5분(15줄) 정도** 두고 Ctrl-C. `Δ` 컬럼이 계속 양수로 나오는 걸 확인 → 충전 중.
증분이 대략 얼마인지 기억해둘 것.

## Step 2. ★ 핵심 — 게임 없이 ADB로 켜기

```sh
adb shell settings put system pass_through 1
adb shell settings get system pass_through     # 1 확인
./tools/watch-battery.sh 20 adb-triggered
```

**5분** 관찰. 판정:

| Δ 거동 | 의미 |
|---|---|
| **0 근처로 평평해짐** | **완전 성공.** 04번 앱 계획으로 직행 |
| Step 1보다 확연히 작아짐 | 성공. 삼성 구현이 원래 완전 차단이 아닌 것 (01 참조). 목적상 충분 |
| Step 1과 똑같음 | 프레임워크가 이 값을 게임 상태와 함께만 읽는다 → Step 4로 |

원복:
```sh
adb shell settings put system pass_through 0
```

## Step 3. 되돌아가는 조건 찾기 (04의 재적용 주기 근거)

`pass_through=1`로 둔 상태에서 아래를 하나씩 하고, 매번 값을 확인:

```sh
chk() { adb shell settings get system pass_through; }

adb shell settings put system pass_through 1; chk    # 1

# (1) 화면 끄기 → 다시 켜기
adb shell input keyevent KEYCODE_POWER; sleep 5
adb shell input keyevent KEYCODE_POWER; chk

# (2) 충전기 뽑았다 꽂기 (직접)
chk

# (3) 아무 앱 실행/전환
adb shell monkey -p com.android.settings 1 >/dev/null 2>&1; sleep 3; chk

# (4) 게임 실행했다 끄기
#     (게임 부스터가 자기 상태로 덮어쓰는지가 핵심)
chk

# (5) 그냥 10분 방치
sleep 600; chk

# (6) 재부팅 후
adb reboot; sleep 60; adb connect <IP>:<포트>; chk
```

**무엇이 0으로 되돌리는지**를 06번 문서에 기록. 되돌리는 게 아무것도 없다면
재적용 주기를 훨씬 길게(또는 이벤트 기반으로만) 잡아도 된다.

## Step 4. Step 2가 실패했을 경우

`pass_through` 값만으로는 부족하고 "게임이 포그라운드"라는 별도 조건이 있다는 뜻.
그럼 조합을 시도한다:

1. **Game Booster+ 굿락 모듈** 설치 시도 (02 참조):
   ```sh
   adb shell pm list packages | grep honeyplayplus
   ```
   없으면 굿락 앱에서 "Game Booster+" 모듈을 찾아 설치. 국내라 있을 가능성이 높다.
2. 개발할 때 쓰는 앱(브라우저 등) 하나를 **게임 카테고리로 재분류**
3. 그 앱을 포그라운드에 둔 상태에서 다시 Step 2 측정
4. 재분류만으로 되는지, 재분류 + `pass_through` 수동 세팅이 필요한지 구분

이 경우 최종 형태는 "포그라운드 앱이 재분류된 앱일 때만 작동"이라 목적을 절반만 달성한다.
그래도 아무것도 없는 것보다는 낫다.

## Step 5. 온도도 같이 본다

`watch-battery.sh`가 `temp_0.1C`를 같이 찍는다 (300 = 30.0℃).
우회충전이 실제로 발열을 줄이는지 Step 1 vs Step 2에서 비교해두면,
장기 사용의 근거가 된다.

---

## 기록할 것 (06번 문서에 추가)

| 항목 | 결과 |
|---|---|
| Step 1 평균 Δ (충전 중) | |
| Step 2 평균 Δ (ADB 트리거) | |
| 판정 | |
| Step 1 온도 / Step 2 온도 | |
| `pass_through`를 0으로 되돌리는 조건 | |
| `honeyplayplus` 설치 여부 | |
