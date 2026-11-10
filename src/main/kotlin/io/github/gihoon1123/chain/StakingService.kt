package io.github.gihoon1123.chain

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger
import java.nio.file.Path

@Service
class StakingService(
    private val chain: ChainService,
    private val contracts: ContractService,
    @Value("\${chain.data-dir:data}") directory: String,
) {
    private val db = factory.open(Path.of(directory).resolve("rewards").toFile(), Options().createIfMissing(true))
    private var lastDistributed = -1L
    private var lastWithdrawalBlock = -1L

    @PostConstruct fun connect() {
        chain.validatorProvider = { activeAddresses() }
        chain.afterBlock = { onBlock(it) }
    }

    private fun deployed(): Boolean = contracts.deployments()?.containsKey("staking") == true

    fun reward(address: String): BigInteger =
        db.get("validator:reward:${address.lowercase()}".toByteArray())?.toString(Charsets.UTF_8)?.toBigInteger() ?: BigInteger.ZERO

    private fun addReward(
        address: String,
        amount: BigInteger,
    ) {
        db.createWriteBatch().use { batch ->
            batch.put("validator:reward:${address.lowercase()}".toByteArray(), (reward(address) + amount).toString().toByteArray())
            batch.put("total:rewards".toByteArray(), (totalReward() + amount).toString().toByteArray())
            db.write(batch)
        }
    }

    private fun totalReward(): BigInteger =
        db.get("total:rewards".toByteArray())?.toString(Charsets.UTF_8)?.toBigInteger() ?: BigInteger.ZERO

    fun activeAddresses(): List<String> = if (deployed()) Abi.addresses(contracts.read("staking", "getActiveValidators()")) else emptyList()

    fun validator(address: String): Map<String, Any> {
        val words = Abi.words(contracts.read("staking", "getValidator(address)", address))
        require(words.size == 8) { "Invalid validator result" }
        val states =
            listOf("pending_initialized", "pending_queued", "active_ongoing", "active_exiting", "exited_withdrawable", "exited_withdrawn")
        return linkedMapOf(
            "validatorAddress" to Abi.address(words[0]),
            "stakedAmount" to "0x${words[1]}",
            "status" to (states.getOrNull(Abi.number(words[2]).toInt()) ?: "unknown"),
            "withdrawalAddress" to Abi.address(words[7]),
            "activatedAt" to "0x${words[3]}",
            "exitRequestedAt" to "0x${words[4]}",
            "totalRewards" to reward(address).quantity(),
            "slashedAmount" to "0x${words[6]}",
        )
    }

    fun stats(): Map<String, Any> {
        val words = Abi.words(contracts.read("staking", "getStats()"))
        require(words.size == 5)
        val active = Abi.number(words[3]).intValueExact()
        val pending = Abi.number(words[4]).intValueExact()
        return mapOf(
            "totalStaked" to "0x${words[0]}",
            "totalValidators" to active + pending,
            "activeValidators" to active,
            "totalRewards" to totalReward().quantity(),
            "totalSlashed" to "0x${words[2]}",
            "minStake" to contracts.read("staking", "MIN_STAKE()"),
            "maxValidators" to contracts.read("staking", "MAX_VALIDATORS()").number().intValueExact(),
            "withdrawalDelay" to contracts.read("staking", "WITHDRAWAL_DELAY()"),
        )
    }

    fun submit(
        key: String,
        signature: String,
        args: List<Any> = emptyList(),
        amount: BigInteger = BigInteger.ZERO,
    ): Any = contracts.submit(contracts.contract("staking"), Abi.call(signature, *args.toTypedArray()), key, amount)

    private fun system(
        signature: String,
        vararg args: Any,
    ): Evm.Result = chain.systemCall(contracts.contract("staking"), Abi.call(signature, *args), Crypto.address(contracts.key(255)))

    fun processWithdrawals(maximum: Int): Map<String, Any> {
        require(maximum > 0)
        val submitted = contracts.submit(contracts.contract("staking"), Abi.call("processWithdrawals(uint256)", maximum), contracts.key(0))
        val deadline =
            System.nanoTime() +
                java.util.concurrent.TimeUnit.SECONDS
                    .toNanos(60)
        var receipt: Map<String, Any?>? = null
        while (System.nanoTime() < deadline) {
            receipt = runCatching { chain.receipt(submitted.getValue("hash")) }.getOrNull()
            if (receipt != null) break
            Thread.sleep(250)
        }
        requireNotNull(receipt) { "Transaction confirmation timed out" }
        @Suppress("UNCHECKED_CAST")
        val logs = receipt["logs"] as? List<Map<String, Any?>> ?: emptyList()
        val topic = Crypto.hashUtf8("Withdrawn(address,address,uint256)")
        val count = logs.count { (it["topics"] as? List<*>)?.firstOrNull() == topic }
        return submitted + ("processed" to count)
    }

    private fun onBlock(block: Block) {
        val committee = chain.consensus.committee(chain.consensus.currentSlot(), chain.validators)
        val proposerReward = WEI * BigInteger.TWO
        chain.addBalance(block.proposer, proposerReward)
        addReward(block.proposer, proposerReward)
        val epoch = block.number / 32
        if (committee.isNotEmpty()) {
            val amount = WEI / BigInteger.valueOf(committee.size.toLong())
            for (address in committee) {
                if (deployed()) {
                    val result =
                        runCatching {
                            contracts.submit(
                                contracts.contract("staking"),
                                Abi.call("accumulateCommitteeReward(uint256,address,uint256)", epoch, address, amount),
                                contracts.key(0),
                            )
                        }
                    if (result.isFailure) {
                        chain.addBalance(address, amount)
                        addReward(address, amount)
                    }
                } else {
                    chain.addBalance(address, amount)
                    addReward(address, amount)
                }
            }
        }
        if (!deployed()) return
        if (epoch - 1 >= 0 && epoch - 1 > lastDistributed) {
            val previous = epoch - 1
            val active = activeAddresses()
            for (address in active) {
                val amount = contracts.read("staking", "epochRewards(uint256,address)", previous, address).number()
                if (amount.signum() > 0) {
                    val validator = validator(address)
                    if (validator["status"] == "active_ongoing") {
                        chain.addBalance(validator.getValue("withdrawalAddress") as String, amount)
                        addReward(address, amount)
                    }
                }
            }
            if (active.isNotEmpty()) lastDistributed = previous
        }
        val pending = Abi.addresses(contracts.read("staking", "getPendingValidators()"))
        val churn = maxOf(4, activeAddresses().size / 65536)
        pending.take(churn).forEach { system("activateValidator(address)", it) }
        if (lastWithdrawalBlock < 0 || block.number - lastWithdrawalBlock >= 5) {
            system("processWithdrawals(uint256)", 10)
            lastWithdrawalBlock = block.number
        }
    }

    @PreDestroy fun close() {
        chain.validatorProvider = null
        chain.afterBlock = null
        db.close()
    }
}

@RestController
class StakingController(
    private val staking: StakingService,
) {
    @PostMapping("/staking/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    fun deposit(
        @RequestBody body: Map<String, String>,
    ): Any = staking.submit(requireNotNull(body["privateKey"]), "deposit()", amount = requireNotNull(body["amount"]).number())

    @PostMapping("/staking/set-withdrawal-address")
    @ResponseStatus(HttpStatus.CREATED)
    fun setWithdrawal(
        @RequestBody body: Map<String, String>,
    ): Any =
        staking.submit(
            requireNotNull(body["privateKey"]),
            "setWithdrawalAddress(address)",
            listOf(requireNotNull(body["withdrawalAddress"])),
        )

    @PostMapping("/staking/request-withdrawal")
    @ResponseStatus(HttpStatus.CREATED)
    fun withdraw(
        @RequestBody body: Map<String, String>,
    ): Any = staking.submit(requireNotNull(body["privateKey"]), "requestWithdrawal()")

    @GetMapping("/staking/validator/{address}")
    fun validator(
        @PathVariable address: String,
    ): Any = staking.validator(address)

    @GetMapping("/staking/validators")
    fun validators(): Any {
        val list = staking.activeAddresses().map { staking.validator(it) }
        return mapOf(
            "validators" to list,
            "total" to list.size,
        )
    }

    @GetMapping("/staking/stats")
    fun stats(): Any = staking.stats()

    @PostMapping("/staking/process-withdrawals")
    @ResponseStatus(HttpStatus.CREATED)
    fun process(
        @RequestBody body: Map<String, Int>,
    ): Any =
        staking.processWithdrawals(
            body["maxProcess"] ?: 10,
        )
}
