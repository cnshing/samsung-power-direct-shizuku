# 출처와 라이선스

이 프로젝트는 **GPL-3.0** 이다. 선택이 아니라 유래 때문이다.

`src/main/java/moe/qwreey/powerdirect/adb/` 와 `src/main/cpp/pairing.cpp` 는
[lan-mouse-android](https://github.com/qwreey/lan-mouse-android) 에서 가져왔고,
그쪽은 다시 [Key Mapper](https://github.com/keymapperorg/KeyMapper) 의 `sysbridge/` 에서
가져온 것이다 (GPL-3.0). Key Mapper 의 그 코드는 [Shizuku](https://github.com/RikkaApps/Shizuku)
에 뿌리를 둔다.

이식하며 바꾼 것:

- 패키지명 `moe.qwreey.lanmouse` → `moe.qwreey.powerdirect`
- `AdbBootstrap.KEY_NAME` — 페어링 목록에서 lan-mouse 와 구분되어야 한다
- `AdbBootstrap.connectAndRun` — 재시도 예산 확대, 증분 백오프와 지터, 매 시도마다
  무선 디버깅 재확인, "포트 없음" 을 "페어링 안 됨" 으로 오분류하던 것 수정
- `AdbMdns.discoverPort` — 광고된 포트를 루프백으로 두드려 보고 살아 있는 것만 채택
  (자세한 이유는 `docs/AGENT.md` 함정 5)

나머지 (`Agent`, `Policy*`, `Battery`, `PassThrough`, `RunLog`, UI) 는 이 프로젝트에서 새로 썼다.

삼성, Key Mapper, Shizuku, lan-mouse 프로젝트와 아무 관련이 없다.
`Settings.System.pass_through` 는 삼성이 공개한 인터페이스가 아니며, 언제든 사라질 수 있다.
