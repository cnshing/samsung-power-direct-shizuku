# Reddit 댓글 검증 — "게임 부스터+에 그 옵션이 있다"

> **⚠ 이 경로는 결국 불필요해졌다.** `pass_through` 를 직접 쓰면 포그라운드가 무엇이든
> 바이패스가 걸린다는 것이 실측으로 확인됐다 ([09](09-final-results.md)).
> Game Booster+ 재분류는 시도할 필요가 없다. 아래는 조사 기록으로 남긴다.

조사일: 2026-08-29
원문: https://www.reddit.com/r/samsunggalaxy/comments/1is1fwb/comment/ngahnj5/

## 판정: **부분적으로 사실 — 그러나 실용적으로는 "사실"에 가깝다**

전용 "모든 앱에 적용" 토글이 새로 생긴 게 아니다. **아무 앱이나 수동으로 "게임"으로 재분류하는
기능**이 굿락 모듈로 생겼고, 그러면 그 앱에 게임 부스터 옵션 전체(우회충전 포함)가 붙는다.

## 원문 댓글 (verbatim)

원글 (u/Radzaarty): *"Just remember, your phone has to be set to fast charge and in something that
counts as a game for it to work. It would be great if they brought it as a feature for use with
other apps not considered a game like maps."*

- u/Overall_Amount_2078: *"If only you could add your own games to enable this feature and not rely
  on what Samsung considers a game or not."*
- **u/RushBIstTheBest (= 댓글 `ngahnj5`)**: *"sorry for necroing this thread but samsung's heard our
  wishes. the option is in game booster+"*
- u/Overall_Amount_2078 (답글): *"Yes a while ago. **Unfortunately not all apps**, but if you
  sideload them you can enable it 😀"*
- u/Few-Palpitation1900: *"Install game booster plus from Goodlock. It adds the functionality"*

## 이름이 같은 서로 다른 두 앱 — 헷갈리지 말 것

| 표기 | 패키지 | 정체 |
|---|---|---|
| Game Booster Plus (구형) | `com.samsung.android.game.gameboosterplus` | Note9/S10 시절 "게임 플러그인" 계열의 갤럭시 스토어 플러그인. 성능 커스터마이징용. **이게 아니다.** |
| **Game Booster + (현행)** | **`com.samsung.android.game.honeyplayplus`** | **굿락(Good Lock) 모듈.** 2025-03 한국 우선 출시, One UI 7 / Android 15 기준. 이게 Reddit이 말하는 것. |

참고로 같은 생태계의 다른 패키지들: `com.samsung.android.game.gamehome`(게임런처),
`com.samsung.android.game.gos`(GOS), `com.samsung.android.game.gametools`(게임 부스터 오버레이),
`com.samsung.android.game.gametuner.thin`.

## 핵심 기능: "게임 카테고리 설정"

Game Booster+ 안의 **게임 카테고리 설정(Game category settings)** — *"게임으로 분류되지 않은 앱을
게임으로 설정"*. 이걸로 아무 앱이나 시스템 레벨에서 게임 취급하게 만들 수 있다.

- [GSMArena](https://m.gsmarena.com/samsung_launches_game_booster_with_new_features-news-66964.php):
  *"the Game Booster+ finally allows you to add apps to the list and classify them as 'games'"*
- 삼성 공식 경고 문구: 그렇게 하면 해당 앱이 **오작동할 수 있음**.

## 실사용 확인 사례 (가장 중요한 증거)

r/Tech_Philippines, [*"Is 'Pause USB PD Charging' the most underrated Samsung feature..."*](https://www.reddit.com/r/Tech_Philippines/comments/1qao1rd/is_pause_usb_pd_charging_the_most_underrated/)

u/xShinRai024x: *"You can use it din sa other app if you download good lock module na **Game
Booster+**. You can make an App treated as a game to be able to use **Pause USB PD charging**. Very
useful lalo na when using your phone as Navigation tool. (GMaps, Waze, etc.)."*

→ **구글 지도/Waze를 게임으로 재분류해서 우회충전을 실제로 쓰고 있다는 1인칭 보고.** 이게 이번
조사에서 확인된 유일한 "실제로 되더라" 증언이다.

같은 스레드의 다른 갈래 (u/BonusWorking1375) — **검증 안 된 별개 주장**:
*"Just use the 'Modes (Gaming)' feature. You just need to manually add the apps you want to use
while that mode is active."* → 설정 > 모드 및 루틴 > 게임 모드 얘기. 삼성 문서로 교차확인 못 함.
**시도해볼 가치는 있음** (모듈 설치 없이 되는 경로라면 제일 깔끔하다).

## 한계 / 불확실한 것

- *"not all apps"* — 어떤 앱은 재분류가 안 되고, 사이드로드하면 된다는 언급. **정확한 제약 불명.**
  Play 설치 앱 vs 사이드로드 앱 구분, 서명/Play Integrity 관련일 가능성.
- 초기 한국 한정 → 이후 확대. **One UI 8에서의 상태 미확인.**
- **삼성 공식 문서는 여전히 "게임 전용"이라고만 쓴다.** 커뮤니티가 찾아낸 우회로이고 공식 지원이 아님.
  → OS 업데이트로 막힐 수 있다.
- 2023년 삼성 멤버스 공식 답변(Game Booster+ 이전): "Add apps to Game Launcher"에 대해
  *"No it will not work with apps."* → **소 게임런처 수동 추가만으로는 안 된다**는 것은 확정.
  Game Booster+ 모듈이 정확히 그 간극을 메우려고 나온 것.

## 그래도 남는 문제

Game Booster+로 재분류에 성공해도 **01-feature-mechanics.md의 발동 조건은 그대로 살아있다**:
PPS 25W+ 충전기, 배터리 20%+, 그리고 **해당 앱이 포그라운드 + 화면 켜짐**.

즉 "개발하면서 여러 PWA 띄우고 창 왔다갔다" 하는 사용 패턴에서는, 앱을 하나 지정해봐야
**다른 앱으로 전환하는 순간 꺼진다.** 이 경로만으로는 장기 유지가 안 된다.
→ 그래서 `pass_through` 직접 제어(03) + 주기적 재적용(04)이 필요하다.
