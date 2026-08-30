# ADB / 저수준 트리거 가능성

조사일: 2026-08-29. 비루팅 Galaxy 태블릿 기준.

> **⚠ 이 문서는 실측 이전의 웹 조사 기록이다. 아래 항목들은 이후 실기기에서 뒤집혔다.**
>
> | 이 문서의 서술 | 실측 결과 |
> |---|---|
> | `pass_through` 는 XDA 단일 출처, 미검증 | **확정.** 게임 부스터 토글 = 이 값 ([06](06-test-results.md)) |
> | sysfs 배터리 노드는 **읽기 가능** | **읽기도 차단됨.** One UI 8 ([06](06-test-results.md)) |
> | 게임런처 프로바이더에 insert 가능할지도 | **읽기부터 권한 거부** (uid=2000) ([06](06-test-results.md)) |
> | 공개 커널 소스에 게임 바이패스 플래그 없음 | `/sys/class/power_supply/battery/pass_through` **노드가 존재한다** ([11](11-write-path.md)) |
> | 쓰는 주체는 GOS 로 추정 | **`com.samsung.android.game.gametools` 다** ([11](11-write-path.md)) |
> | `dumpsys battery` 로는 전류를 못 본다 | **`current now` 필드가 있다** ([09](09-final-results.md)) |
>
> 최종 사실은 [09](09-final-results.md) · [11](11-write-path.md) · [12](12-trigger-matrix.md) 를 보라.

## 결론

| 경로 | 판정 |
|---|---|
| `settings put system pass_through 1` | **유일한 실마리. 단일 출처. 미검증.** → 05에서 실측 |
| sysfs 직접 쓰기 (`store_mode`, `batt_slate_mode` 등) | **비루팅 불가**. AOSP `shell.te`에 write 룰 없음 |
| sysfs 읽기 (`current_now`, `status`, `direct_charging_status`) | **가능**. 검증 수단으로 쓴다 |
| `cmd game mode performance <pkg>` (AOSP) | 명령은 되지만 **삼성 기능과 무관할 가능성 높음** |
| GOS에 IPC로 "게임 시작" 알리기 | 공개 문서 없음. 플랫폼 서명 권한 필요할 것 |
| `dumpsys battery unplug` 류 | **완전 무관**. 앱에 보고되는 값만 속임, 실제 충전 경로는 그대로 |

## 1. `pass_through` — 유일한 실마리

출처: XDA [*"Bypass battery while charging/gaming"*](https://xdaforums.com/t/bypass-battery-while-charging-gaming.4550059/)
스레드의 사용자 "skritland" (S22 Ultra / S23 계열).

```sh
adb shell settings get system pass_through
adb shell settings put system pass_through 1   # 켜기
adb shell settings put system pass_through 0   # 끄기
```

- `settings put system`은 **`adb shell`에서 권한 문제 없이 실행된다.** shell UID가 원래 쓸 수 있다.
  즉 "명령이 에러 없이 돌아간다"는 건 보장되고, **문제는 프레임워크가 이 키를 실제로 읽느냐**다.
- Tasker + Shizuku로 자동화했다는 보고도 같은 스레드.
- 별개 프로젝트 [`pascua28/SBatteryTweaks`](https://github.com/pascua28/SBatteryTweaks)가 이런
  충전 제어 설정들을 온도/스케줄 기반으로 자동으로 뒤집는다. 그 repo
  [이슈 #5](https://github.com/pascua28/SBatteryTweaks/issues/5)에서 `pass_through`가 "원래는
  게임에만 열려 있는 값"이라고 언급된다.

**주의 — 두 탐사자가 독립적으로 이 키에 도달했지만, 추적해보면 출처가 같은 XDA 스레드 하나다.
독립 확인이 아니다.** 스레드 안에서도 "실제로 충전 거동이 바뀌더라"는 2차 확인 댓글은 없다.

### One UI 6+ 앱에서 쓸 때의 걸림돌
`Settings.System` 쓰기는 앱에겐 `WRITE_SETTINGS`(사용자가 UI에서 주는 특수 권한)가 필요하고,
삼성이 특정 키를 추가로 막았을 수 있다. SBatteryTweaks는 이 때문에 Shizuku나
`settings-database-provider` 헬퍼 APK를 쓴다. One UI 6+에서는 `pm grant`로 준 권한이
**앱 정보 > ⋮ > 제한된 설정 허용** 을 눌러야 실제로 먹는 One UI 특유의 함정도 있다.

→ **우리는 이 문제를 안 겪는다.** 04의 자체 ADB 방식은 shell UID로 명령을 실행하므로
`WRITE_SETTINGS`도 Shizuku도 필요 없다.

## 2. sysfs — 읽기만 된다

삼성 충전 스택은 GPL로 공개된 `sec_battery` / `battery_v2` 커널 드라이버다.
`/sys/class/power_supply/battery/` 아래에 attribute들이 노출된다
([sec_battery_sysfs.h](https://github.com/ianmacd/d2s/blob/master/drivers/battery_v2/include/sec_battery_sysfs.h)):

`batt_slate_mode`, `store_mode`, `batt_current_event`, `direct_charging_status`,
`direct_charging_step`, `direct_charging_iin`, `siop_level`, `wc_enable`, `factory_mode` …

- 파일 모드는 `0664`지만 그게 끝이 아니다. AOSP
  [`shell.te`](https://android.googlesource.com/platform/system/sepolicy/+/ae46511bfa62b56938b3df824bb2ee737dceaa7a/shell.te)
  는 shell 도메인에 `sysfs_batteryinfo:file r_file_perms` — **읽기만** 준다. 쓰기 룰이 없다.
- 삼성 자체 sepolicy는 비공개라 확답은 못 하지만, 프로덕션 빌드에서 열어뒀을 이유가 없다.
- **"게임 바이패스"에 해당하는 명시적 플래그를 공개된 드라이버 소스에서 찾지 못했다.** 공개된 트리가
  Note10/Tab S6 세대라 S23 시대 기능보다 오래됐기 때문. 구조상 `SEC_BAT_CURRENT_EVENT_SELECT_PDO`
  + APDO(PPS) 재협상 쪽일 가능성이 있다 — 바이패스는 결국 "충전용 PDO 대신 시스템 레일용 프로파일을
  PPS로 재협상"하는 문제지 단순 on/off 스위치가 아니다. **추정.**

이 구조가 맞다면 중요한 함의가 있다: **PPS 충전기 요구조건은 소프트웨어로 못 넘는다.**

## 3. 게임런처 게임 목록 읽기

콘텐트 프로바이더가 있다
([SamsungLauncher 재구현체](https://github.com/fonix232/SamsungLauncher/blob/master/app/src/main/java/com/android/launcher3/gamehome/GameHomeManager.java)에서 확인):

```sh
adb shell content query --uri content://com.samsung.android.game.gamehome.data/applist
```

읽기는 될 가능성이 높다. **쓰기(insert)가 되는지는 미확인** — 된다면 Game Booster+ 없이도
임의 패키지를 게임 목록에 넣을 수 있다는 뜻이라 시도 가치가 있다.

관련 `Settings.Secure` 키: `game_home_enable`, `game_app_hidden_enable`.

## 4. 안 되는 것들 (시간 낭비 방지)

- **`dumpsys battery unplug` / `set status 2`**: `BatteryManager`가 앱에게 보고하는 값만 조작한다.
  PMIC/충전 IC/커널 드라이버는 전혀 안 건드린다. 실제로는 계속 충전된다. 바이패스와 무관.
- **`cmd game mode performance <pkg>`**: 명령 자체는 Android 12+ 표준이고 비루팅으로 된다. 하지만
  삼성 게임 부스터 스택(2019~)은 AOSP Game Mode API(2021)보다 먼저 나왔고 완전히 별개 서브시스템이다.
  삼성 문서 어디에도 Game Mode API 연동 언급이 없다. **효과 없을 것으로 본다** (그래도 5초면 테스트 가능).
- **`android:appCategory="game"` 매니페스트**: AOSP Game Mode 레이어에는 먹지만, 삼성 게임런처가
  이걸 읽는다는 증거가 없다. 삼성은 자체 패키지명 allowlist + 스토어 카테고리 + OpenGL 사용 휴리스틱을
  쓰는 것으로 보인다 ([개발자 사례 gist](https://gist.github.com/Adnan-Bacic/718eb3b4e70380696c91dc21c4804112) —
  평범한 테스트 앱이 applicationId 문자열만으로 게임으로 오분류됨).
- **`GameManager.setGameMode()`**: `MANAGE_GAME_MODE` 권한은 시스템 앱 전용. 서드파티는 `getGameMode()`만.
- **`galaxy-bypass-utility` 계열 GitHub repo들**: 내용을 열어보면 stock platform-tools에 README만
  붙인 SEO 스팸에 가깝다. **신뢰하지 말 것.**
- **`pm disable-user com.samsung.android.game.gos`**: GOS 스로틀링을 끄는 것. 바이패스와 직교. 무관.
