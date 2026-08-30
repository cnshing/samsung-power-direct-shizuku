# 쓰기 경로의 정체 — 왜 앱 UID로는 안 되는가

2026-08-30, logcat 실측으로 확정.

## 1. 전체 체인이 드러났다

게임(Roblox) 진입 시 logcat:

```
D SettingsProvider: PUT_ret(/system/pass_through), callingPackage:com.samsung.android.game.gametools
I GameTools: DreamToolsStatus: [alarmlck,gestlck,brightlck,bixbylck,edgelck,tempctl,asl,
                                navilock,rec,recfin,bixbyatt,priority,PASSTHRU]
D BatteryService: PassThrough Settings = true
D [SS]BattUtils: [writeNode]path:/sys/class/power_supply/battery/pass_through ,data:1
D BatteryService: success to set PassThrough sysfs as true
```

```
com.samsung.android.game.gametools          (게임 부스터 오버레이)
        │  Settings.System.pass_through = 1
        ▼
BatteryService  (system_server, pid 2754)   ← 설정 옵저버
        │  "PassThrough Settings = true"
        ▼
/sys/class/power_supply/battery/pass_through = 1
```

### 여기서 확정되는 것

1. **`pass_through` 를 쓰는 것은 `com.samsung.android.game.gametools` 다.** GOS가 아니다.
   (GOS는 같은 타이밍에 `/secure/allow_more_heat_value` 를 쓴다 — 발열 허용치. 별개 기능)
2. **`BatteryService` 가 설정을 감시하다가 sysfs로 그대로 미러링한다.**
   중간에 "게임이 포그라운드인가" 같은 추가 검사가 **없다.**
   → 09번에서 실측으로 확인한 "게임 없이도 걸린다"가 왜 그런지 코드 수준에서 설명된다.
3. **`/sys/class/power_supply/battery/pass_through` 라는 sysfs 노드가 존재한다.**
   03번 조사에서 공개된 커널 소스(Note10/Tab S6 세대)에는 없던 노드다.
   S23 세대에 추가된 것으로 보인다. 물론 shell에서는 쓰기도 읽기도 막혀 있다.
4. AOSP의 `GameManagerService` 는 별개로 동작한다
   (`V GameManagerService: Game power mode ON (only games in foreground)`).
   03번에서 "삼성 기능과 무관할 것"이라 한 추정이 맞았다.

## 2. 앱 UID가 거부되는 이유 — 권한 문제가 아니다

SetEdit(`WRITE_SETTINGS` 보유, `WRITE_SECURE_SETTINGS` 까지 `pm grant`)의 실패 로그:

```
D SecContentProvider: called from by4a.setedit22
D RestrictionPolicy: isSettingsChangesAllowedAsUser, userId 0 : true      ← 정책상 허용됨
E DatabaseUtils: java.lang.IllegalArgumentException:
                 You cannot keep your settings in the secure settings.
  at SettingsProvider.warnOrThrowForUndesiredSecureSettingsMutationForTargetSdk(:3710)
  at SettingsProvider.enforceRestrictedSystemSettingsMutationForCallingPackage(:3296)
  at SettingsProvider.mutateSystemSetting(:2992)
```

**이건 삼성의 패키지 allowlist가 아니라 AOSP의 일반 규칙이다.**

`RestrictionPolicy` 는 `true`(허용)를 반환했다. 즉 삼성의 정책 레이어는 통과했고,
막은 것은 **AOSP `SettingsProvider` 의 `Settings.System` 비공개 키 보호**다.

`Settings.System` 에는 공개 키 목록(`PUBLIC_SETTINGS`)이 있고, 거기 없는 키를 앱이 쓰려 하면
`enforceRestrictedSystemSettingsMutationForCallingPackage` 가 걸러낸다.
`pass_through` 는 삼성이 추가한 비공개 키라 당연히 목록에 없다.

**→ `WRITE_SETTINGS` 를 아무리 줘도 이 경로는 절대 열리지 않는다.** 권한의 문제가 아니다.

### 다만 메서드 이름에 단서가 있다

`warnOrThrowFor...**ForTargetSdk**` — AOSP 구현은 호출자의 `targetSdkVersion` 을 본다.
**Lollipop MR1(API 22) 이하를 타겟하는 앱에는 경고만 하고 통과시킨다.**

이게 03번 조사에서 나왔던 `SBatteryTweaks` 의 수수께끼를 설명한다:

```
adb install --bypass-low-target-sdk-block settings-database-provider-v1.1-cli.apk
```

그 헬퍼 APK는 **targetSdk ≤ 22 짜리 설정 쓰기 프록시**다. Android 14+ 는 낮은 targetSdk 앱의
설치를 막으므로 `--bypass-low-target-sdk-block` 이 필요했던 것이다.

**우리는 이 경로를 쓰지 않는다.** 어차피 설치에 adb가 한 번 필요하고, 구조가 취약하며,
자체 ADB 경로가 더 깨끗하다. 다만 왜 그런 APK가 존재하는지는 이제 설명된다.
(검증하지는 않았다. AOSP 소스와 스택 트레이스로부터의 추론이다.)

## 3. Shizuku (rish) 로는 된다 — 하지만 채택하지 않는다

```sh
$ ./rish -c 'settings put system pass_through 1; settings get system pass_through'
1
$ adb shell settings get system pass_through     # 노트북에서 재확인
1
```

**기기 위에서 도는 shell UID 브리지로 이 값을 쓸 수 있음이 확인됐다.**
데스크탑 adb 없이 앱이 자립 가능하다는 증명이다.

### 그런데 Shizuku는 부적합하다

- **Shizuku의 "Start on boot" 는 root 전용이다.** 비루팅 기기에서는 재부팅할 때마다
  사용자가 무선 디버깅 페어링을 손으로 통과시켜야 한다.
- 그런데 **`pass_through` 는 재부팅마다 0으로 초기화된다** (09 참조).
  즉 **재부팅 직후가 정확히 복구가 필요한 시점인데, 바로 그때 Shizuku가 죽어 있다.**
- Shizuku는 `Shizuku.newProcess` 도 private/deprecated라 지속 실행의 토대로 쓰기 어렵다
  (lan-mouse-android가 KeyMapper의 `sysbridge/` 원칙을 따라 Shizuku를 안 쓴 이유와 동일).

**→ `lan-mouse-android` 방식(앱이 자기 adbd에 붙어 명령 한 줄 실행)이 유일한 자립 경로다.**
그 앱은 `WRITE_SECURE_SETTINGS` 로 무선 디버깅을 스스로 켜고
`adb_allowed_connection_time = 0` 으로 페어링 만료를 막아 재부팅 후에도 자동 복구한다.

## 결론 — 아키텍처 확정

| 경로 | 판정 |
|---|---|
| 앱 UID + `WRITE_SETTINGS` | **불가.** AOSP 비공개 키 보호. 권한으로 해결 안 됨 |
| 앱 UID + `WRITE_SECURE_SETTINGS` | **불가.** 애초에 `system` 네임스페이스 관할이 아님 |
| targetSdk ≤ 22 헬퍼 APK | 이론상 가능하나 설치에 adb 필요, 취약. 채택 안 함 |
| Shizuku | **동작 확인됨.** 하지만 재부팅 후 자동 복구 불가 → 채택 안 함 |
| **자체 ADB (lan-mouse 방식)** | **채택.** 유일하게 재부팅 후 자동 복구가 되는 구조 |
| 루팅 | 논외 |
