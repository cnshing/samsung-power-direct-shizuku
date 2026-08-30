# Pause USB Power Delivery — 기능 자체의 정체

조사일: 2026-08-29
대상 기기: Galaxy Tab S9+ / Android 16 (One UI 8) 가정

## 한 줄

**PPS 충전기가 협상한 전력을 배터리를 거치지 않고 시스템 레일로 직접 넣는 기능.** 게임 부스터 안에
있고, 게임이 포그라운드일 때만 산다.

## 공식 명칭

| 로케일 / 버전 | 문자열 |
|---|---|
| 영문 (초기, One UI 5.1~) | Pause USB Power Delivery |
| 영문 (최근 지원문서) | Pause USB PD charging when gaming |
| 한국어 (One UI 6.0 이하) | USB Power Delivery 충전 일시정지 |
| 한국어 (One UI 6.0 이상) | 게임 중 USB PD 충전 일시정지 |

일명 "우회충전(bypass charging)". Settings > 배터리의 **"배터리 보호"(80% 상한)** 와는 완전히 다른
기능이다. 삼성 멤버스에도 이 둘을 혼동해서 올라온 질문 스레드가 따로 있다.

## 진짜 바이패스인가 — 논쟁 있음

- 삼성 영문 지원문서는 단정한다: *"a direct system power supply method that bypasses the battery"*
  ([Samsung UK](https://www.samsung.com/uk/support/mobile-devices/what-is-the-pause-usb-power-delivery-feature/))
- **삼성 한국어 공식문서는 표현이 약하다**: "배터리 충전을 최소화하고 대부분의 전력을 기기에 공급"
  ([samsungsvc.co.kr/solution/1324310](https://www.samsungsvc.co.kr/solution/1324310)).
  배터리가 전기적으로 완전히 분리된다고는 어디에도 안 쓴다.
- XDA 스레드 [*"Is Samsung Bypass Charging FAKE?"*](https://xdaforums.com/t/is-samsung-bypass-charging-fake-pause-usb-pd-charging-when-gaming.4766245/)
  에서 USB 전력계로 실측한 사용자들은 **0mA가 아니라 30~70mA가 계속 흐른다**고 보고. OnePlus의
  바이패스 구현은 일관되게 0mA가 나오는 것과 대비된다. S24 Ultra에서 기능 켠 채로 세션 도중
  63% → 65%로 **오히려 올라간** 사례도 있음.

**해석**: 하드웨어 레벨의 완전 분리라기보다 "충전 전류 최소화 + 시스템 직결"에 가까울 가능성이 있다.
그래도 만충 근처에서 계속 100% 유지하며 트리클 충전되는 것보다는 배터리에 훨씬 낫다.
→ **실측이 필요한 항목.** 05-test-checklist.md 참조.

## 발동 조건 (전부 AND)

| 조건 | 근거 |
|---|---|
| **PD + PPS 지원 25W 이상** 충전기 | 삼성 공식 (한국/UK/Levant 지원문서 일치) |
| 배터리 **20% 이상** | 삼성 공식 다수 (Android Authority는 "아무 잔량에서나 된다"고 반대로 씀 — 오기로 판단) |
| 설정 > 배터리에서 **고속 충전 켜짐** | Android Central |
| **게임런처가 게임으로 인식한 앱**이 실행 중 | 삼성 공식 + XDA 다수 |
| **화면 켜짐 + 해당 게임이 포그라운드** | 삼성 한국어 공식: *"화면이 꺼지거나 다른 화면으로 전환되면 자동으로 비활성화됩니다"* |
| 온도 컷오프 | **근거 못 찾음.** 없다는 뜻이 아니라 문서화된 게 없음 |

토글 자체가 **게임 실행 중 + PPS 충전기 연결 상태에서만 게임 부스터 메뉴에 나타난다.** 평상시에
미리 켜둘 수 없다. ([9to5Google](https://9to5google.com/2023/02/14/samsung-pause-usb-power-delivery/))

> 이 표가 이번 조사에서 가장 중요한 부분이다. `pass_through` 설정값을 ADB로 뒤집는 데 성공하더라도,
> **PPS 충전기 요구조건은 펌웨어/충전기 협상 레벨에서 강제되므로 소프트웨어로 우회되지 않는다.**
> 25W PPS 충전기가 없으면 이 문서 전체가 무의미하다. 이것부터 확인할 것.

## 설정 경로

게임런처(Gaming Hub) → 더보기(⋮) → 게임 부스터 → **게임 중 USB PD 충전 일시정지**

Tab S8/S9/S10 전부 동일 경로. 폰/태블릿 간 차이 보고 없음.

## 지원 범위

- **도입**: Galaxy S23 시리즈 / One UI 5.1 / 2023-02
- **역이식 방식이 중요하다**: 전체 OS 업데이트가 아니라 **갤럭시 스토어의 게임 부스터 플러그인 앱
  업데이트(v5.0.03.0)** 로 구형 기기에 뿌려졌다. 즉 지원 여부는 One UI 버전보다 **게임 부스터 앱
  버전**에 묶여 있다. ([Android Authority](https://www.androidauthority.com/galaxy-s23-bypass-charging-feature-more-phones-3282489/))
- 지원 기기: S22/S23/S24 계열, Z Fold 2~5, Z Flip 4~5, A23/A33/A35/A53/A73, M33,
  **Tab S8 / Tab S9 / Tab S10 시리즈**
- One UI 7 / 8에서의 상태를 직접 확인한 문서는 못 찾음. 계승됐다고 추정할 뿐.

## 앱 제한 — 공식 입장

게임런처가 게임으로 인식한 앱만. **전역 "모든 앱" 토글은 없다.**

게임 아닌 앱을 게임런처에 수동 추가하면 실행 시 토스트가 뜬다:
*"This app isn't a game. Some features will not work."* 그리고 배터리 %는 평소대로 올라간다.

삼성 멤버스 미국 게시판에 "Add Bypass Charging" 기능 요청이 있고, 한국 게시판에도
[*"게임이 아닌 앱에도 게임 중 USB PD 충전 일시정지(우회충전)을 가능하게 해주세요"*](https://r1.community.samsung.com/t5/gaming-hub/%EA%B2%8C%EC%9E%84%EC%9D%B4-%EC%95%84%EB%8B%8C-%EC%95%B1%EC%97%90%EB%8F%84-%EA%B2%8C%EC%9E%84-%EC%A4%91-usb-pd-%EC%B6%A9%EC%A0%84-%EC%9D%BC%EC%8B%9C%EC%A0%95%EC%A7%80-%EC%9D%BC%EB%AA%85-%EC%9A%B0%ED%9A%8C%EC%B6%A9%EC%A0%84-%EC%9D%84-%EA%B0%80%EB%8A%A5%ED%95%98%EA%B2%8C-%ED%95%B4%EC%A3%BC%EC%84%B8%EC%9A%94/td-p/32055864)
글이 있다. **요청이 존재한다는 것 자체가 "게임런처 수동 추가로는 안 된다"의 방증이다.**
