# release/Digriss.jar

공동작업자가 만든 통합본 jar (2026-10-10 21:12). 참고용 보관본입니다.

**이 jar의 변경 내용은 이제 전부 `src/`에 소스로 들어 있습니다** (디컴파일 후 Kotlin으로 복원, 공동작업자 `CHANGES.md` 기준).
앞으로는 `src/`로 빌드한 jar를 쓰면 되고, 이 파일은 비교·되돌리기용입니다.

| CHANGES.md 항목 | 소스 위치 |
|---|---|
| 일본도 · 환도 모션 | `weapon/KatanaMotion.kt`, `weapon/HwandoMotion.kt` |
| 무기 스킬 모션 4종 | `weapon/WeaponSkillMotion.kt` + `WeaponSkillListener` 한 줄 |
| GUI 배경 29곳 | `addon/GuiBg.kt` (`GuiBg.createInventory`) |
| 슬롯 이동 (국가 · 외교 상대국 · 퀘스트) | `addon/GuiSlots.kt` (`GuiSlots.set` / `slot` / `rawSlot`) |
| 메인 메뉴 B안 | `menu/MainMenu.kt` |
| 킬 이펙트 상점 (10번 칸부터, holder) | `effect/EffectGUI.kt`, `effect/EffectListener.kt` |
| 유리판 3곳 (직업 · 도움말 · 사전예약) | `GuiSlots.set`이 `common.filler`로 바꿈 |
| 모션 켜기 | `addon/Addons.kt` (`Digriss.onEnable` 끝에서 호출) |
| plugin.yml | softdepend `BetterModel` |

빌드: Paper API 1.21.4 + BetterModel API 2.2.0 (`build.gradle.kts`). plugin.yml `api-version`은 1.21.1 그대로.

서버에 함께 필요한 것 (공동작업자 패키지, 저장소에는 없음):
- `gui/digriss_gui.zip` → `plugins/ItemsAdder/`에 풀고 `/iazip`, `gui/적용방법.md` 2번의 icons.yml 수정
- 무기 모션 리소스: 일본도 · 환도 · 스킬 모션 폴더의 `*_server.zip`, `*_bettermodel.zip`

SHA-256: `1C18191ABBB8A7363C395F4B52888204D81AB6BFD66176754CD972A34F775FCA`
