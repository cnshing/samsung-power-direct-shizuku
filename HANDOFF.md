# 인수인계

Galaxy 태블릿에서 삼성의 우회충전(배터리를 전류 경로에서 빼고 충전기가 시스템을 직접 먹이는 것)을
게임이 아닐 때도 유지하는 앱. **동작한다.** 조사와 핵심 구현은 끝났다.

## 상태 (2026-08-30)

실기기 Galaxy Tab S9+ / Android 16 / One UI 8 에서 끝에서 끝까지 검증됐다.

- 앱이 **부팅 후 한 번** 자체 ADB 로 shell UID 에이전트를 띄운다. 그 뒤로는 ADB 와 무관하다
- 에이전트가 배터리 밴드(기본 65/75)를 지키며 `Settings.System.pass_through` 를 쓴다
- **재부팅 후 사용자 개입 없이 스스로 복구한다** (실측: `[boot_completed] agent launched`)
- 8코어 포화 부하에서도 배터리 전류 0 — 진짜 바이패스다

## 먼저 읽을 것

| | |
|---|---|
| **[docs/AGENT.md](docs/AGENT.md)** | **에이전트 구조와 함정 5개.** 에이전트를 건드리기 전에 반드시 |
| [docs/SPEC.md](docs/SPEC.md) | 확정 사실, 아키텍처 결정과 그 근거, 정책 |
| [docs/research/](docs/research/) | 조사 기록 12편. 평소엔 볼 필요 없다 |

한 줄 요약: `settings put system pass_through 1` 이면 우회충전이 걸린다. **단 shell UID 가 필요하다** —
`WRITE_SETTINGS` 로는 안 되고(AOSP 비공개 키 보호), Shizuku 는 재부팅 후 자동 시작이 root 전용이라
탈락했다. 그래서 `~/Projects/lan-mouse-android` 의 자체 ADB 구현을 이식했다.

## 다음 작업 — 퀵세팅 타일과 홈 위젯

지금 세션에서 하지 않은 이유는 위험해서가 아니라 **개념적으로 분리돼 있어서**다.
핵심 동작은 이미 끝났고, 이 둘은 그 위에 얹는 표면일 뿐이다.

필요한 것:

- **퀵세팅 타일** — 탭하면 자동 ↔ 끔. 상태(바이패스 중/충전 중/에이전트 죽음)를 아이콘과 라벨로.
  `lan-mouse-android` 의 `AgentTileService.kt` 가 패턴 참고용
- **홈 위젯** — 잔량, 배터리 전류, 모드, 에이전트 생존. `AgentWidget.kt` 참고
- 둘 다 `Prefs` 를 바꾸고 `Agent.writeConfig` 를 부르면 끝난다. **에이전트를 직접 건드리지 말 것**

지켜야 할 규칙 두 가지 (이유는 `docs/AGENT.md`):

1. **생존 판정을 새로 만들지 말 것.** `Agent.isAlive` 는 표시 전용, 되살릴지는 `ensureRunning` 이
   혼자 정한다. 호출하는 쪽에 게이트를 두면 같은 버그가 네 번째로 재발한다
2. **에이전트가 쓰는 파일을 앱 밖에서 만들지 말 것.** shell 이 만든 파일은 앱이 읽지 못한다

## 그 밖에 남은 것

- 장기 실사용 관측 — 발열, 실제 수명 효과. `docs/measure-phase.sh` 로 전류를 잰다
- 다른 기기 / One UI 버전에서의 재현성
- `docs/SPEC.md` 11장의 미확인 항목 (고잔량 거동 등)

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
