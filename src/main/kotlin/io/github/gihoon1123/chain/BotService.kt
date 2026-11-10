package io.github.gihoon1123.chain

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Service
class BotService(
    private val chain: ChainService,
    private val contracts: ContractService,
    @Value("\${chain.accounts-file:}") accountsFile: String,
    @Value("\${chain.bot-enabled:true}") enabled: Boolean,
) {
    private data class Wallet(
        val index: Int,
        val key: String,
        val address: String,
    )

    private val mapper = JsonMapper.builder().build()
    private val wallets =
        if (accountsFile.isBlank()) {
            emptyList()
        } else {
            mapper.readTree(Files.readString(Path.of(accountsFile))).toList().mapIndexed { i, node ->
                val key = node["privateKey"].asString()
                Wallet(node["index"]?.asInt() ?: i, key, Crypto.address(key))
            }
        }
    private val nativeWallets = wallets.filter { it.index in 100..150 }
    private val tokenWallets = wallets.filter { it.index in 151..255 }
    private val tokenBytecodes =
        Path
            .of("token-bytecodes.json")
            .takeIf {
                Files.exists(it)
            }?.let { mapper.readTree(Files.readString(it))["contracts"].toList() }
            .orEmpty()
    private val deployed = linkedMapOf<String, String>()
    private val pendingDeployments = linkedMapOf<String, Pair<String, Wallet>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    @Volatile private var running = enabled
    private var count = 0L
    private var deploymentCount = 0L
    private var tokenCount = 0L

    init {
        scheduler.scheduleAtFixedRate({ safely { generateNative() } }, 20, 20, TimeUnit.SECONDS)
        scheduler.scheduleAtFixedRate({
            safely {
                deployAndDistributeTokens()
                deployRandom()
                transferTokens()
            }
        }, 600, 600, TimeUnit.SECONDS)
    }

    private fun safely(action: () -> Unit) {
        runCatching(action).onFailure {
            org.slf4j.LoggerFactory
                .getLogger(javaClass)
                .warn("Local transaction bot iteration failed: {}", it.message)
        }
    }

    @Synchronized fun start() {
        running = true
    }

    @Synchronized fun stop() {
        running = false
    }

    @Synchronized fun status(): Map<String, Any> =
        linkedMapOf(
            "isRunning" to running,
            "accountCount" to nativeWallets.size,
            "tokenAccountCount" to tokenWallets.size,
            "tokenBytecodesLoaded" to tokenBytecodes.size,
            "deployedTokens" to deployed.map { mapOf("name" to it.key, "address" to it.value) },
            "minBalance" to WEI.toString(),
            "targetRate" to "0.4-0.5 tx/sec",
            "totalTransactions" to count,
            "contractDeployments" to deploymentCount,
            "tokenTransactions" to tokenCount,
        )

    @Synchronized fun generateNative() {
        if (!running || nativeWallets.size < 2) return
        repeat(Random.nextInt(4, 6)) {
            val from = nativeWallets.random()
            val to = nativeWallets.filter { it.address != from.address }.random()
            val amount = BigInteger.valueOf(Random.nextLong(5, 100)) * WEI / BigInteger.TEN
            if (chain.account(from.address).balance > amount + WEI) {
                chain.submit(chain.sign(from.key, to.address, amount))
                count++
            }
        }
    }

    @Synchronized fun deployRandom() {
        if (!running || wallets.none { it.index == 255 }) return
        val catalog = mapper.readTree(Files.readString(Path.of("contract-bytecodes.json")))["contracts"].toList()
        if (catalog.isEmpty()) return
        contracts.deploy(catalog.random()["bytecode"].asString())
        count++
        deploymentCount++
    }

    @Synchronized fun deployAndDistributeTokens() {
        if (!running || tokenWallets.isEmpty()) return
        for (token in tokenBytecodes) {
            val name = token["name"].asString()
            if (name in deployed) continue
            val pending = pendingDeployments[name]
            if (pending == null) {
                val wallet = tokenWallets.random()
                val tx = contracts.submit(null, token["bytecode"].asString(), wallet.key, gas = 5_000_000)
                pendingDeployments[name] = tx.getValue("hash") to wallet
                count++
                deploymentCount++
            } else {
                val receipt = runCatching { chain.receipt(pending.first) }.getOrNull() ?: continue
                pendingDeployments.remove(name)
                val address = receipt["contractAddress"] as? String ?: continue
                deployed[name] = address
                val balance = chain.call(address, Abi.call("balanceOf(address)", pending.second.address)).output.number()
                val amount = balance / BigInteger.valueOf(tokenWallets.size.toLong())
                if (amount.signum() > 0) {
                    for (wallet in tokenWallets.filter { it.address != pending.second.address }) {
                        contracts.submit(address, Abi.call("transfer(address,uint256)", wallet.address, amount), pending.second.key)
                        count++
                        tokenCount++
                    }
                }
            }
        }
    }

    @Synchronized fun transferTokens() {
        if (!running || tokenWallets.size < 2 || deployed.isEmpty()) return
        repeat(5) {
            val address = deployed.values.random()
            val from = tokenWallets.random()
            val to = tokenWallets.filter { it.address != from.address }.random()
            val result = chain.call(address, Abi.call("balanceOf(address)", from.address))
            if (result.success && result.output.number().signum() > 0) {
                contracts.submit(address, Abi.call("transfer(address,uint256)", to.address, BigInteger.ONE), from.key)
                count++
                tokenCount++
            }
        }
    }

    @PreDestroy fun close() {
        scheduler.shutdownNow()
    }
}

@RestController
class BotController(
    private val bot: BotService,
) {
    @GetMapping("/bot/status")
    fun status(): Any = bot.status()

    @PostMapping("/bot/start")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(): Any {
        bot.start()
        return mapOf("success" to true, "message" to "Transaction bot started", "status" to bot.status())
    }

    @PostMapping("/bot/stop")
    @ResponseStatus(HttpStatus.CREATED)
    fun stop(): Any {
        bot.stop()
        return mapOf("success" to true, "message" to "Transaction bot stopped", "status" to bot.status())
    }
}
