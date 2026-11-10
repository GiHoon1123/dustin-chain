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

## 실제 JVM EVM 선택

Besu 26.2.0의 `besu-evm`·`besu-datatypes`와 해당 배포의 Tuweni 2.7.2를 사용한다. Hyperledger의 공식 저장소에 올라온 모듈 메타데이터·실제 클래스 파일(JDK21)을 확인했다. EVMExecutor와 SimpleWorld updater의 commit/revert를 사용하며 반환값은 최상위 frame의 outputData에서 읽는다. 실행 성공을 고정값으로 반환하거나 원본 TypeScript 서버에 위임하지 않는다.

공식 소스: https://github.com/hyperledger/besu/tree/26.2.0/evm
공식 아티팩트: https://hyperledger.jfrog.io/artifactory/besu-maven/

계정 상태는 기존 LevelDB 키와 RLP 값을 유지하고 MPT를 Kotlin에서 계산한다. 계약 저장소는 원본의 rolling storageRoot 방식을 따른다. 이 방식은 Ethereum 표준 storage trie와 다르며 VM별 storage write 순서가 root에 영향을 줄 수 있어 전체 실행 차등 검증이 필요하다.

## 외부 실행과 개발 계정

컴파일러는 지정된 solc 실행 파일을 standard JSON으로 호출한다. 이름을 검증해 경로 이탈을 막고 실행 시간을 제한한다. 키·원본 genesis를 복사하지 않고 별도 Kotlin 생성 도구로 개발 파일을 만든다. 키 파일이 없으면 계약 배포에 필요한 계정을 임의로 흉내 내지 않고 설정 오류를 반환한다.

## 검증 중 확인한 상태 오염

Scan의 잘못된 계정 경로가 원본의 관대한 계정 생성 동작을 타고 들어오면, strict hex로 root를 계산하는 다음 블록이 실패할 수 있었다. 계정 저장·조회 모두 20바이트 주소를 검사해 오염 전에 차단했다. 잘못된 요청 뒤 정상 전송·블록 생성을 회귀 검증한다. 상태 DB와 블록 DB 사이의 crash atomicity는 별도 미완료 항목이며 이 입력 검증으로 해결됐다고 주장하지 않는다.

## 위원회 shuffle의 경계 수정

최종 원본 검토에서 BlockProducer가 seed의 4바이트 단어로 Fisher–Yates shuffle을 수행함을 확인했다. 기존 ValidatorService의 다른 shuffle과 혼동하지 않도록 실제 BlockProducer 경로를 따르고 JavaScript 5명 고정 표본을 비교했다. 원본은 `0x`를 포함한 문자열 길이 66을 8로 나눈 8.25를 순환 경계로 써 9번째 읽기에서 빈 단어/NaN이 생길 수 있다. Kotlin은 실제 seed의 완전한 단어 8개로 순환해 검증인이 누락되지 않도록 했다. 이 경계 수정과 기본 계정 파일 미설정 시 로컬 fallback 검증인을 제공하는 동작은 원본의 엄격한 기동 조건과 다르다.
