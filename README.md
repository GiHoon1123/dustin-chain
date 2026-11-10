# Dustin Chain Kotlin

학습용 PoS 체인 [Dustin-Chain](https://github.com/GiHoon1123/Dustin-Chain)의 Kotlin·Spring Boot 이전본. 실제 Besu Cancun EVM으로 원본 Solidity 계약을 실행한다. Ethereum 메인넷 노드가 아니다.

## 실행

JDK 21이 필요하다. Kotlin 2.3.21, Spring Boot 4.1.2, Gradle 9.8.2.

```bash
./gradlew build --no-daemon --max-workers=2
./gradlew generateGenesis -Poutput=local-genesis
./gradlew bootRun --args='--server.port=3100 --chain.genesis-file=local-genesis/genesis.json --chain.accounts-file=local-genesis/genesis-accounts.json'
```

`generateGenesis`는 새 디렉터리에 로컬 개발 계정 256개와 초기 잔액을 만들며 기존 파일을 덮어쓰거나 키를 출력하지 않는다. 비밀 파일은 커밋하지 않는다. `chain.accounts-file`이 설정되면 기동 시 기본 계약과 최초 10명의 검증인을 준비한다. 계약 자동 준비를 끄려면 `--chain.bootstrap-contracts=false`, 로컬 거래 봇은 `--chain.bot-enabled=false`, 자동 블록 생성은 `--chain.auto-mine=false`를 지정한다.

기본 DB 경로는 `data`, 포트는 3100으로 명시해 실행할 수 있다. `--chain.data-dir=<새 경로>`와 `--chain.deployment-file=<메타데이터 경로>`로 실험을 격리한다. 계정 API는 개발용 잔액 발급을 포함하므로 외부 공개 운영용으로 배포하지 않는다.

계약 컴파일 API는 **solc 0.8.33** 실행 파일을 별도로 설치하고 `--chain.solc-binary=/absolute/path/solc`로 지정한다. 원본 컴파일 바이트코드도 포함되어 기본 계약 실행에는 매번 컴파일러를 호출하지 않는다. 계약 수정 시 `POST /compiler/compile-all`로 갱신한다.

`GET /block/latest`, `/block/stats`, `/consensus/stats`, `/validator/list`, `/staking/stats`, `/bot/status`로 상태를 조회한다. Swagger UI는 `/swagger-ui/index.html`, Actuator health는 `/actuator/health`다. 전체 API는 [원본 계약](docs/source-inventory.md)에 있다.

## 검증과 제한

[마이그레이션 상태](docs/migration-status.md)는 실제 테스트·HTTP 연동·남은 차등 검증을 구분한다. [설계 결정](docs/decisions.md)은 EVM·암호화·저장소 선택과 차이를 기록한다. 특히 원본 체인과 동일한 DB 전체·EVM gas·블록 해시의 완전한 호환은 아직 검증되지 않았다. 검증 완료 전 저장소 이름을 바꾸지 않는다.
