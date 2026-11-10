// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/**
 * @title StakingContract
 * @dev 이더리움 PoS 기반 스테이킹 컨트랙트
 * 
 * 이 컨트랙트는 이더리움 2.0 스테이킹 시스템과 동일한 방식으로 동작합니다.
 * 사용자가 DSTN을 스테이킹하여 Validator가 되고, 블록 제안/검증에 대한 보상을 받습니다.
 * 
 * 전체 플로우:
 * 1. deposit() - DSTN 스테이킹 예치 (최소 32 DSTN)
 * 2. activateValidator() - Validator 활성화 (Backend에서 호출)
 * 3. rewardValidator() - 보상 지급 (Backend에서 호출)
 *    - Proposer 보상: 블록 생성 시 즉시 지급
 *    - Committee 보상: Epoch 단위로 누적 후 지급
 * 4. requestWithdrawal() - 출금 요청 (사용자가 직접 호출)
 * 5. processWithdrawals() - 출금 처리 (Backend에서 주기적으로 호출, 자동 전송)
 * 6. slashValidator() - 슬래싱 (Backend에서 호출)
 * 
 * 이더리움과의 동일점:
 * - 최소 스테이킹 금액: 32 DSTN (이더리움 32 ETH)
 * - 출금 대기 시간: 27시간 (이더리움과 동일)
 * - 출금 방식: 출금 요청 후 자동 전송 (이더리움과 동일)
 * - 보상 구조: Proposer 즉시, Committee Epoch 단위 (이더리움과 동일)
 * - 슬래싱: Double Vote, Surround Vote (이더리움과 동일)
 * 
 * 상수 설명:
 * - MIN_STAKE: 32 DSTN (이더리움 32 ETH와 동일)
 * - WITHDRAWAL_DELAY: 27시간 (이더리움과 동일)
 * - MAX_VALIDATORS: 최대 Validator 수 (네트워크 보안을 위한 제한)
 */
contract StakingContract {
    // ============ 상태 변수 ============
    
    /**
     * @dev 최소 스테이킹 금액 (Wei 단위)
     * 32 DSTN = 32 * 10^18 Wei
     * 
     * 이더리움:
     * - 32 ETH = 32 * 10^18 Wei
     * 
     * 의미:
     * - Validator가 되기 위한 최소 금액
     * - 이 금액 이상 스테이킹해야 Validator 등록 가능
     */
    uint256 public constant MIN_STAKE = 32 * 10**18; // 32 DSTN
    
    /**
     * @dev 출금 대기 시간 (초 단위)
     * 1분 = 60초 (테스트용)
     * 
     * 이더리움:
     * - 약 27시간 (최소)
     * 
     * 의미:
     * - 출금 요청 후 실제 출금까지 대기해야 하는 시간
     * - 악의적 행동 후 즉시 탈출 방지
     * - 슬래싱 집행 시간 확보
     * 
     * 테스트:
     * - 테스트 편의를 위해 1분으로 설정
     * - 프로덕션에서는 27시간으로 변경 필요
     */
    uint256 public constant WITHDRAWAL_DELAY = 5 seconds; // 5초 (테스트용, 기존: 1 minutes)
    
    /**
     * @dev 최대 Validator 수
     * 네트워크 보안과 성능을 위한 제한
     * 
     * 이더리움:
     * - 제한 없음 (현재 900,000+ Validator)
     * 
     * 우리:
     * - 초기에는 제한을 두고, 나중에 확장 가능
     */
    uint256 public constant MAX_VALIDATORS = 100;
    
    
    /**
     * @dev Validator 상태
     * 
     * 이더리움 2.0과 동일한 상태 정의:
     * - pending_initialized: 초기화 대기 (Deposit Contract에 예치 완료, 아직 Beacon Chain에 등록 안 됨)
     * - pending_queued: 활성화 대기열 (Beacon Chain에 등록됨, Activation Queue 대기 중)
     * - active_ongoing: 활성 (블록 제안/검증 가능)
     * - active_exiting: 출금 대기 중 (출금 요청했지만 아직 Exit Queue 처리 안 됨)
     * - exited_withdrawable: 출금 가능 (Exit Queue 처리 완료, 대기 시간 경과)
     * - exited_withdrawn: 출금 완료 (자산 인출 완료)
     * 
     * 상태 전환:
     * 1. deposit() → pending_initialized
     * 2. activateValidator() → pending_queued → active_ongoing (자동 또는 수동)
     * 3. requestWithdrawal() → active_exiting
     * 4. processWithdrawals() (대기 시간 경과) → exited_withdrawable → exited_withdrawn
     */
    enum ValidatorStatus {
        pending_initialized,  // 초기화 대기
        pending_queued,       // 활성화 대기열
        active_ongoing,       // 활성
        active_exiting,       // 출금 대기 중
        exited_withdrawable,  // 출금 가능
        exited_withdrawn      // 출금 완료
    }
    
    /**
     * @dev Validator 정보 구조체
     * 
     * 이더리움:
     * - effective_balance: 유효 스테이킹 금액
     * - activation_epoch: 활성화된 Epoch
     * - exit_epoch: 출금 요청 Epoch
     * - withdrawable_epoch: 출금 가능 Epoch
     * 
     * 우리:
     * - validatorAddress: Validator 주소
     * - stakedAmount: 스테이킹 금액 (Wei)
     * - status: 상태
     * - activatedAt: 활성화된 타임스탬프
     * - exitRequestedAt: 출금 요청 타임스탬프
     * - totalRewards: 누적 보상 (Wei)
     * - slashedAmount: 슬래싱된 금액 (Wei)
     * - withdrawalAddress: 출금 주소 (이더리움과 동일)
     */
    struct Validator {
        address validatorAddress;      // Validator 주소
        uint256 stakedAmount;          // 스테이킹 금액 (Wei)
        ValidatorStatus status;        // 상태
        uint256 activatedAt;           // 활성화된 타임스탬프 (초)
        uint256 exitRequestedAt;        // 출금 요청 타임스탬프 (초)
        uint256 totalRewards;           // 누적 보상 (Wei)
        uint256 slashedAmount;         // 슬래싱된 금액 (Wei)
        address withdrawalAddress;     // 출금 주소 (이더리움과 동일)
    }
    
    /**
     * @dev Validator 정보 매핑
     * mapping(Validator 주소 => Validator 정보)
     */
    mapping(address => Validator) public validators;
    
    /**
     * @dev 활성 Validator 목록
     * Proposer/Committee 선택 시 사용
     */
    address[] public validatorList;
    
    /**
     * @dev 출금 대기열
     * 출금 요청한 Validator들의 주소 목록
     * 
     * 이더리움:
     * - Withdrawal Queue에 순서대로 등록
     * - 순서대로 처리
     * 
     * 우리:
     * - 배열로 관리
     * - processWithdrawals()에서 순서대로 처리
     */
    address[] public withdrawalQueue;
    
    /**
     * @dev 전체 스테이킹 금액 (Wei)
     * 모든 Validator의 스테이킹 금액 합계
     */
    uint256 public totalStaked;
    
    /**
     * @dev 전체 지급된 보상 (Wei)
     * 모든 Validator에게 지급된 보상 합계
     */
    uint256 public totalRewards;
    
    /**
     * @dev 전체 슬래싱된 금액 (Wei)
     * 모든 Validator에서 슬래싱된 금액 합계
     */
    uint256 public totalSlashed;
    
    /**
     * @dev Epoch별 Committee 보상 누적
     * mapping(Epoch 번호 => mapping(Validator 주소 => 누적 보상))
     * 
     * 이더리움:
     * - Committee 보상은 Epoch 단위로 누적
     * - Epoch 완료 시 일괄 지급
     * 
     * 우리:
     * - 동일하게 구현
     */
    mapping(uint256 => mapping(address => uint256)) public epochRewards;
    
    /**
     * @dev 현재 Epoch 번호
     * Epoch = 블록 번호 / 32 (32 블록 = 1 Epoch)
     */
    uint256 public currentEpoch;
    
    // ============ 이벤트 ============
    
    /**
     * @dev 스테이킹 예치 이벤트
     */
    event Deposited(address indexed validator, uint256 amount);
    
    /**
     * @dev Validator 활성화 이벤트
     */
    event Activated(address indexed validator, uint256 timestamp);
    
    /**
     * @dev 출금 요청 이벤트
     */
    event WithdrawalRequested(address indexed validator, uint256 timestamp);
    
    /**
     * @dev 출금 완료 이벤트
     */
    event Withdrawn(address indexed validator, address indexed to, uint256 amount);
    
    /**
     * @dev 보상 지급 이벤트
     */
    event Rewarded(address indexed validator, uint256 amount, string rewardType);
    
    /**
     * @dev 슬래싱 이벤트
     */
    event Slashed(address indexed validator, uint256 amount, string reason);
    
    /**
     * @dev Epoch 보상 지급 이벤트
     */
    event EpochRewardsDistributed(uint256 indexed epoch, uint256 totalAmount);
    
    // ============ 모디파이어 ============
    
    /**
     * @dev Validator만 호출 가능하도록 제한하는 모디파이어
     * 
     * 사용법:
     * function requestWithdrawal() external onlyValidator { ... }
     * 
     * 동작:
     * 1. msg.sender가 등록된 Validator인지 확인
     * 2. 아니면 에러 발생, 맞으면 함수 실행 계속
     */
    modifier onlyValidator() {
        require(
            validators[msg.sender].validatorAddress != address(0),
            "Not a validator"
        );
        _;
    }
    
    // ============ 생성자 ============
    
    /**
     * @dev 컨트랙트 배포 시 실행
     * 
     * 이더리움:
     * - Beacon Chain이 자동으로 모든 것을 처리
     * 
     * 우리:
     * - Backend가 Beacon Chain 역할을 수행하여 자동으로 호출
     * - Admin 없음 (이더리움과 동일)
     */
    constructor() {
        currentEpoch = 0;
    }
    
    // ============ 주요 함수 ============
    
    /**
     * @dev 스테이킹 예치
     * 
     * 사용자가 DSTN을 스테이킹하여 Validator가 되기 위한 첫 단계입니다.
     * 
     * 사용법:
     * - 트랜잭션과 함께 DSTN을 전송 (msg.value)
     * - 예: deposit{value: 32 * 10^18}()
     * 
     * 조건:
     * - 최소 32 DSTN 예치 필요
     * - 이미 등록된 Validator는 추가 예치 가능
     * - 최대 Validator 수 제한 확인
     * 
     * 동작:
     * 1. 예치 금액 확인 (최소 32 DSTN)
     * 2. 새 Validator인 경우 등록 (Pending 상태)
     * 3. 기존 Validator인 경우 추가 예치
     * 4. 전체 스테이킹 금액 증가
     * 5. 이벤트 발생
     * 
     * 예시:
     * - Alice가 32 DSTN 예치
     *   → validators[Alice] = {status: Pending, stakedAmount: 32 * 10^18, ...}
     *   → Backend에서 activateValidator() 호출하여 활성화
     * 
     * - Alice가 추가로 16 DSTN 예치
     *   → validators[Alice].stakedAmount = 48 * 10^18
     * 
     * 이더리움:
     * - Deposit Contract에 32 ETH 예치
     * - Validator 등록 정보 제출
     * - Activation Queue 대기
     */
    function deposit() external payable {
        require(msg.value >= MIN_STAKE, "Minimum stake is 32 DSTN");
        require(
            validatorList.length < MAX_VALIDATORS || 
            validators[msg.sender].validatorAddress != address(0),
            "Maximum validators reached"
        );
        
        Validator storage validator = validators[msg.sender];
        
        if (validator.validatorAddress == address(0)) {
            // 새 Validator 등록
            validator.validatorAddress = msg.sender;
            validator.stakedAmount = msg.value;
            validator.status = ValidatorStatus.pending_initialized; // 초기화 대기
            validator.withdrawalAddress = msg.sender; // 기본값: Validator 주소
            validatorList.push(msg.sender);
            
            // 테스트 편의: 최소 금액 충족 시 자동으로 pending_queued로 전환
            // (실제로는 Backend에서 activateValidator() 호출 필요)
            if (msg.value >= MIN_STAKE) {
                validator.status = ValidatorStatus.pending_queued; // 활성화 대기열
            }
        } else {
            // 기존 Validator 추가 예치
            require(
                validator.status == ValidatorStatus.active_ongoing ||
                validator.status == ValidatorStatus.pending_queued ||
                validator.status == ValidatorStatus.pending_initialized,
                "Cannot deposit in current status"
            );
            validator.stakedAmount += msg.value;
        }
        
        totalStaked += msg.value;
        
        emit Deposited(msg.sender, msg.value);
    }
    
    /**
     * @dev 출금 주소 설정
     * 
     * 이더리움:
     * - Validator 등록 시 Withdrawal Address 설정
     * - 출금은 이 주소로 자동 전송
     * 
     * 우리:
     * - 동일하게 구현
     * - Validator가 출금 주소를 설정 가능
     * 
     * @param _withdrawalAddress 출금 주소
     */
    function setWithdrawalAddress(address _withdrawalAddress) external onlyValidator {
        require(_withdrawalAddress != address(0), "Cannot set to zero address");
        require(
            validators[msg.sender].status == ValidatorStatus.active_ongoing ||
            validators[msg.sender].status == ValidatorStatus.pending_queued ||
            validators[msg.sender].status == ValidatorStatus.pending_initialized,
            "Cannot set withdrawal address in current status"
        );
        
        validators[msg.sender].withdrawalAddress = _withdrawalAddress;
    }
    
    /**
     * @dev Validator 활성화 (Backend에서 호출)
     * 
     * 이더리움:
     * - Beacon Chain이 Activation Queue에서 순서대로 활성화
     * 
     * 우리:
     * - Backend가 Pending 상태의 Validator를 활성화
     * 
     * 조건:
     * - Pending 상태
     * - 최소 스테이킹 금액 충족
     * 
     * @param validatorAddress 활성화할 Validator 주소
     */
    /**
     * @dev Validator 활성화 (Backend에서 호출)
     * 
     * 이더리움:
     * - Beacon Chain이 Activation Queue에서 순서대로 활성화
     * - pending_queued → active_ongoing
     * 
     * 우리:
     * - Backend가 pending_queued 상태의 Validator를 활성화
     * - 테스트 편의: pending_initialized도 바로 active_ongoing으로 활성화 가능
     * 
     * 조건:
     * - pending_queued 또는 pending_initialized 상태
     * - 최소 스테이킹 금액 충족
     * 
     * @param validatorAddress 활성화할 Validator 주소
     */
    function activateValidator(address validatorAddress) external {
        Validator storage validator = validators[validatorAddress];
        require(
            validator.status == ValidatorStatus.pending_queued ||
            validator.status == ValidatorStatus.pending_initialized,
            "Not in pending status"
        );
        require(validator.stakedAmount >= MIN_STAKE, "Insufficient stake");
        
        validator.status = ValidatorStatus.active_ongoing;
        validator.activatedAt = block.timestamp;
        
        emit Activated(validatorAddress, block.timestamp);
    }
    
    /**
     * @dev 출금 요청
     * 
     * 이더리움:
     * - Validator가 출금 요청 트랜잭션 제출
     * - Exit Queue에 등록
     * - 대기 시간 경과 후 자동으로 Withdrawal Address로 전송
     * 
     * 우리:
     * - 동일하게 구현
     * - 출금 요청 후 Withdrawal Queue에 등록
     * - processWithdrawals()에서 자동 처리
     * 
     * 동작:
     * 1. Active 상태 확인
     * 2. Exiting 상태로 변경
     * 3. exitRequestedAt 타임스탬프 기록
     * 4. Withdrawal Queue에 추가
     * 5. 이벤트 발생
     */
    function requestWithdrawal() external onlyValidator {
        Validator storage validator = validators[msg.sender];
        require(validator.status == ValidatorStatus.active_ongoing, "Not active");
        
        validator.status = ValidatorStatus.active_exiting;
        validator.exitRequestedAt = block.timestamp;
        withdrawalQueue.push(msg.sender);
        
        emit WithdrawalRequested(msg.sender, block.timestamp);
    }
    
    /**
     * @dev 출금 처리 (Backend에서 주기적으로 호출)
     * 
     * 이더리움:
     * - Beacon Chain이 Withdrawal Queue를 순서대로 처리
     * - 대기 시간 경과한 Validator의 자산을 Withdrawal Address로 자동 전송
     * 
     * 우리:
     * - 동일하게 구현
     * - Backend가 주기적으로 호출하여 자동 처리
     * 
     * 동작:
     * 1. Withdrawal Queue를 순회
     * 2. 대기 시간 경과한 Validator 확인
     * 3. 출금 가능 금액 계산 (stakedAmount - slashedAmount)
     * 4. Withdrawal Address로 자동 전송
     * 5. 상태를 Withdrawn으로 변경
     * 6. Validator 목록에서 제거
     * 
     * @param maxProcess 최대 처리할 출금 요청 수 (가스 제한 방지)
     * @return processed 처리된 출금 요청 수
     */
    function processWithdrawals(uint256 maxProcess) external returns (uint256 processed) {
        uint256 queueLength = withdrawalQueue.length;
        if (queueLength == 0) {
            return 0;
        }
        
        uint256 toProcess = queueLength < maxProcess ? queueLength : maxProcess;
        
        for (uint256 i = 0; i < toProcess; i++) {
            address validatorAddress = withdrawalQueue[i];
            Validator storage validator = validators[validatorAddress];
            
            // 대기 시간 경과 확인
            if (block.timestamp < validator.exitRequestedAt + WITHDRAWAL_DELAY) {
                continue; // 아직 대기 시간 미경과
            }
            
            // 출금 가능 금액 계산
            uint256 withdrawableAmount = validator.stakedAmount - validator.slashedAmount;
            
            if (withdrawableAmount > 0 && validator.status == ValidatorStatus.active_exiting) {
                // 상태 변경: active_exiting → exited_withdrawable → exited_withdrawn
                validator.status = ValidatorStatus.exited_withdrawn;
                validator.stakedAmount = 0;
                totalStaked -= withdrawableAmount;
                
                // Withdrawal Address로 자동 전송
                address to = validator.withdrawalAddress;
                (bool success, ) = payable(to).call{value: withdrawableAmount}("");
                require(success, "Withdrawal transfer failed");
                
                // Validator 목록에서 제거
                _removeFromValidatorList(validatorAddress);
                
                emit Withdrawn(validatorAddress, to, withdrawableAmount);
                processed++;
            }
        }
        
        // 처리된 항목들을 Queue에서 제거
        if (processed > 0) {
            // 배열을 앞으로 당기기
            for (uint256 i = 0; i < queueLength - processed; i++) {
                withdrawalQueue[i] = withdrawalQueue[i + processed];
            }
            // 배열 크기 줄이기
            for (uint256 i = 0; i < processed; i++) {
                withdrawalQueue.pop();
            }
        }
        
        return processed;
    }
    
    /**
     * @dev Proposer 보상 지급 (Backend에서 호출)
     * 
     * 이더리움:
     * - 블록 생성 시 즉시 Proposer에게 보상 지급
     * 
     * 우리:
     * - 동일하게 구현
     * - 블록 생성 시마다 Backend가 호출
     * 
     * @param validatorAddress Proposer 주소
     * @param amount 보상 금액 (Wei)
     */
    function rewardProposer(address validatorAddress, uint256 amount) external {
        Validator storage validator = validators[validatorAddress];
        require(validator.status == ValidatorStatus.active_ongoing, "Not active");
        require(amount > 0, "Amount must be greater than 0");
        
        validator.totalRewards += amount;
        totalRewards += amount;
        
        // 즉시 지급 (이더리움과 동일)
        (bool success, ) = payable(validatorAddress).call{value: amount}("");
        require(success, "Proposer reward transfer failed");
        
        emit Rewarded(validatorAddress, amount, "proposer");
    }
    
    /**
     * @dev Committee 보상 누적 (Backend에서 호출)
     * 
     * 이더리움:
     * - Attestation 제출 시 보상을 Epoch 단위로 누적
     * - Epoch 완료 시 일괄 지급
     * 
     * 우리:
     * - 동일하게 구현
     * - Attestation 제출 시마다 Backend가 호출하여 누적
     * - distributeEpochRewards()에서 일괄 지급
     * 
     * @param epoch Epoch 번호
     * @param validatorAddress Committee 멤버 주소
     * @param amount 보상 금액 (Wei)
     */
    function accumulateCommitteeReward(
        uint256 epoch,
        address validatorAddress,
        uint256 amount
    ) external {
        Validator storage validator = validators[validatorAddress];
        require(validator.status == ValidatorStatus.active_ongoing, "Not active");
        require(amount > 0, "Amount must be greater than 0");
        
        // Epoch별로 누적
        epochRewards[epoch][validatorAddress] += amount;
    }
    
    /**
     * @dev Epoch 보상 일괄 지급 (Backend에서 호출)
     * 
     * 이더리움:
     * - Epoch 완료 시 모든 Committee 보상을 일괄 지급
     * 
     * 우리:
     * - 동일하게 구현
     * - Epoch 완료 시 Backend가 호출
     * 
     * @param epoch 지급할 Epoch 번호
     * @param validatorAddresses 지급할 Validator 주소 배열
     * @return totalDistributed 총 지급된 보상
     */
    function distributeEpochRewards(
        uint256 epoch,
        address[] calldata validatorAddresses
    ) external returns (uint256 totalDistributed) {
        for (uint256 i = 0; i < validatorAddresses.length; i++) {
            address validatorAddress = validatorAddresses[i];
            uint256 reward = epochRewards[epoch][validatorAddress];
            
            if (reward > 0) {
                Validator storage validator = validators[validatorAddress];
                
                // Active 상태인 경우만 지급
                if (validator.status == ValidatorStatus.active_ongoing) {
                    validator.totalRewards += reward;
                    totalRewards += reward;
                    totalDistributed += reward;
                    
                    // Withdrawal Address로 지급 (이더리움과 동일)
                    (bool success, ) = payable(validator.withdrawalAddress).call{value: reward}("");
                    require(success, "Epoch reward transfer failed");
                    
                    // 누적 보상 초기화
                    epochRewards[epoch][validatorAddress] = 0;
                }
            }
        }
        
        emit EpochRewardsDistributed(epoch, totalDistributed);
        return totalDistributed;
    }
    
    /**
     * @dev 슬래싱 (Backend에서 호출)
     * 
     * 이더리움:
     * - Double Vote: 같은 슬롯에 두 개의 Attestation
     * - Surround Vote: 잘못된 체인 선택
     * - 페널티: 스테이킹 금액의 1/32 (약 3.125%)
     * 
     * 우리:
     * - 동일하게 구현
     * 
     * 슬래싱 조건:
     * - Double Vote: 같은 슬롯에 두 개의 Attestation 제출
     * - Surround Vote: 잘못된 체인 선택
     * 
     * 페널티:
     * - 스테이킹 금액의 1/32 (약 3.125%)
     * - 슬래싱된 금액은 소각 (또는 슬래싱 풀로 이동)
     * 
     * @param validatorAddress 슬래싱할 Validator 주소
     * @param amount 슬래싱 금액 (Wei)
     * @param reason 슬래싱 사유
     */
    function slashValidator(
        address validatorAddress,
        uint256 amount,
        string memory reason
    ) external {
        Validator storage validator = validators[validatorAddress];
        require(validator.status == ValidatorStatus.active_ongoing, "Not active");
        require(amount <= validator.stakedAmount, "Amount exceeds stake");
        
        validator.slashedAmount += amount;
        validator.stakedAmount -= amount;
        totalStaked -= amount;
        totalSlashed += amount;
        
        // 슬래싱된 금액은 소각 (이더리움과 동일)
        // address(0)으로 전송하여 소각
        (bool success, ) = payable(address(0)).call{value: amount}("");
        require(success, "Slashing burn failed");
        
        emit Slashed(validatorAddress, amount, reason);
    }
    
    // ============ 조회 함수 ============
    
    /**
     * @dev Validator 정보 조회
     * 
     * @param validatorAddress 조회할 Validator 주소
     * @return Validator 정보 구조체
     */
    function getValidator(address validatorAddress)
        external
        view
        returns (Validator memory)
    {
        return validators[validatorAddress];
    }
    
    /**
     * @dev 활성 Validator 목록 조회
     * 
     * @return 활성 Validator 주소 배열
     */
    function getActiveValidators() external view returns (address[] memory) {
        // 먼저 개수 계산
        uint256 count = 0;
        for (uint256 i = 0; i < validatorList.length; i++) {
            if (validators[validatorList[i]].status == ValidatorStatus.active_ongoing) {
                count++;
            }
        }
        
        // 정확한 크기로 배열 생성
        address[] memory active = new address[](count);
        uint256 index = 0;
        
        for (uint256 i = 0; i < validatorList.length; i++) {
            if (validators[validatorList[i]].status == ValidatorStatus.active_ongoing) {
                active[index] = validatorList[i];
                index++;
            }
        }
        
        return active;
    }
    
    /**
     * @dev Pending Validator 목록 조회
     * 
     * pending_queued 또는 pending_initialized 상태의 Validator 목록을 반환합니다.
     * Activation Queue 처리에 사용됩니다.
     * 
     * 이더리움:
     * - Activation Queue에 대기 중인 Validator 목록
     * - Churn Limit에 따라 Epoch마다 활성화됨
     * 
     * @return Pending Validator 주소 배열
     */
    function getPendingValidators() external view returns (address[] memory) {
        // 먼저 개수 계산
        uint256 count = 0;
        for (uint256 i = 0; i < validatorList.length; i++) {
            ValidatorStatus status = validators[validatorList[i]].status;
            if (
                status == ValidatorStatus.pending_queued ||
                status == ValidatorStatus.pending_initialized
            ) {
                count++;
            }
        }
        
        // 정확한 크기로 배열 생성
        address[] memory pending = new address[](count);
        uint256 index = 0;
        
        for (uint256 i = 0; i < validatorList.length; i++) {
            ValidatorStatus status = validators[validatorList[i]].status;
            if (
                status == ValidatorStatus.pending_queued ||
                status == ValidatorStatus.pending_initialized
            ) {
                pending[index] = validatorList[i];
                index++;
            }
        }
        
        return pending;
    }
    
    /**
     * @dev 출금 대기열 조회
     * 
     * @return 출금 대기 중인 Validator 주소 배열
     */
    function getWithdrawalQueue() external view returns (address[] memory) {
        return withdrawalQueue;
    }
    
    /**
     * @dev 전체 통계 조회
     * 
     * @return _totalStaked 전체 스테이킹 금액
     * @return _totalRewards 전체 지급된 보상
     * @return _totalSlashed 전체 슬래싱된 금액
     * @return activeCount 활성 Validator 수
     * @return pendingCount 대기 중인 Validator 수
     */
    function getStats()
        external
        view
        returns (
            uint256 _totalStaked,
            uint256 _totalRewards,
            uint256 _totalSlashed,
            uint256 activeCount,
            uint256 pendingCount
        )
    {
        _totalStaked = totalStaked;
        _totalRewards = totalRewards;
        _totalSlashed = totalSlashed;
        
        uint256 listLength = validatorList.length;
        for (uint256 i = 0; i < listLength; i++) {
            ValidatorStatus status = validators[validatorList[i]].status;
            if (status == ValidatorStatus.active_ongoing) {
                activeCount++;
            } else if (
                status == ValidatorStatus.pending_queued ||
                status == ValidatorStatus.pending_initialized
            ) {
                pendingCount++;
            }
        }
    }
    
    /**
     * @dev validatorList 길이 조회 (디버깅용)
     */
    function getValidatorListLength() external view returns (uint256) {
        return validatorList.length;
    }
    
    // ============ 내부 함수 ============
    
    /**
     * @dev Validator 목록에서 제거 (내부 함수)
     * 
     * @param validatorAddress 제거할 Validator 주소
     */
    function _removeFromValidatorList(address validatorAddress) private {
        for (uint256 i = 0; i < validatorList.length; i++) {
            if (validatorList[i] == validatorAddress) {
                validatorList[i] = validatorList[validatorList.length - 1];
                validatorList.pop();
                break;
            }
        }
    }
    
    // ============ 특수 함수 ============
    
    /**
     * @dev 컨트랙트가 DSTN을 받을 수 있도록 하는 함수
     * 
     * 이더리움에서 컨트랙트가 네이티브 토큰(ETH/DSTN)을 받으려면
     * receive() 또는 fallback() 함수가 필요합니다.
     * 
     * 하지만 여기서는 deposit()을 통해서만 예치하도록 강제합니다.
     * 직접 전송하면 에러가 발생합니다.
     */
    receive() external payable {
        revert("Use deposit() to stake");
    }
}

