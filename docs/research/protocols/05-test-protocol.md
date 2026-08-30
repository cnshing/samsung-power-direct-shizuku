# 실기기 테스트 프로토콜

목적: **게임 부스터의 공식 토글을 켰을 때 실제로 어떤 설정값이 바뀌는지**를 추측이 아니라
diff로 특정한다. `pass_through`는 XDA 단일 출처라서 그대로 믿지 않는다.

## 0. 준비물 체크 (이거 없으면 전부 무의미)

- [ ] **PPS 지원 25W 이상 PD 충전기** — 이게 제일 중요하다. PPS 없는 PD 충전기는 안 된다.
      확인법: 충전기 스펙에 `PPS 3.3~11V` 같은 가변 전압 표기가 있는지. 삼성 45W 정품이면 확실.
- [ ] 배터리 20% 이상
- [ ] 설정 > 배터리 > **고속 충전 켜짐**
- [ ] 게임런처에 실제 게임 하나 (진짜 게임이어야 함)
- [ ] 태블릿 개발자 옵션 > **무선 디버깅** 켜짐

## 1. PC에 태블릿 붙이기

태블릿: 개발자 옵션 → 무선 디버깅 → **페어링 코드로 기기 페어링** (IP:포트 + 6자리 코드가 뜬다)

```sh
adb pair <페어링용-IP>:<페어링용-포트>     # 코드 입력
adb connect <무선디버깅-IP>:<무선디버깅-포트>   # 위 화면 상단의 다른 포트
adb devices -l                              # device 로 뜨면 성공
```

## 2. 4단계 스냅샷

각 단계에서 **태블릿 화면을 켠 채로** PC에서 실행:

```sh
cd ~/Desktop/research/samsung-power-direct

# A. 충전기 뽑음, 게임 없음 (기준선)
./tools/snapshot.sh A-unplugged-nogame

# B. PPS 충전기 꽂음, 게임 없음
./tools/snapshot.sh B-plugged-nogame

# C. 충전기 꽂은 채로 게임 실행 (아직 토글 OFF, 홈 화면 말고 게임 화면에 머물 것)
./tools/snapshot.sh C-plugged-game-off

# D. 게임 부스터 열어서 "게임 중 USB PD 충전 일시정지" ON
#    (게임 화면으로 돌아온 뒤 10초쯤 두고)
./tools/snapshot.sh D-plugged-game-on
```

> 스냅샷은 `adb shell`이라 게임이 포그라운드인 상태 그대로 뜬다. 태블릿을 만질 필요 없음.

## 3. 비교

```sh
./tools/diff.sh C-plugged-game-off D-plugged-game-on   # ★ 이게 핵심
./tools/diff.sh B-plugged-nogame  C-plugged-game-off   # 게임 진입이 뭘 바꾸나
./tools/diff.sh A-unplugged-nogame B-plugged-nogame    # 충전기 꽂힘이 뭘 바꾸나
```

**C→D diff에 나타나는 설정 키가 우리가 찾는 것이다.** `pass_through`가 나오면 XDA 주장 확인,
안 나오고 다른 키가 나오면 그게 진짜다. 둘 다 아니고 sysfs 값만 바뀌면 → 설정 경유가 아니라
프레임워크가 직접 커널을 때리는 구조라는 뜻이고, 그럼 ADB 경로는 죽는다 (03 문서 참조).

## 4. 효과 실측 (기능이 진짜인지)

C 상태와 D 상태에서 각각:

```sh
watch -n1 'adb shell cat /sys/class/power_supply/battery/current_now \
  /sys/class/power_supply/battery/status'
```

- `current_now`: 부호/단위는 기기마다 다르다 (µA, 충전이 +일 수도 −일 수도).
  **중요한 건 절댓값이 C에서 D로 갈 때 거의 0에 수렴하는가.**
- XDA 실측 반론대로 30~70mA(=30000~70000 µA)가 남으면 "완전 바이패스는 아님"이 우리 기기에서도 재현된 것.
- `status`: `Charging` → `Not charging` 으로 바뀌는지.

## 5. ADB로 직접 트리거 시도 (3단계에서 키를 찾았다면)

게임을 **끄고**, 충전기는 꽂은 채로:

```sh
adb shell settings get system pass_through          # 또는 3에서 찾은 키
adb shell settings put system pass_through 1
adb shell cat /sys/class/power_supply/battery/current_now   # 변하나?
adb shell settings put system pass_through 0        # 원복
```

가능한 결과 세 가지:
1. **전류가 0으로 떨어진다** → 완전 성공. 04번 앱 계획으로 직행.
2. 값은 써지는데 전류 변화 없음 → 프레임워크가 이 키를 읽지 않거나, "게임 포그라운드" 조건을 별도로
   검사한다. → Game Booster+ 재분류(02) 경로와 조합해야 함.
3. `settings get`이 `null` → 이 펌웨어엔 그런 키 자체가 없다. C→D diff 결과가 유일한 단서.

## 6. sysfs 직접 쓰기 (위가 다 실패했을 때 마지막 확인)

거의 확실히 막혀 있지만, **어떻게 막히는지**를 기록해두면 이후 판단에 쓰인다.

```sh
adb shell 'echo 1 > /sys/class/power_supply/battery/store_mode'   # Permission denied 예상
adb shell 'dmesg | grep avc' ; adb logcat -d -b all | grep avc | tail -20
```

`avc: denied` 가 뜨면 SELinux 벽, 그냥 `Permission denied`면 DAC 벽. 둘 다 루팅 없이는 못 넘는다.

## 7. 기록할 것

결과는 `snapshots/` 에 그대로 남는다. 여기에 요약만 적어둘 것:

| 항목 | 결과 |
|---|---|
| 충전기 PPS 여부 | |
| C→D에서 바뀐 설정 키 | |
| `pass_through` 존재? | |
| C의 current_now | |
| D의 current_now | |
| ADB 단독 트리거 성공? | |
| Game Booster+ 설치 가능? (`honeyplayplus`) | |
