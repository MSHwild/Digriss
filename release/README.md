# release/Digriss.jar

공동작업자가 빌드한 서버용 jar (2026-10-10 21:12). **애니메이션 포함본**.

## 이 jar에만 있고 소스(src/)에는 아직 없는 것
- `kr.maeshil.digriss.addon` — `Addons`, `GuiBg`, `GuiSlots`, `IaFont` (ItemsAdder 폰트로 GUI 배경·칸 꾸미기)
- `kr.maeshil.digriss.weapon` — `WeaponSkillMotion`, `HwandoMotion`, `KatanaMotion` (무기 스킬 애니메이션)
- 위 코드를 쓰도록 바뀐 기존 파일 약 19개 (Digriss, 메인 메뉴, 국가 메뉴, 거래·거래소, 워프, 퀘스트, 칭호, 번들, 도움말, 무기 스킬 리스너 등)
- `plugin.yml` softdepend에 `BetterModel` (애니메이션 플러그인)

⚠️ 소스가 없어서 **지금 src/로 다시 빌드하면 위 애니메이션·GUI 꾸미기가 빠집니다.**
공동작업자가 소스(.kt)를 GitHub에 올리면 합친 뒤 이 폴더는 지워도 됩니다.

## 이 jar에 없는 것 (jar를 만든 뒤에 GitHub에 올라간 변경)
- 1.21.1 ~ 1.21.4 호환 (`Attrs.kt`, 커밋 "1.21.1 ~ 1.21.4 서버 모두 지원")
  → 이 jar는 1.21.1 속성 이름을 그대로 쓰므로, 1.21.4 서버에서 대축제 보스·탈것·흡혈 계열 스킬이 Paper 자동 변환에 의존함

SHA-256: `1C18191ABBB8A7363C395F4B52888204D81AB6BFD66176754CD972A34F775FCA`
