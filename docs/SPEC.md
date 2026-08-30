# samsung-power-direct — 구현 사양

이 문서 하나만 읽고 구현할 수 있게 쓴다. 근거가 필요하면 각 항목의 링크를 따라가라.
조사 기록은 [research/README.md](research/README.md) 의 문서 목록에 있다.

---

## 1. 무엇을 만드는가

Galaxy 태블릿에서 **충전기를 꽂아둔 채 배터리를 우회해 시스템에 직접 급전**하는 상태를
게임이 아닐 때도 유지하는 앱. 목표는 완전한 충전 차단이 아니라 **배터리 수명을 조금이라도 늘리는 것**.

검증 기기: Galaxy Tab S9+ (SM-X810) / Android 16 / One UI 8

## 2. 확정된 사실 (전부 실기기 실측)

| 사실 | 근거 |
|---|---|
| 스위치는 `Settings.System.pass_through` (`0`/`1`) | [06](research/06-test-results.md) |
| `1` 이면 진짜 바이패스. 8코어 포화 부하(1527 mA)에서도 배터리 전류 ≈ 0 | [09](research/09-final-results.md) |
| 게임런처 등록도 포그라운드 조건도 불필요. 포그라운드가 무엇이든 걸린다 | [09](research/09-final-results.md) |
| 쓰기에는 **shell UID 가 필요**. `WRITE_SETTINGS` 로는 불가 (AOSP 비공개 키 보호) | [11](research/11-write-path.md) |
| **배터리 20% 이상**에서만 효과. 미만이면 값은 남되 무시되고 정상 충전 | [09](research/09-final-results.md) |
| **재부팅하면 `0` 으로 초기화**된다 | [09](research/09-final-results.md) |
| 값을 덮는 유일한 주체는 `com.samsung.android.game.gametools`, **게임의 포그라운드 전이 때만** | [12](research/12-trigger-matrix.md) |
| 화면 끔/켬·잠금화면·앱 전환·홈·최근앱·알림창은 **전부 무해** | [12](research/12-trigger-matrix.md) |
| 효과 수렴에 10~20초 (20% 문턱 근처에서는 4~5분) | [09](research/09-final-results.md) |
| **화면을 끄고 10시간을 두어도 유지된다. 그동안 트리클 충전조차 없다** | [FIELD](FIELD.md) |
| 유휴 바이패스의 표류는 약 20 mA — 밴드 10%p 를 내려오는 데 약 50시간 | [FIELD](FIELD.md) |
| PD/PPS 고속 충전 협상이 전제. 소프트웨어로 우회 불가 | [01](research/01-feature-mechanics.md) |

내부 동작 체인:
```
쓰기 주체  →  Settings.System.pass_through  →  BatteryService(system_server)
                                              →  /sys/class/power_supply/battery/pass_through
```

## 3. 아키텍처 — 자체 ADB + shell 에이전트

> **이 절은 초기 설계이고, 구현하며 한 걸음 더 갔다.** ADB 는 이제 **부팅 후 한 번**,
> shell UID 로 도는 작은 에이전트를 띄우는 데에만 쓴다. 정책 판단도 그 에이전트가 한다
> (`dumpsys battery` 를 읽을 수 있으므로). 그래서 앱은 백그라운드에서 돌 필요가 없고,
> 알람·포그라운드 서비스·배터리 최적화 안내가 전부 불필요해졌다.
> **[AGENT.md](AGENT.md) 를 함께 읽을 것** — 그 구조와, 만들며 밟은 함정 다섯 개가 있다.

## 3.1 원래의 자체 ADB 설계

**`~/Projects/lan-mouse-android` 의 `app/src/main/java/moe/qwreey/lanmouse/adb/` 를 이식한다.**

```
AdbClient.kt   AdbPairingClient.kt   AdbKey.kt
AdbMdns.kt     AdbBootstrap.kt       SelfSignedCertificate.kt
```

앱이 자기 기기의 adbd 에 루프백으로 붙어 **명령 한 줄 실행하고 끊는다.** 같은 기기에서 검증된 코드다.

### 왜 다른 방법이 아닌가 ([11](research/11-write-path.md))

| 대안 | 탈락 사유 |
|---|---|
| `WRITE_SETTINGS` 로 직접 쓰기 | AOSP 가 비공개 `Settings.System` 키 쓰기를 차단. 권한 문제가 아님 |
| `WRITE_SECURE_SETTINGS` | `system` 네임스페이스 관할이 아님 |
| targetSdk ≤ 22 헬퍼 APK | 설치에 adb 필요 + 취약 |
| **Shizuku** | 동작은 하나 **"Start on boot" 이 root 전용.** `pass_through` 는 재부팅마다 초기화되므로, **복구가 필요한 바로 그 시점에 Shizuku 가 죽어 있다** |

### 부트스트랩

최초 1회만 데스크탑에서 (앱이 스스로에게 줄 수 없는 권한이라 이것만은 불가피하다):
```sh
adb shell pm grant moe.qwreey.powerdirect android.permission.WRITE_SECURE_SETTINGS
```

그 뒤로 앱이 스스로 하는 것:

1. 무선 디버깅 활성화
2. **`adb_allowed_connection_time = 0` 을 직접 설정한다** — 페어링 만료 차단.
   AOSP 기본값은 7일(`604800000`)이다.
   **다른 앱(lan-mouse-android 등)이 이미 설정해뒀을 것이라고 가정하지 말 것.**
   그 앱을 지우거나 기기를 바꾸면 깨진다. 시작할 때마다 현재 값을 읽어 확인하고,
   0이 아니면 쓴다 (멱등).
3. mDNS 로 adbd 포트 탐색 — **포트는 재부팅마다 바뀐다** (실측: 42027 → 42451).
   `AdbMdns.kt` 가 처리한다.
4. 루프백 접속 → 명령 한 줄 → 즉시 해제

## 4. 정책

```
충전기 연결 중:
    level <  LOW      →  pass_through = 0     (충전 허용)
    level >= HIGH     →  pass_through = 1     (바이패스)
    LOW <= level < HIGH →  현재 상태 유지      (히스테리시스)

충전기 미연결:
    아무것도 하지 않는다
```

기본값 **LOW = 70, HIGH = 80** (사용자 조절 가능).

- 밴드 폭 10%p 면 플래핑이 없다. 70→80 충전은 약 13분 (1009 mAh / 4.6 A).
  반대 방향은 약 20 mA 로 표류하므로 **약 50시간** 걸린다 — 밴드는 심하게 비대칭이고,
  그래서 실사용에서 플래핑은 일어날 수가 없다 ([FIELD.md](FIELD.md))
- **안전 바닥을 앱에 넣을 필요는 없다.** 20% 미만에서는 펌웨어가 플래그를 무시하고 정상 충전한다
- 삼성 **배터리 보호**(설정 > 배터리, 80% 상한)를 같이 켜두면 이중 안전장치가 된다.
  계층이 달라 충돌하지 않는다 (`mProtectionThreshold: 80`)

### 모드

| 모드 | 동작 |
|---|---|
| **자동** | 위 밴드 정책 |
| **강제** | 잔량 무시하고 `1` 유지 |
| **끔** | `0` 유지 |

## 5. 이벤트 배선

| 트리거 | 등록 | 하는 일 |
|---|---|---|
| `BOOT_COMPLETED` | 매니페스트 | 충전 중이면 정책 평가 (**필수** — 부팅 시 초기화됨) |
| `ACTION_POWER_CONNECTED` | 매니페스트 (암시적 브로드캐스트 면제 목록) | 포그라운드 서비스 시작 |
| `ACTION_POWER_DISCONNECTED` | 매니페스트 | 서비스 종료 |
| `ACTION_BATTERY_CHANGED` | **런타임만 가능** | 서비스가 구독. 잔량·전압·온도 변화마다 정책 평가 |

**`ACTION_SCREEN_ON` 은 필요 없다** ([12](research/12-trigger-matrix.md)).

게임 세션 종료가 유일한 외부 간섭인데, `BATTERY_CHANGED` 가 주기적으로 오므로 그때 값을 확인해
재적용하면 자연히 복구된다. **게임 감지를 위해 `UsageStatsManager` 를 붙일 필요가 없다.**

알림은 **충전 중에만** 뜬다 — 기능이 의미 있는 구간과 정확히 일치하고,
"왜 충전이 안 되지?" 사고를 막는 안내 역할을 겸한다.

## 6. 관측

- **전류**: `BatteryManager.getIntProperty(BATTERY_PROPERTY_CURRENT_NOW)` — **권한 불필요**.
  셸에서는 sysfs 가 막혀 못 읽는 값을 앱은 볼 수 있다
- 잔량: `BATTERY_PROPERTY_CAPACITY`
- 온도·전압: `ACTION_BATTERY_CHANGED` 의 extras
- **효과 판정에 짧은 창을 쓰지 말 것.** 수렴에 최소 20초, 문턱 근처에서는 4~5분 걸린다

## 7. UI

| 요소 | 내용 |
|---|---|
| 메인 화면 | 모드 선택, LOW/HIGH 슬라이더, 현재 잔량·전류·온도, 바이패스 활성 여부 |
| 퀵세팅 타일 | 탭 = 자동↔끔, 롱탭 = 앱 열기 (`BypassTileService.kt`) |
| 홈 위젯 | 잔량·전류·상태·모드, 탭 = 자동↔끔 (`StatusWidget.kt`) |
| 알림 (충전 중) | 상태 + 즉시 끄기 액션 |

`lan-mouse-android` 의 `AgentTileService.kt` · `AgentWidget.kt` · `BootReceiver.kt` 패턴 재사용.

타일과 위젯은 `SurfaceState.kt` 하나를 공유한다 — 상태를 읽는 것도 모드를 뒤집는 것도
거기 한 곳에만 있다. 나눠 두면 "타일은 바이패스 중이라는데 위젯은 충전 중" 이 생기고,
그때 어느 쪽이 맞는지 알아낼 방법이 없다.

**타일의 on/off 는 사용자가 켜 둔 것이지 지금 일어나는 일이 아니다.** 자동 모드에서 잔량이
밴드 아래면 켜 둔 채로 정상 충전하는 것이 정상인데, 그때 타일을 꺼진 것으로 그리면 사용자가
한 번 더 눌러 진짜로 꺼버린다. 실제 상태는 부제(`Bypassing · 80%`)가 말한다.
자세한 것은 [../HANDOFF.md](../HANDOFF.md) 의 "표면".

## 8. 안전

- `pass_through` 는 영속 설정이지만 **재부팅하면 초기화**되므로, 앱이 사라져도 자가 치유된다.
  복구 안내는 **"재부팅하세요"** 로 충분하다
- 앱에 "끄고 나가기" 버튼 (값을 `0` 으로 되돌리고 종료)
- 20% 미만에서는 펌웨어가 알아서 충전한다 — 방전으로 못 빠져나오는 상황은 없다

## 9. 고지 ([../README.md](../README.md) 의 "Read this first" 에 실려 있다)

> 목표는 배터리 수명을 조금이라도 늘리는 것이지 충전을 완벽히 차단하는 게 아니다.
> · 완전 바이패스가 아닐 수 있다 (다른 기기에서 30~70 mA 잔류 보고가 있다. 이 기기는 −16 mA)
> · PPS 충전기·20% 이상 같은 펌웨어 조건은 우회 대상이 아니다
> · 공식 지원 기능이 아니므로 One UI 업데이트로 막힐 수 있다
> · 효과가 정량적으로 얼마나 되는지는 아무도 측정한 적이 없다

## 10. 단계

| 단계 | 내용 |
|---|---|
| 1 | ✅ 프로젝트 생성 + `adb/` 패키지 이식. `settings put system pass_through 1` 한 줄 실행 확인 (**깨끗한 기기 상태 — `adb_allowed_connection_time` 을 기본값으로 되돌린 뒤 — 에서도 되는지 확인할 것**) |
| 2 | ✅ 수동 토글 + 전류 표시 + 퀵세팅 타일 |
| 3 | ✅ 밴드 정책 + 이벤트 배선 (`BOOT_COMPLETED`, `POWER_CONNECTED`). 포그라운드 서비스는 필요 없어졌다 — [AGENT.md](AGENT.md) |
| 4 | ✅ 위젯, 설정 UI |
| 5 | 장기 관측 로그 — 전류·온도·잔량을 남겨 실제 수명 효과 확인 |

## 11. 남은 미확인

- **`low` 에 닿았을 때 충전이 실제로 재개되는가.** 짧은 창에서 가역성은 확인됐지만
  ([09](research/09-final-results.md)), 장시간 표류 끝에 자연스럽게 밟는 경로로는 아직 못 봤다.
  아래쪽 변이 약 50시간이라 관측에 시간이 걸린다 ([FIELD.md](FIELD.md))
- 장시간 상시 바이패스의 **발열** — 거동 자체는 10시간 확인됨 ([FIELD.md](FIELD.md))
- 80% **초과** 고잔량에서의 거동 (측정 구간이 20~39% 에서 80% 근방까지 넓어졌다)
- 다른 기기/One UI 버전에서의 재현성
