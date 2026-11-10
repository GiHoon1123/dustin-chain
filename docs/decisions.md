# 설계 결정

## 버전과 실행 환경

DEX 저장소의 Kotlin 2.3.21·Spring Boot 4.1.2·JDK 21·Gradle 9.8.2 설정을 사용한다. 버전은 사용자 지정 기준이며 원본 TypeScript 버전과 무관하다.

## 암호화

Bouncy Castle 1.83의 Keccak-256·secp256k1과 SHA-256 기반 RFC6979 서명 난수를 사용한다. 원본 elliptic과 동일한 deterministic low-S 서명 및 EIP-155 v를 유지한다. RLP는 unsigned big-endian 정수·빈 값 규칙을 명시적으로 구현한다. 원본 JS는 일부 잘못된 hex를 조용히 잘라내지만 Kotlin의 공개 hex 유틸리티는 잘못된 문자를 거절한다. 정상 거래 계약을 유지하고 잘못된 입력의 차이는 테스트한다.

원본의 로그 bloom은 Ethereum 구현과 달리 byteIndex=bit/8, bitOffset=7-bit%8를 사용한다. 마이그레이션에서는 원본의 이 배치를 유지한다. 표준 Ethereum bloom으로 조용히 교체하지 않는다.

## 동시성

Node 이벤트 루프의 단일 실행과 달리 Spring MVC는 여러 요청 스레드가 있다. 거래 풀은 동기화된 메서드로 원자적인 추가·중복 검사·승격을 제공한다. 블록과 상태의 원자적 갱신 경계는 후속 상태/블록 구현에서 정한다.

## EVM과 상태

원본은 EthereumJS Cancun VM과 custom state manager를 사용한다. JVM 대안은 Besu의 추출 가능한 EVM과 Web3j embedded EVM을 조사했다. 단순히 EVM을 교체하는 것으로 기존 LevelDB, 스테이킹 precompile, gas·환불·체크포인트 계약의 일치가 보장되지 않으므로 별도 차등 검증이 필요하다.

참고: https://docs.web3j.io/latest/web3j_evm/
