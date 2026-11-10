# dustin-chain-kotlin 작업 지침

원본: `../Dustin-Chain` (읽기 전용), GitHub: GiHoon1123/Dustin-Chain.
목적: 기존 기능·계약을 유지하며 Kotlin·Spring Boot로 마이그레이션한다.

## 공통 기술
- dex-simulator-kotlin과 동일: Kotlin 2.3.21, JDK 21, Spring Boot 4.1.2, Gradle 9.8.2, Spring MVC, JUnit 5, MockK 1.14.12, springdoc 3.2.0, ktlint 플러그인 14.2.0.
- 추가 의존성은 공식 문서·실제 아티팩트를 확인하고 결정 기록에 남긴다.
- 원본의 API 경로·JSON 타입·오류·DB 스키마·이벤트·해시/서명/거래 형식을 유지한다. 보안상 위험한 기존 동작이나 불가피한 차이는 조용히 바꾸지 말고 문서·테스트에 기록한다.
- DEX의 인메모리/금액/단일 작성자 결정을 무조건 복제하지 않는다. 이 원본의 의미를 먼저 분석한다.
- 원본의 비밀 파일·키·환경 설정·운영 데이터를 복사하거나 출력·커밋하지 않는다. 외부 주문·실제 자금 전송·외부 메시지 발송 없이 로컬·모의 환경으로 검증한다.

## 작업과 검증
- 먼저 모듈·API·데이터 계약과 원본 커밋을 docs/source-inventory.md에 정리한다.
- 모듈별 이전 → 핵심 테스트 → 자체 리뷰 → 커밋. 기존 테스트는 의미를 보존해 이전하고 없는 경로는 핵심 동작 테스트를 작성한다.
- 줄 단위 번역보다 Kotlin의 명시적 타입·불변·null 안전성을 사용한다. 기능을 생략하거나 stub을 완성으로 표시하지 않는다.
- 통합 실행·외부 경계 대체 검증을 진행하고 실제 연동과 모의 결과를 구분한다.
- README와 docs/decisions.md, docs/migration-status.md에 실행 방법·원본과 차이·구현 및 미검증 범위를 유지한다. 코드 주석은 영어, 문서는 한국어.

## GitHub
- 사용자 2026-11-10 명시 승인: 이번 4개 마이그레이션은 중간 허락 없이 자체 검토 후 커밋·push·PR·main 병합까지 진행한다. 과거의 매번 확인 규칙보다 우선한다.
- 작성자 GiHoon <85157729+GiHoon1123@users.noreply.github.com>, GitHub 계정 GiHoon1123. 영어 Conventional Commits, AI 공동 작성 표기 없음.
- 첫 지침 커밋으로 main을 초기화한 뒤 작업 단위 브랜치·PR을 사용한다. 테스트·CI 통과 후 병합한다.
- 전체 호환·연동 검증 완료 전 저장소 이름을 바꾸지 않는다. 검증 완료 후 새 저장소의 -kotlin을 제거하고 원본에 실제 기술명 접미사를 붙여 보존한다. 미완성 상태에서 rename하지 않는다.

## 현재 상태
원본 분석과 마이그레이션 진행 중. 자세한 단계와 검증은 docs/migration-status.md에 기록한다.
