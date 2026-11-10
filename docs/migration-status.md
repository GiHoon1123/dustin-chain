# 마이그레이션 상태

## 검증 완료

- Kotlin·Spring Boot 공통 빌드, ktlint, JUnit 실행
- Keccak-256·secp256k1 키 생성·주소·deterministic low-S 서명·복구
- EIP-155 legacy 거래 서명·해시·검증과 원본 JSON quantity 표현
- 계정 금액·nonce 모델, pending/queued 거래 풀·중복 방어·승격
- 핵심 테스트 10개 통과 (`./gradlew build --no-daemon --max-workers=2`)

## 미완료

- 원본 JS 실행과의 고정 서명/거래·bloom 차등 표본
- LevelDB·MPT 상태 저장과 체크포인트, 영수증·블록
- REST·거래 검증·생산자·검증인·스테이킹·보상
- Cancun EVM·컴파일러·stablecoin·봇
- 전체 원본 테스트 이전, Chain↔Scan 통합 실행

현재는 전체 마이그레이션 완료본이 아니다. 검증되지 않은 기능을 완료로 표시하거나 저장소 이름을 전환하지 않는다.
