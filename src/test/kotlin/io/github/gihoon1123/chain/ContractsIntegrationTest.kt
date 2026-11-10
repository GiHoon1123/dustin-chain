package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

class ContractsIntegrationTest {
    @TempDir lateinit var directory: Path
    private val key = "0x" + "00".repeat(31) + "01"
    private val deployer = "0x" + "00".repeat(31) + "02"

    private fun contracts(chain: ChainService): ContractService {
        val keys = directory.resolve("genesis-accounts.json")
        Files.writeString(
            keys,
            JsonMapper.builder().build().writeValueAsString(
                listOf(
                    mapOf("index" to 0, "privateKey" to key),
                    mapOf(
                        "index" to 255,
                        "privateKey" to deployer,
                    ),
                ),
            ),
        )
        return ContractService(
            chain,
            CompilerService("unconfigured-solc"),
            keys.toString(),
            directory.resolve("deployed-contracts.json").toString(),
        )
    }

    @Test fun `original Solidity staking bytecode accepts deposit and exposes validator tuple`() {
        val chain = ChainService(directory.resolve("state").toString(), "", false)
        try {
            chain.addBalance(Crypto.address(key), WEI * BigInteger.valueOf(100))
            chain.addBalance(Crypto.address(deployer), WEI * BigInteger.valueOf(100))
            val contracts = contracts(chain)
            val deployed = contracts.deployStaking()
            assertEquals(ZERO_HASH, deployed["stakingTxHash"])
            val submitted = contracts.submit(contracts.contract("staking"), Abi.call("deposit()"), key, WEI * BigInteger.valueOf(32))
            chain.produce()
            assertEquals("0x1", chain.receipt(submitted.getValue("hash"))["status"])
            val words = Abi.words(contracts.read("staking", "getValidator(address)", Crypto.address(key)))
            assertEquals(Crypto.address(key), Abi.address(words[0]))
            assertEquals(WEI * BigInteger.valueOf(32), Abi.number(words[1]))
            assertEquals(BigInteger.ONE, Abi.number(words[2]))
        } finally {
            chain.close()
        }
    }

    @Test fun `original stablecoin vault deploy link collateral mint and balance work through Cancun VM`() {
        val chain = ChainService(directory.resolve("state").toString(), "", false)
        try {
            chain.addBalance(Crypto.address(key), WEI * BigInteger.valueOf(100))
            chain.addBalance(Crypto.address(deployer), WEI * BigInteger.valueOf(100))
            val contracts = contracts(chain)
            contracts.deployStablecoin()
            val deposit = contracts.submit(contracts.contract("vault"), Abi.call("depositCollateral()"), key, WEI * BigInteger.TEN)
            chain.produce()
            assertEquals("0x1", chain.receipt(deposit.getValue("hash"))["status"])
            val mint = contracts.submit(contracts.contract("vault"), Abi.call("mintStablecoin(uint256)", WEI), key)
            chain.produce()
            assertEquals("0x1", chain.receipt(mint.getValue("hash"))["status"])
            assertEquals(WEI, contracts.read("stablecoin", "balanceOf(address)", Crypto.address(key)).number())
            val position = Abi.words(contracts.read("vault", "getPosition(address)", Crypto.address(key)))
            assertEquals(WEI * BigInteger.TEN, Abi.number(position[0]))
            assertEquals(WEI, Abi.number(position[1]))
        } finally {
            chain.close()
        }
    }

    @Test fun `staking lifecycle activates deposited validator and persists contract across restart`() {
        val location = directory.resolve("state").toString()
        var chain = ChainService(location, "", false)
        val contractService = contracts(chain)
        chain.addBalance(Crypto.address(key), WEI * BigInteger.valueOf(100))
        chain.addBalance(Crypto.address(deployer), WEI * BigInteger.valueOf(100))
        contractService.deployStaking()
        val staking = StakingService(chain, contractService, location)
        try {
            staking.connect()
            contractService.submit(contractService.contract("staking"), Abi.call("deposit()"), key, WEI * BigInteger.valueOf(32))
            chain.produce()
            assertEquals(listOf(Crypto.address(key)), staking.activeAddresses())
            assertEquals("active_ongoing", staking.validator(Crypto.address(key))["status"])
            assertEquals(BigInteger.valueOf(2) * WEI, staking.reward(chain.latest().proposer))
            assertEquals(Crypto.address(key), chain.validators.single())
        } finally {
            staking.close()
            chain.close()
        }
        chain = ChainService(location, "", false)
        try {
            val restored = contracts(chain)
            assertEquals(listOf(Crypto.address(key)), Abi.addresses(restored.read("staking", "getActiveValidators()")))
            assertEquals(
                WEI * BigInteger.valueOf(32),
                Abi.number(Abi.words(restored.read("staking", "getValidator(address)", Crypto.address(key)))[1]),
            )
        } finally {
            chain.close()
        }
    }

    @Test fun `ABI selector and fixed width values match Ethereum wire format`() {
        assertEquals(
            "0xa9059cbb" + Crypto.address(key).removePrefix("0x").padStart(64, '0') + "1".padStart(64, '0'),
            Abi.call("transfer(address,uint256)", Crypto.address(key), BigInteger.ONE),
        )
        assertTrue(Abi.addresses("0x" + Abi.word(32) + Abi.word(1) + Abi.word(Crypto.address(key))).contains(Crypto.address(key)))
    }
}
