# 인수인계

Galaxy 태블릿에서 삼성의 우회충전(배터리를 전류 경로에서 빼고 충전기가 시스템을 직접 먹이는 것)을
게임이 아닐 때도 유지하는 앱. **동작한다.** 조사와 핵심 구현은 끝났다.

## 상태 (2026-08-31)

실기기 Galaxy Tab S9+ / Android 16 / One UI 8 에서 끝에서 끝까지 검증됐다.

- 앱이 **부팅 후 한 번** 자체 ADB 로 shell UID 에이전트를 띄운다. 그 뒤로는 ADB 와 무관하다
- 에이전트가 배터리 밴드(기본 65/75)를 지키며 `Settings.System.pass_through` 를 쓴다
- **재부팅 후 사용자 개입 없이 스스로 복구한다** (실측: `[boot_completed] agent launched`)
- 8코어 포화 부하에서도 배터리 전류 0 — 진짜 바이패스다
- 퀵세팅 타일과 홈 위젯으로 앱을 열지 않고 자동 ↔ 끔을 뒤집을 수 있다
- **화면을 끄고 10시간을 방치해도 유지된다.** 그동안 잔량이 2%p 내려갔다 — 트리클 충전조차
  없다는 뜻이다 ([docs/FIELD.md](docs/FIELD.md))

## 먼저 읽을 것

| | |
|---|---|
| **[docs/AGENT.md](docs/AGENT.md)** | **에이전트 구조와 함정 5개.** 에이전트를 건드리기 전에 반드시 |
| [docs/SPEC.md](docs/SPEC.md) | 확정 사실, 아키텍처 결정과 그 근거, 정책 |
| [docs/FIELD.md](docs/FIELD.md) | 완성된 앱의 장기 실사용 관측. 새 실측은 여기 쌓는다 |
| [docs/research/](docs/research/) | 구현 이전의 조사 기록 12편. 평소엔 볼 필요 없다 |

한 줄 요약: `settings put system pass_through 1` 이면 우회충전이 걸린다. **단 shell UID 가 필요하다** —
`WRITE_SETTINGS` 로는 안 되고(AOSP 비공개 키 보호), Shizuku 는 재부팅 후 자동 시작이 root 전용이라
탈락했다. 그래서 `~/Projects/lan-mouse-android` 의 자체 ADB 구현을 이식했다.

## 표면 — 퀵세팅 타일과 홈 위젯

`BypassTileService.kt` · `StatusWidget.kt` · `SurfaceState.kt`. 실기기에서 왕복 검증됐다
(타일 탭 → `agent.conf` 갱신 → 한 주기 안에 `pass_through` 반영 → 타일·위젯이 결과를 그림).

둘 다 하는 일은 같다 — `Prefs` 를 바꾸고, 세대를 올리고, `Agent.writeConfig` 로 넘기고,
`PolicyJobService` 를 하나 던진다. **에이전트를 직접 건드리지 않는다.** 그 판단은
`SurfaceState.toggle` 한 곳에만 있다.

건드리기 전에 알아둘 것 두 가지:

- **타일의 on/off 는 사용자가 켜 둔 것이지 지금 일어나는 일이 아니다.** 자동 모드에서 잔량이
  밴드 아래면 켜 둔 채로 정상 충전하는 것이 정상인데, 그때 타일을 꺼진 것으로 그리면 한 번 더
  눌러 진짜로 꺼버리게 된다. 실제 상태는 부제와 위젯 상태 줄이 말한다
- **위젯은 깨우지 않는 알람(`ELAPSED_REALTIME`)으로 60초마다 갱신한다.** `updatePeriodMillis`
  하한이 30분이라 전류에는 못 쓰고 `ACTION_BATTERY_CHANGED` 는 매니페스트로 못 받는다.
  깨우지 않는 알람은 기기가 이미 깨어 있을 때만 발화하고, 위젯은 화면이 켜져 있을 때만 보인다 —
  두 조건이 겹치므로 보이는 동안만 갱신되고 잠든 동안은 공짜다. `docs/AGENT.md` 가 알람을
  걷어낸 것은 **정책**을 알람에 맡기지 말라는 뜻이고, 이건 늦어도 잘못될 것이 없다

`adb shell am broadcast` 로는 위젯 탭을 흉내 낼 수 없다. 리시버가 `exported="false"` 라
shell UID 의 브로드캐스트는 버려진다 (탭은 앱이 만든 PendingIntent 라 앱 UID 로 발사된다).
`adb shell input tap` 으로 실제 위젯을 누르거나, 타일은
`cmd statusbar expand-settings` 로 그늘을 연 뒤 `cmd statusbar click-tile` 해야 한다 —
그늘이 닫혀 있으면 타일 서비스가 바인드되어 있지 않아 클릭이 큐에 쌓였다가 나중에 터진다.

## 다음 작업

- **`low` 에 닿았을 때 충전이 재개되는지 확인.** 유일하게 남은 정책 경로다. 표류가 약 20 mA
  라 75 → 65 에 **약 50시간** 걸린다 — 그냥 꽂아두고 기다리거나, `low` 를 현재 잔량 바로
  아래로 올려 인위적으로 밟아야 한다 ([docs/FIELD.md](docs/FIELD.md))
- 장시간 바이패스의 **발열**. `docs/measure-phase.sh` 로 전류를, `dumpsys battery` 로 온도를 잰다
- 다른 기기 / One UI 버전에서의 재현성
- `docs/SPEC.md` 11장의 나머지 미확인 항목

문서는 갖춰졌다 — `README.md` / `README-kr.md` (쓰는 사람), 이 문서와 `docs/` (만드는 사람).
새 실측은 `docs/FIELD.md` 에 쌓는다. `docs/research/` 는 구현 이전의 조사이고 정정 외에는
건드리지 않는다. **`docs/research/README.md` 는 조사 기록의 색인이지 프로젝트 README 가
아니다** — 루트에 진짜 README 가 생겼으니 더 헷갈리기 쉽다.

## 라이선스

**GPL-3.0.** `adb/` 와 `pairing.cpp` 가 Key Mapper(GPL-3.0) 유래라 선택의 여지가 없다.
자세한 것은 [NOTICE.md](NOTICE.md).

## 개발 환경

```sh
./gradlew assembleDebug
adb install -r build/outputs/apk/debug/samsung-power-direct-debug.apk
```

최초 1회만 데스크탑에서 (앱이 스스로에게 줄 수 없는 권한):

```sh
adb shell pm grant moe.qwreey.powerdirect android.permission.WRITE_SECURE_SETTINGS
```

에이전트 상태를 밖에서 보려면:

```sh
D=/storage/emulated/0/Android/data/moe.qwreey.powerdirect/files
adb shell "cat $D/agent.beat"    # epoch 잔량 충전여부 want pass_through 보호모드 임계값 ping
adb shell "cat $D/agent.conf"    # 모드 low high 세대
adb shell "ps -A -o PID,PPID,USER,ARGS | grep agent.sh"
adb shell "run-as moe.qwreey.powerdirect cat shared_prefs/runlog.xml"   # 앱 활동 로그
```

**주의**: `adb push` 로 에이전트 파일을 건드리지 말 것. push 는 덮어쓰기가 아니라 교체라
소유권이 shell 로 넘어가고, 그러면 앱이 그 파일을 읽지 못한다. (겪었다.)

기기 무선 디버깅 포트는 재부팅마다 바뀐다. 데스크탑 `adb` 가 mDNS 를 지원하지 않으면
설정 > 개발자 옵션 > 무선 디버깅에서 포트를 읽어 `adb connect` 해야 한다. 앱은 스스로 찾는다.
