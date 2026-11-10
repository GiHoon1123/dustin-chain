# 원본 계약과 모듈 목록

원본 커밋: `78b5028030e6507cadf36d566ea2dabe703677b0`

원본은 NestJS 기반 단일 프로세스 PoS 학습용 체인이다. EthereumJS VM·MPT와 LevelDB를 사용하며 Ethereum 메인넷 노드가 아니다.

## API

- `POST /transaction/sign`
- `POST /transaction/send`
- `POST /transaction/send-native`
- `GET /transaction/logs`
- `GET /transaction/:hash`
- `GET /transaction/:hash/receipt`
- `GET /consensus/stats`
- `POST /stablecoin/deposit`
- `POST /stablecoin/mint`
- `POST /stablecoin/redeem`
- `POST /stablecoin/withdraw`
- `POST /stablecoin/liquidate`
- `GET /stablecoin/position/:userAddress`
- `GET /stablecoin/health/:userAddress`
- `POST /stablecoin/transfer`
- `GET /stablecoin/balance/:userAddress`
- `GET /contract/:address/bytecode`
- `POST /contract/call`
- `POST /contract/deploy`
- `POST /contract/execute`
- `POST /contract/deploy-stablecoin`
- `GET /contract/:address/abi`
- `POST /contract/deploy-staking`
- `GET /contract/deployed`
- `GET /validator/list`
- `GET /validator/stats`
- `POST /staking/deposit`
- `POST /staking/set-withdrawal-address`
- `POST /staking/request-withdrawal`
- `GET /staking/validator/:address`
- `GET /staking/validators`
- `GET /staking/stats`
- `POST /staking/process-withdrawals`
- `GET /bot/status`
- `POST /bot/start`
- `POST /bot/stop`
- `POST /account/create-wallet`
- `GET /account/:address`
- `POST /account/add-balance`
- `POST /compiler/compile-all`
- `POST /compiler/compile/:contractName`
- `GET /compiler/results`
- `GET /compiler/results/:contractName`
- `GET /block/latest`
- `GET /block/number/:number`
- `GET /block/hash/:hash`
- `GET /block/stats`
- `GET /block/producer-status`
- `POST /block/mining/start`
- `POST /block/mining/stop`
- `GET /block/logs`

## 모듈과 이전 순서

1. 암호화·단위·계정·거래·영수증·블록 모델과 거래 풀
2. LevelDB·MPT 상태와 체크포인트
3. 거래 검증·블록 생성·조회
4. 검증인·PoS 투표·스테이킹·출금·보상
5. EVM 실행·컨트랙트·Solidity 컴파일·스테이블코인
6. 거래 봇·수명주기·REST·전체 연동

## 변경 금지 계약

- chainId 999, EIP-155 legacy RLP 거래, Keccak-256, secp256k1 서명
- API의 hex quantity/string 구분과 블록 timestamp 초 단위
- stateRoot·transactionsRoot·receiptsRoot 계산과 상태 체크포인트 의미
- 기존 LevelDB 원시 키/값 형식은 독립 검증 전 호환으로 표시하지 않는다.
