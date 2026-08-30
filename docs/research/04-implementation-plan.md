# 구현 계획

전제는 전부 검증됐다 ([09](09-final-results.md)). 이제 만들기만 하면 된다.

## 확정된 사실이 설계에 주는 것

| 검증된 사실 | 설계 함의 |
|---|---|
| `settings put system pass_through 1` 한 줄로 바이패스가 걸린다 | 앱이 할 일은 이 한 줄이 전부다 |
| 게임런처 등록·재분류 불필요, 포그라운드 무관 | **오버레이·팝업창·가짜 게임 앱 전부 불필요** |
| 게임 부스터는 폴링하지 않는다 | 재적용 주기를 짧게 잡을 이유가 없다 |
| 값을 되돌리는 유일한 주체 = 인식된 게임의 포그라운드 전이 | **게임을 실행/종료했을 때만 복구가 필요하다** |
| 전환에 10~20초 걸린다 | 효과 판정 전 대기 필요 |
| `dumpsys battery`의 `current now`(mA)를 셸에서 읽을 수 있다 | 검증·로깅이 쉽다. 앱에선 `BatteryManager`로 더 정확히 |

## 채택 구조 — `lan-mouse-android`의 자체 ADB 재사용

`~/Projects/lan-mouse-android` 가 이미 갖고 있는 것:

```
app/src/main/java/moe/qwreey/lanmouse/adb/
  AdbClient.kt          ADB 와이어 프로토콜
  AdbPairingClient.kt   무선 디버깅 SPAKE2 페어링
  AdbKey.kt             키 생성/보관
  AdbMdns.kt            adbd 포트 mDNS 탐색
  AdbBootstrap.kt       무선 디버깅 자동 활성화
  SelfSignedCertificate.kt
```

앱이 자기 기기의 adbd에 루프백으로 붙어 **명령 한 줄 실행하고 끊는다.**
이미 같은 기기(Tab S9+ / Android 16)에서 검증된 코드다.

**왜 자체 ADB인가**: 명령이 shell UID로 돈다. `Settings.System` 쓰기는 앱에게
`WRITE_SETTINGS`(사용자가 UI에서 주는 특수 권한)를 요구하고, One UI 6+ 에서는 `pm grant` 로 준
권한이 *앱 정보 > ⋮ > 제한된 설정 허용* 을 눌러야 먹는 함정도 있다. 자체 ADB는 이걸 통째로 우회한다.

> **대안들은 전부 탈락했다 ([11](11-write-path.md) 참조).**
> `WRITE_SETTINGS` 는 AOSP의 `Settings.System` 비공개 키 보호에 걸려 **권한과 무관하게 불가**.
> Shizuku 는 동작은 하지만 "Start on boot" 가 root 전용이라, **재부팅 직후 — 정확히 복구가
> 필요한 그 시점에 — 죽어 있다.** `pass_through` 가 재부팅마다 초기화되므로 치명적이다.
> 자체 ADB 만이 재부팅 후 자동 복구가 되는 유일한 구조다.

## 앱 스케치 — `samsung-power-direct`

한 화면짜리 앱. 하는 일:

1. **적용**: `pass_through=1` 쓰기 (자체 ADB 또는 `WRITE_SETTINGS`)
2. **관측**: `BatteryManager.BATTERY_PROPERTY_CURRENT_NOW` 로 실제 전류 표시
   (권한 불필요. 셸에서 막힌 sysfs를 앱은 볼 수 있다)
3. **유지**: 아래 이벤트에 재적용
   - 충전기 연결 (`ACTION_POWER_CONNECTED`)
   - 화면 켜짐 (`ACTION_SCREEN_ON`)
   - **게임 세션 종료 감지** — 실질적으로 이것만이 값을 되돌린다.
     `UsageStatsManager` 로 포그라운드 앱 변화를 보거나, 그냥 주기 재적용으로 갈음
   - 안전망: `AlarmManager` 30분 주기
4. **끄기**: 타일(Quick Settings) + 알림에서 수동 on/off, 앱 종료 시 정책 선택

**충전기 미연결이면 아무것도 하지 않는다** — `BatteryManager` 로 앱 권한만으로 확인 가능하니
ADB 접속 자체를 스킵한다. 배터리 낭비가 없다.

`lan-mouse-android` 에 얹지 않고 **별도 앱**으로 간다. 공통점이 자체 ADB 클라이언트뿐이고
수명주기가 완전히 다르다 (상시 데몬 vs 가끔 한 줄). `adb/` 패키지만 복사하거나 공용 모듈로 뽑는다.

## 단계

| 단계 | 내용 |
|---|---|
| 1 | `lan-mouse-android` 의 `adb/` 패키지를 새 프로젝트로 이식, 명령 한 줄 실행까지 확인 |
| 2 | 최소 앱: 수동 토글 + 현재 전류 표시(`BatteryManager`) + 퀵세팅 타일 |
| 3 | 밴드 정책 ([10](10-policy-design.md)) — `POWER_CONNECTED` 로 포그라운드 서비스, `BOOT_COMPLETED` 복구 |
| 4 | 장기 관측 로그 — 전류·온도·잔량을 남겨서 실제 수명 효과를 확인 |

## 남은 확인 사항

- ~~재부팅 후 값 유지~~ → **초기화된다. `BOOT_COMPLETED` 필수** (09)
- ~~`WRITE_SETTINGS` 로 가능한가~~ → **불가 확정** (11)
- 20% 미만에서 펌웨어가 자동 해제되는지 — **측정 중**
- 배터리 80%+ 고잔량에서의 거동 (이번 측정은 37~39%)
- 장시간 상시 바이패스의 발열·안전성

## 위험

- 공식 지원 기능이 아니다. One UI 업데이트로 막힐 수 있다.
- 삼성이 화면 꺼짐에 자동 해제하도록 만든 데는 이유가 있을 수 있다(발열·안전).
  상시 바이패스는 문서화되지 않은 영역이다. 4단계 관측이 그래서 필요하다.
- README「목적과 인정된 한계」 참조 — 이 프로젝트는 완벽한 차단이 아니라
  **배터리 수명을 조금이라도 늘리는 것**이 목표다.
