# Dustin Chain Kotlin

학습용 PoS 체인 [Dustin-Chain](https://github.com/GiHoon1123/Dustin-Chain)을 Kotlin·Spring Boot로 이전한다. Ethereum 메인넷 노드가 아니다.

현재 구현·미완료 범위는 [마이그레이션 상태](docs/migration-status.md), 원본 API와 모듈은 [원본 계약](docs/source-inventory.md), 변경 이유는 [설계 결정](docs/decisions.md)에 기록한다.

## 검증

```bash
./gradlew build --no-daemon --max-workers=2
```

Kotlin 2.3.21, Spring Boot 4.1.2, JDK 21, Gradle 9.8.2. 아직 HTTP 기능 전체와 EVM 이전이 완료되지 않았다. 원본·Kotlin 서비스 간 연동 검증 완료 전에는 저장소 이름을 변경하지 않는다.
