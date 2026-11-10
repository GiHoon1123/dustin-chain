package io.github.gihoon1123.chain

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

@Component
class LocalChainBootstrap(
    private val chain: ChainService,
    private val contracts: ContractService,
    @Value("\${chain.accounts-file:}") private val accountsFile: String,
    @Value("\${chain.bootstrap-contracts:true}") private val enabled: Boolean,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun initialize() {
        if (!enabled || accountsFile.isBlank()) return
        val logger = org.slf4j.LoggerFactory.getLogger(javaClass)
        runCatching {
            val deployed = contracts.deployments()
            val staking = (deployed?.get("staking") as? Map<*, *>)?.get("address") as? String
            if (staking == null || chain.bytecode(staking) == "0x") contracts.deployStaking()
            val stablecoin = (deployed?.get("stablecoin") as? Map<*, *>)?.get("address") as? String
            if (stablecoin == null || chain.bytecode(stablecoin) == "0x") contracts.deployStablecoin()
            val accounts =
                JsonMapper
                    .builder()
                    .build()
                    .readTree(Files.readString(Path.of(accountsFile)))
                    .toList()
                    .take(10)
            for (account in accounts) {
                val key = account["privateKey"].asString()
                val address = Crypto.address(key)
                val current = Abi.words(contracts.read("staking", "getValidator(address)", address))
                if (Abi.address(current[0]) != "0x" + "00".repeat(20)) continue
                if (chain.account(address).balance < WEI * BigInteger.valueOf(32)) continue
                chain.systemCall(contracts.contract("staking"), Abi.call("deposit()"), address, WEI * BigInteger.valueOf(32))
                chain.systemCall(
                    contracts.contract("staking"),
                    Abi.call("activateValidator(address)", address),
                    Crypto.address(contracts.key(255)),
                )
            }
            logger.info("Local contract bootstrap and genesis validator registration finished")
        }.onFailure { logger.error("Local contract bootstrap failed; verify configured development accounts and bytecodes", it) }
    }
}
