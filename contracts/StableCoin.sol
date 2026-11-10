// SPDX-License-Identifier: MIT
pragma solidity ^0.8.0;

/**
 * @title StableCoin
 * @dev ERC20 기반 스테이블 코인 토큰 (USDST)
 * 
 * 이 컨트랙트는 담보형 스테이블 코인 시스템의 토큰 컨트랙트입니다.
 * 
 * 역할:
 * - 스테이블 코인 발행 및 소각
 * - 표준 ERC20 기능 제공 (송금, 승인 등)
 * 
 * 권한:
 * - mint/burn: Vault 컨트랙트만 호출 가능 (일반 사용자는 불가능)
 * - transfer/approve: 모든 사용자 가능 (일반 토큰처럼 사용 가능)
 * 
 * 예시:
 * - 사용자가 담보를 예치하고 스테이블 코인을 발행하면, Vault가 mint() 호출
 * - 사용자가 스테이블 코인을 상환하면, Vault가 burn() 호출
 */
contract StableCoin {
    // ============ 상태 변수 ============
    
    /**
     * @dev 토큰 이름 (예: "USD Stable Token")
     */
    string public name = "USD Stable Token";
    
    /**
     * @dev 토큰 심볼 (예: "USDST")
     */
    string public symbol = "USDST";
    
    /**
     * @dev 소수점 자릿수 (18 = 이더리움 표준)
     * 예: 1 USDST = 1 * 10^18 (Wei 단위)
     */
    uint8 public constant decimals = 18;
    
    /**
     * @dev 총 발행량 (Wei 단위)
     * mint() 호출 시 증가, burn() 호출 시 감소
     */
    uint256 public totalSupply;

    /**
     * @dev Vault 컨트랙트 주소
     * 이 주소만 mint()와 burn()을 호출할 수 있습니다.
     * 초기값은 address(0), 나중에 setVault()로 설정
     */
    address public vault = address(0);

    /**
     * @dev 각 주소의 토큰 잔액
     * mapping(주소 => 잔액)
     * 예: balanceOf[0x123...] = 1000 * 10^18 (1000 USDST)
     */
    mapping(address => uint256) public balanceOf;
    
    /**
     * @dev 승인된 양 (ERC20 표준)
     * mapping(소유자 => mapping(승인받은주소 => 양))
     * 예: allowance[Alice][DEX] = 500 * 10^18
     *     → DEX가 Alice의 500 USDST를 대신 전송할 수 있음
     */
    mapping(address => mapping(address => uint256)) public allowance;

    // ============ 이벤트 ============
    
    /**
     * @dev 토큰 전송 이벤트 (ERC20 표준)
     */
    event Transfer(address indexed from, address indexed to, uint256 value);
    
    /**
     * @dev 승인 이벤트 (ERC20 표준)
     */
    event Approval(address indexed owner, address indexed spender, uint256 value);
    
    /**
     * @dev 토큰 발행 이벤트
     */
    event Mint(address indexed to, uint256 amount);
    
    /**
     * @dev 토큰 소각 이벤트
     */
    event Burn(address indexed from, uint256 amount);
    
    /**
     * @dev Vault 주소 변경 이벤트
     */
    event VaultSet(address indexed oldVault, address indexed newVault);

    // ============ 모디파이어 ============
    
    /**
     * @dev Vault만 호출 가능하도록 제한하는 모디파이어
     * 
     * 사용법:
     * function mint(...) external onlyVault { ... }
     * 
     * 동작:
     * 1. msg.sender가 vault 주소와 같은지 확인
     * 2. 다르면 에러 발생, 같으면 함수 실행 계속
     */
    modifier onlyVault() {
        require(msg.sender == vault, "Only vault can call this");
        _; // 여기서 실제 함수 코드가 실행됨
    }

    // ============ 생성자 ============
    
    /**
     * @dev 컨트랙트 배포 시 한 번만 실행되는 함수
     * 
     * 생성자 파라미터 없이 기본값으로 배포됩니다.
     * - name: "USD Stable Token"
     * - symbol: "USDST"
     * - vault: address(0) (나중에 setVault()로 설정)
     * 
     * 배포 순서:
     * 1. StableCoin 배포 (파라미터 없이)
     * 2. CollateralVault 배포 (StableCoin 주소 전달)
     * 3. StableCoin.setVault(CollateralVault 주소) 호출
     */
    constructor() {
        totalSupply = 0; // 초기 발행량은 0
    }

    // ============ Vault 전용 함수 ============
    
    /**
     * @dev 토큰 발행 (Vault만 호출 가능)
     * 
     * 사용 시나리오:
     * 1. 사용자가 담보를 예치하고 스테이블 코인을 발행 요청
     * 2. Vault가 담보비율 검증 후 이 함수 호출
     * 3. 사용자에게 토큰 발행
     * 
     * @param to 발행받을 주소 (보통 사용자 주소)
     * @param amount 발행할 양 (Wei 단위, 예: 1000 * 10^18 = 1000 USDST)
     * 
     * 동작:
     * 1. to 주소가 유효한지 확인 (0 주소 불가)
     * 2. totalSupply 증가
     * 3. to의 balanceOf 증가
     * 4. 이벤트 발생
     */
    function mint(address to, uint256 amount) external onlyVault {
        require(to != address(0), "Cannot mint to zero address");
        
        totalSupply += amount; // 총 발행량 증가
        balanceOf[to] += amount; // 받는 사람의 잔액 증가
        
        emit Mint(to, amount);
        emit Transfer(address(0), to, amount); // address(0)에서 발행되는 것처럼 표시
    }

    /**
     * @dev 토큰 소각 (Vault만 호출 가능)
     * 
     * 사용 시나리오:
     * 1. 사용자가 스테이블 코인을 상환 요청
     * 2. Vault가 이 함수 호출하여 토큰 소각
     * 3. 사용자의 부채 감소
     * 
     * @param from 소각할 주소 (보통 사용자 주소)
     * @param amount 소각할 양 (Wei 단위)
     * 
     * 동작:
     * 1. from의 잔액이 충분한지 확인
     * 2. totalSupply 감소
     * 3. from의 balanceOf 감소
     * 4. 이벤트 발생
     */
    function burn(address from, uint256 amount) external onlyVault {
        require(balanceOf[from] >= amount, "Insufficient balance");
        
        totalSupply -= amount; // 총 발행량 감소
        balanceOf[from] -= amount; // 소각하는 사람의 잔액 감소
        
        emit Burn(from, amount);
        emit Transfer(from, address(0), amount); // address(0)으로 전송되는 것처럼 표시
    }

    /**
     * @dev Vault 주소 설정
     * 
     * 사용 시나리오:
     * 1. StableCoin을 먼저 배포 (vault = address(0))
     * 2. CollateralVault 배포
     * 3. 이 함수로 CollateralVault 주소 설정
     * 
     * @param _vault 새로운 Vault 컨트랙트 주소
     * 
     * 조건:
     * - 현재 vault가 address(0)이어야 함 (한 번만 설정 가능)
     * - _vault가 address(0)이 아니어야 함
     * 
     * 동작:
     * 1. 현재 vault가 0인지 확인
     * 2. 새로운 vault 주소 유효성 확인
     * 3. vault 주소 업데이트
     * 4. 이벤트 발생
     */
    function setVault(address _vault) external {
        require(vault == address(0), "Vault already set");
        require(_vault != address(0), "Cannot set vault to zero address");
        
        address oldVault = vault;
        vault = _vault;
        
        emit VaultSet(oldVault, _vault);
    }

    // ============ ERC20 표준 함수 ============
    
    /**
     * @dev ERC20 transfer - 직접 송금
     * 
     * 사용 예시:
     * Alice가 Bob에게 100 USDST 전송
     * → transfer(Bob주소, 100 * 10^18)
     * 
     * @param to 받는 사람 주소
     * @param amount 전송할 양 (Wei 단위)
     * @return 성공 여부 (항상 true, 실패 시 revert)
     * 
     * 동작:
     * 1. to 주소 유효성 확인
     * 2. 보내는 사람의 잔액 확인
     * 3. 잔액 이동 (from -= amount, to += amount)
     * 4. 이벤트 발생
     */
    function transfer(address to, uint256 amount) external returns (bool) {
        require(to != address(0), "Cannot transfer to zero address");
        require(balanceOf[msg.sender] >= amount, "Insufficient balance");

        // 잔액 이동
        balanceOf[msg.sender] -= amount; // 보내는 사람 잔액 감소
        balanceOf[to] += amount; // 받는 사람 잔액 증가

        emit Transfer(msg.sender, to, amount);
        return true;
    }

    /**
     * @dev ERC20 transferFrom - 대리 전송 (승인 후)
     * 
     * 사용 시나리오:
     * 1. Alice가 DEX에 500 USDST 사용 승인
     *    → approve(DEX주소, 500 * 10^18)
     * 2. DEX가 Alice 대신 Bob에게 300 USDST 전송
     *    → transferFrom(Alice주소, Bob주소, 300 * 10^18)
     * 3. Alice의 allowance[DEX]가 300 감소 (500 → 200)
     * 
     * @param from 보내는 사람 주소
     * @param to 받는 사람 주소
     * @param amount 전송할 양 (Wei 단위)
     * @return 성공 여부
     * 
     * 동작:
     * 1. 주소 유효성 확인
     * 2. from의 잔액 확인
     * 3. msg.sender(호출자)가 from으로부터 승인받은 양 확인
     * 4. 잔액 이동 및 allowance 감소
     */
    function transferFrom(
        address from,
        address to,
        uint256 amount
    ) external returns (bool) {
        require(to != address(0), "Cannot transfer to zero address");
        require(balanceOf[from] >= amount, "Insufficient balance");
        require(allowance[from][msg.sender] >= amount, "Insufficient allowance");

        // 잔액 이동
        balanceOf[from] -= amount;
        balanceOf[to] += amount;
        
        // 승인된 양 감소
        allowance[from][msg.sender] -= amount;

        emit Transfer(from, to, amount);
        return true;
    }

    /**
     * @dev ERC20 approve - 다른 주소에게 전송 권한 부여
     * 
     * 사용 예시:
     * Alice가 DEX에 1000 USDST 사용 승인
     * → approve(DEX주소, 1000 * 10^18)
     * → 이제 DEX가 Alice 대신 최대 1000 USDST까지 전송 가능
     * 
     * @param spender 승인할 주소 (예: DEX, 스마트 컨트랙트)
     * @param amount 승인할 양 (Wei 단위)
     * @return 성공 여부
     * 
     * 주의:
     * - 기존 승인을 덮어씁니다 (증가가 아닌 교체)
     * - 보안: 무한대 승인은 위험할 수 있음
     */
    function approve(address spender, uint256 amount) external returns (bool) {
        allowance[msg.sender][spender] = amount; // 승인 양 설정
        emit Approval(msg.sender, spender, amount);
        return true;
    }
}
