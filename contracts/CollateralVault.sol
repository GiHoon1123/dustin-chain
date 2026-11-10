// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

import "./StableCoin.sol";

/**
 * @title CollateralVault
 * @dev 담보 관리 및 스테이블 코인 발행/상환 컨트랙트
 * 
 * 이 컨트랙트는 담보형 스테이블 코인 시스템의 핵심입니다.
 * 사용자가 DSTN을 담보로 예치하고, 그에 비례하여 스테이블 코인을 발행할 수 있습니다.
 * 
 * 전체 플로우:
 * 1. depositCollateral() - DSTN 예치
 * 2. mintStablecoin() - 스테이블 코인 발행 (담보비율 150% 이상 유지)
 * 3. redeemStablecoin() - 스테이블 코인 상환 (부채 감소)
 * 4. withdrawCollateral() - 담보 인출 (담보비율 150% 이상 유지)
 * 5. liquidate() - 청산 (담보비율 150% 미만인 포지션)
 * 
 * 상수 설명:
 * - DSTN_PRICE: 1000 (1 DSTN = 1000 USD 가치)
 * - COLLATERAL_RATIO: 150 (150% = 1.5배 담보비율)
 *   → 100 USDST 발행하려면 최소 150 USD 가치의 담보 필요
 * - LIQUIDATION_BONUS: 5 (5% 청산 보너스)
 *   → 청산자가 담보 + 5% 보너스 획득
 */
contract CollateralVault {
    // ============ 상태 변수 ============
    
    /**
     * @dev StableCoin 컨트랙트 인스턴스
     * 이 컨트랙트를 통해 mint()와 burn()을 호출합니다.
     * 초기값은 address(0), 나중에 setStablecoin()으로 설정
     */
    StableCoin public stablecoin;

    /**
     * @dev DSTN 가격 (USD)
     * 1 DSTN = 1000 USD
     * 
     * 예시:
     * - 1 DSTN = 1000 USD
     * - 100 DSTN = 100,000 USD 가치
     */
    uint256 public constant DSTN_PRICE = 1000;
    
    /**
     * @dev 담보비율 (퍼센트)
     * 150 = 150% = 1.5배
     * 
     * 의미:
     * - 100 USDST 발행하려면 최소 150 USD 가치의 담보 필요
     * - 계산: 담보가치 >= 발행량 * 1.5
     * 
     * 예시:
     * - 담보: 150 DSTN = 150,000 USD
     * - 최대 발행 가능: 150,000 / 1.5 = 100,000 USDST
     */
    uint256 public constant COLLATERAL_RATIO = 150;
    
    /**
     * @dev 청산 보너스 (퍼센트)
     * 5 = 5%
     * 
     * 의미:
     * - 청산자가 담보 전액 + 5% 보너스 획득
     * - 인센티브로 빠른 청산 유도
     * 
     * 예시:
     * - 담보: 100 DSTN
     * - 청산 보너스: 5 DSTN
     * - 청산자가 받는 양: 105 DSTN
     */
    uint256 public constant LIQUIDATION_BONUS = 5;

    /**
     * @dev 사용자별 담보 잔액 (Wei 단위)
     * mapping(사용자주소 => 담보양)
     * 
     * 예시:
     * - collateral[Alice] = 150 * 10^18 (150 DSTN)
     */
    mapping(address => uint256) public collateral;
    
    /**
     * @dev 사용자별 발행한 스테이블 코인 (부채, Wei 단위)
     * mapping(사용자주소 => 부채양)
     * 
     * 예시:
     * - stablecoinDebt[Alice] = 100000 * 10^18 (100,000 USDST)
     */
    mapping(address => uint256) public stablecoinDebt;

    // ============ 이벤트 ============
    
    /**
     * @dev 담보 예치 이벤트
     */
    event DepositCollateral(address indexed user, uint256 amount);
    
    /**
     * @dev 담보 인출 이벤트
     */
    event WithdrawCollateral(address indexed user, uint256 amount);
    
    /**
     * @dev 스테이블 코인 발행 이벤트
     */
    event MintStablecoin(address indexed user, uint256 amount);
    
    /**
     * @dev 스테이블 코인 상환 이벤트
     */
    event RedeemStablecoin(address indexed user, uint256 amount);
    
    /**
     * @dev 청산 이벤트
     */
    event Liquidate(
        address indexed user,
        address indexed liquidator,
        uint256 collateralAmount,
        uint256 debtAmount,
        uint256 bonus
    );

    // ============ 생성자 ============
    
    /**
     * @dev 컨트랙트 배포 시 실행
     * 
     * 생성자 파라미터 없이 배포됩니다.
     * 나중에 setStablecoin()으로 StableCoin 주소를 설정합니다.
     * 
     * 배포 순서:
     * 1. StableCoin 배포 (파라미터 없이)
     * 2. CollateralVault 배포 (파라미터 없이)
     * 3. CollateralVault.setStablecoin(StableCoin 주소) 호출
     * 4. StableCoin.setVault(CollateralVault 주소) 호출
     */
    constructor() {
        // 빈 생성자, 나중에 setStablecoin()으로 설정
    }

    // ============ 주요 함수 ============
    
    /**
     * @dev StableCoin 컨트랙트 주소 설정
     * 
     * 배포 후 StableCoin 주소를 연결하기 위해 사용됩니다.
     * 
     * 조건:
     * - stablecoin 주소가 아직 설정되지 않았을 때 (address(0)일 때)만 호출 가능
     * - _stablecoin 주소가 0 주소가 아니어야 함
     * 
     * @param _stablecoin 설정할 StableCoin 컨트랙트 주소
     */
    function setStablecoin(address _stablecoin) external {
        require(address(stablecoin) == address(0), "Stablecoin already set");
        require(_stablecoin != address(0), "Cannot set stablecoin to zero address");
        stablecoin = StableCoin(_stablecoin);
    }
    
    /**
     * @dev 담보 예치
     * 
     * 사용자가 DSTN을 Vault에 예치합니다.
     * 
     * 사용법:
     * - 트랜잭션과 함께 DSTN을 전송 (msg.value)
     * - 예: depositCollateral{value: 150 * 10^18}()
     * 
     * 동작:
     * 1. 전송된 DSTN 양 확인 (msg.value > 0)
     * 2. 사용자의 담보 잔액 증가
     * 3. 이벤트 발생
     * 
     * 예시:
     * - Alice가 150 DSTN 예치
     * - collateral[Alice] = 150 * 10^18
     */
    function depositCollateral() external payable {
        require(msg.value > 0, "Amount must be greater than 0");
        
        // 사용자의 담보 잔액 증가
        collateral[msg.sender] += msg.value;
        
        emit DepositCollateral(msg.sender, msg.value);
    }

    /**
     * @dev 스테이블 코인 발행
     * 
     * 담보를 기반으로 스테이블 코인을 발행합니다.
     * 담보비율이 150% 이상이어야 발행 가능합니다.
     * 
     * @param stablecoinAmount 발행할 스테이블 코인 양 (Wei 단위)
     * 
     * 계산 공식:
     * - 담보 가치 = collateral[user] * DSTN_PRICE / 1e18
     * - 필요 담보 = stablecoinAmount * COLLATERAL_RATIO / 100
     * - 조건: 담보 가치 >= 필요 담보
     * 
     * 예시:
     * - 담보: 150 DSTN = 150,000 USD 가치
     * - 발행 요청: 100,000 USDST
     * - 필요 담보: 100,000 * 1.5 = 150,000 USD ✓ (가능)
     * 
     * 동작:
     * 1. 담보비율 검증
     * 2. 사용자의 부채 증가
     * 3. StableCoin.mint() 호출하여 토큰 발행
     * 4. 이벤트 발생
     */
    function mintStablecoin(uint256 stablecoinAmount) external {
        require(stablecoinAmount > 0, "Amount must be greater than 0");

        // 담보 가치 계산 (USD)
        // collateral[user]는 Wei 단위이므로 1e18로 나눔
        // 예: 150 * 10^18 Wei * 1000 / 1e18 = 150,000 USD
        uint256 collateralValue = (collateral[msg.sender] * DSTN_PRICE) / 1e18;
        
        // 발행하려는 스테이블 코인에 필요한 담보 가치 계산 (USD)
        // stablecoinAmount는 Wei 단위이므로 1e18로 나눈 후 계산
        // 예: (100,000 * 10^18 / 1e18) * 150 / 100 = 150,000 USD
        uint256 stablecoinAmountInUSD = stablecoinAmount / 1e18;
        uint256 requiredCollateral = (stablecoinAmountInUSD * COLLATERAL_RATIO) / 100;

        // 담보비율 검증 (150% 이상이어야 함)
        require(
            collateralValue >= requiredCollateral,
            "Insufficient collateral ratio"
        );

        // 부채 증가
        stablecoinDebt[msg.sender] += stablecoinAmount;
        
        // StableCoin 컨트랙트에 mint 요청
        stablecoin.mint(msg.sender, stablecoinAmount);

        emit MintStablecoin(msg.sender, stablecoinAmount);
    }

    /**
     * @dev 스테이블 코인 상환
     * 
     * 발행한 스테이블 코인을 상환하여 부채를 감소시킵니다.
     * 상환 후 담보비율이 개선되어 담보 인출이 가능해집니다.
     * 
     * @param stablecoinAmount 상환할 스테이블 코인 양 (Wei 단위)
     * 
     * 예시:
     * - Alice의 부채: 100,000 USDST
     * - 상환: 50,000 USDST
     * - 결과: 부채 50,000 USDST로 감소
     * 
     * 동작:
     * 1. 상환할 양이 0보다 큰지 확인
     * 2. 사용자의 부채가 충분한지 확인
     * 3. 사용자가 보유한 스테이블 코인이 충분한지 확인
     * 4. 부채 감소
     * 5. StableCoin.burn() 호출하여 토큰 소각
     * 6. 이벤트 발생
     */
    function redeemStablecoin(uint256 stablecoinAmount) external {
        require(stablecoinAmount > 0, "Amount must be greater than 0");
        
        // 부채 확인
        require(
            stablecoinDebt[msg.sender] >= stablecoinAmount,
            "Insufficient debt"
        );
        
        // 스테이블 코인 잔액 확인
        require(
            stablecoin.balanceOf(msg.sender) >= stablecoinAmount,
            "Insufficient stablecoin balance"
        );

        // 부채 감소
        stablecoinDebt[msg.sender] -= stablecoinAmount;
        
        // StableCoin 컨트랙트에 burn 요청
        stablecoin.burn(msg.sender, stablecoinAmount);

        emit RedeemStablecoin(msg.sender, stablecoinAmount);
    }

    /**
     * @dev 담보 인출
     * 
     * 예치한 담보를 인출합니다.
     * 인출 후에도 담보비율이 150% 이상 유지되어야 합니다.
     * 
     * @param amount 인출할 담보 양 (Wei 단위)
     * 
     * 계산:
     * - 인출 후 담보 = 현재 담보 - amount
     * - 인출 후 담보 가치 >= 필요 담보 (150%)
     * 
     * 예시:
     * - 현재 담보: 150 DSTN (150,000 USD)
     * - 부채: 100,000 USDST
     * - 필요 담보: 100,000 * 1.5 = 150,000 USD
     * - 인출 가능: 0 DSTN (인출 불가)
     * 
     * - 부채를 50,000 USDST로 상환 후
     * - 필요 담보: 50,000 * 1.5 = 75,000 USD
     * - 인출 가능: 최대 75 DSTN
     * 
     * 동작:
     * 1. 인출할 양 확인
     * 2. 담보 잔액 확인
     * 3. 인출 후 담보비율 검증
     * 4. 담보 잔액 감소
     * 5. DSTN 전송
     * 6. 이벤트 발생
     */
    function withdrawCollateral(uint256 amount) external {
        require(amount > 0, "Amount must be greater than 0");
        require(collateral[msg.sender] >= amount, "Insufficient collateral");

        // 인출 후 담보 계산
        uint256 newCollateral = collateral[msg.sender] - amount;
        
        // 인출 후 담보 가치 계산 (USD)
        uint256 collateralValue = (newCollateral * DSTN_PRICE) / 1e18;
        
        // 필요 담보 계산 (USD)
        // stablecoinDebt는 Wei 단위이므로 1e18로 나눈 후 계산
        uint256 debtInUSD = stablecoinDebt[msg.sender] / 1e18;
        uint256 requiredCollateral = (debtInUSD * COLLATERAL_RATIO) / 100;

        // 인출 후에도 담보비율 150% 이상 유지되어야 함
        require(
            collateralValue >= requiredCollateral,
            "Insufficient collateral ratio after withdrawal"
        );

        // 담보 잔액 감소
        collateral[msg.sender] = newCollateral;

        // DSTN 전송 (안전한 방식)
        (bool success, ) = payable(msg.sender).call{value: amount}("");
        require(success, "Transfer failed");

        emit WithdrawCollateral(msg.sender, amount);
    }

    /**
     * @dev 청산 실행
     * 
     * 담보비율이 150% 미만인 포지션을 청산합니다.
     * 누구나 호출 가능하며, 청산자는 담보 + 보너스를 획득합니다.
     * 
     * @param user 청산 대상 주소
     * 
     * 청산 조건:
     * - 담보비율 < 150%
     * - 담보 가치 < 필요 담보
     * 
     * 청산 과정:
     * 1. 청산자가 부채만큼의 스테이블 코인을 소각 (burn)
     * 2. 청산자가 담보 전액 + 5% 보너스 획득
     * 3. 청산 대상의 부채와 담보 초기화
     * 
     * 예시:
     * - Alice의 담보: 100 DSTN (100,000 USD)
     * - Alice의 부채: 100,000 USDST
     * - 필요 담보: 100,000 * 1.5 = 150,000 USD
     * - 실제 담보: 100,000 USD < 150,000 USD → 청산 가능
     * 
     * - 청산자 Bob이 100,000 USDST 소각
     * - Bob이 100 DSTN + 5 DSTN(보너스) = 105 DSTN 획득
     * - Alice의 포지션 초기화
     * 
     * 동작:
     * 1. 주소 유효성 확인
     * 2. 담보비율 확인 (150% 미만이어야 함)
     * 3. 청산 대상의 부채와 담보 저장
     * 4. 청산 보너스 계산
     * 5. 부채와 담보 초기화
     * 6. 청산자가 부채만큼 스테이블 코인 소각
     * 7. 청산자에게 담보 + 보너스 전송
     * 8. 이벤트 발생
     */
    function liquidate(address user) external {
        require(user != address(0), "Invalid user address");
        require(user != msg.sender, "Cannot liquidate yourself");

        // 담보 가치 계산 (USD)
        uint256 collateralValue = (collateral[user] * DSTN_PRICE) / 1e18;
        
        // 필요 담보 계산 (USD)
        // stablecoinDebt는 Wei 단위이므로 1e18로 나눈 후 계산
        uint256 debtInUSD = stablecoinDebt[user] / 1e18;
        uint256 requiredCollateral = (debtInUSD * COLLATERAL_RATIO) / 100;

        // 담보비율이 150% 미만이어야 청산 가능
        require(
            collateralValue < requiredCollateral,
            "Position is healthy, cannot liquidate"
        );

        // 청산 정보 저장
        uint256 debtAmount = stablecoinDebt[user]; // 부채
        uint256 collateralAmount = collateral[user]; // 담보
        uint256 bonus = (collateralAmount * LIQUIDATION_BONUS) / 100; // 보너스 (5%)
        uint256 totalReward = collateralAmount + bonus; // 총 보상

        // 청산 대상의 부채와 담보 초기화
        stablecoinDebt[user] = 0;
        collateral[user] = 0;

        // 청산자가 부채를 상환해야 함 (스테이블 코인 필요)
        require(
            stablecoin.balanceOf(msg.sender) >= debtAmount,
            "Insufficient stablecoin to cover debt"
        );
        
        // 청산자가 부채만큼 스테이블 코인 소각
        stablecoin.burn(msg.sender, debtAmount);

        // 청산자에게 담보 + 보너스 전송
        (bool success, ) = payable(msg.sender).call{value: totalReward}("");
        require(success, "Liquidation transfer failed");

        emit Liquidate(user, msg.sender, collateralAmount, debtAmount, bonus);
    }

    // ============ 조회 함수 ============
    
    /**
     * @dev 사용자 포지션 조회
     * 
     * 사용자의 담보, 부채, 담보비율을 조회합니다.
     * 
     * @param user 조회할 사용자 주소
     * @return collateralAmount 담보 양 (Wei)
     * @return debtAmount 부채 양 (Wei)
     * @return collateralRatio 담보비율 (퍼센트, 예: 150 = 150%)
     * 
     * 담보비율 계산:
     * - 부채가 0이면: 무한대 (type(uint256).max)
     * - 부채가 있으면: (담보가치 * 100) / 부채
     * 
     * 예시:
     * - 담보: 150 DSTN = 150,000 USD
     * - 부채: 100,000 USDST
     * - 담보비율: (150,000 * 100) / 100,000 = 150%
     */
    function getPosition(address user)
        external
        view
        returns (
            uint256 collateralAmount,
            uint256 debtAmount,
            uint256 collateralRatio
        )
    {
        collateralAmount = collateral[user];
        debtAmount = stablecoinDebt[user];

        if (debtAmount == 0) {
            // 부채가 없으면 담보비율은 무한대
            collateralRatio = type(uint256).max;
        } else {
            // 담보 가치 계산 (USD)
            uint256 collateralValue = (collateralAmount * DSTN_PRICE) / 1e18;
            // 부채를 USD 단위로 변환 (Wei 단위이므로 1e18로 나눔)
            uint256 debtInUSD = debtAmount / 1e18;
            // 담보비율 계산 (퍼센트)
            collateralRatio = (collateralValue * 100) / debtInUSD;
        }
    }

    /**
     * @dev 담보비율이 건강한지 확인
     * 
     * 담보비율이 150% 이상이면 건강한 포지션입니다.
     * 
     * @param user 확인할 사용자 주소
     * @return isHealthy 건강 여부 (true = 건강함, false = 청산 위험)
     * 
     * 예시:
     * - 담보비율 150% 이상 → true (건강함)
     * - 담보비율 150% 미만 → false (청산 위험)
     */
    function isHealthy(address user) external view returns (bool) {
        // 부채가 없으면 건강함
        if (stablecoinDebt[user] == 0) {
            return true;
        }

        // 담보 가치 계산 (USD)
        uint256 collateralValue = (collateral[user] * DSTN_PRICE) / 1e18;
        
        // 필요 담보 계산 (USD)
        // stablecoinDebt는 Wei 단위이므로 1e18로 나눈 후 계산
        uint256 debtInUSD = stablecoinDebt[user] / 1e18;
        uint256 requiredCollateral = (debtInUSD * COLLATERAL_RATIO) / 100;

        // 담보비율 150% 이상이면 건강함
        return collateralValue >= requiredCollateral;
    }

    // ============ 특수 함수 ============
    
    /**
     * @dev 컨트랙트가 DSTN을 받을 수 있도록 하는 함수
     * 
     * 이더리움에서 컨트랙트가 네이티브 토큰(ETH/DSTN)을 받으려면
     * receive() 또는 fallback() 함수가 필요합니다.
     * 
     * 하지만 여기서는 depositCollateral()을 통해서만 예치하도록 강제합니다.
     * 직접 전송하면 에러가 발생합니다.
     */
    receive() external payable {
        revert("Use depositCollateral() to deposit");
    }
}
