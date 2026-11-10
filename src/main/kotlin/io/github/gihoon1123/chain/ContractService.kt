package io.github.gihoon1123.chain

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

object Abi {
    fun call(
        signature: String,
        vararg values: Any,
    ): String = Crypto.hashUtf8(signature).take(10) + values.joinToString("") { word(it) }

    fun word(value: Any): String =
        when (value) {
            is BigInteger -> value.toString(16).padStart(64, '0')
            is Number -> BigInteger(value.toString()).toString(16).padStart(64, '0')
            is Boolean -> if (value) "1".padStart(64, '0') else "0".repeat(64)
            is String -> value.removePrefix("0x").padStart(64, '0')
            else -> error("Unsupported ABI static value")
        }

    fun words(value: String): List<String> = value.removePrefix("0x").chunked(64).also { require(it.all { word -> word.length == 64 }) }

    fun number(word: String): BigInteger = BigInteger(word, 16)

    fun address(word: String): String = "0x" + word.takeLast(40)

    fun addresses(value: String): List<String> {
        val words = words(value)
        if (words.isEmpty()) return emptyList()
        val offset = number(words[0]).intValueExact() / 32
        val count = number(words[offset]).intValueExact()
        require(count >= 0 && offset + 1 + count <= words.size)
        return words.drop(offset + 1).take(count).map { address(it) }
    }
}

@Service
class ContractService(
    private val chain: ChainService,
    private val compiler: CompilerService,
    @Value("\${chain.accounts-file:}") private val accountsFile: String,
    @Value("\${chain.deployment-file:deployed-contracts.json}") private val deploymentFile: String,
) {
    private val mapper = JsonMapper.builder().build()
    private val deployed = linkedMapOf<String, Map<String, Any?>>()

    init {
        val file = Path.of(deploymentFile)
        if (Files.exists(file)) {
            mapper.readTree(Files.readString(file)).properties().forEach { entry ->
                deployed[entry.key] =
                    mapOf(
                        "address" to entry.value["address"].asString(),
                        "name" to entry.value["name"].asString(),
                        "deployedAt" to entry.value["deployedAt"].asString(),
                    )
            }
        }
    }

    fun key(index: Int): String {
        require(accountsFile.isNotBlank()) { "Genesis accounts file is not configured" }
        val accounts = mapper.readTree(Files.readString(Path.of(accountsFile)))
        return requireNotNull(
            accounts.toList().firstOrNull {
                it["index"]?.asInt() == index
            } ?: accounts.toList().getOrNull(index),
        ) { "Genesis account $index is not loaded" }["privateKey"].asString()
    }

    fun submit(
        to: String?,
        data: String,
        key: String,
        value: BigInteger = BigInteger.ZERO,
        gas: Long = 1_000_000,
    ): Map<String, String> {
        val tx = chain.submit(chain.sign(key, to, value, data, gasLimit = BigInteger.valueOf(gas)))
        return mapOf("hash" to tx.hash, "status" to "pending")
    }

    fun deploy(bytecode: String): Map<String, String> = submit(null, bytecode, key(255), gas = 5_000_000)

    fun execute(
        to: String,
        data: String,
    ): Map<String, String> = submit(to, data, key(0))

    fun contract(name: String): String =
        requireNotNull(deployed[name]) {
            "${name.replaceFirstChar { it.uppercase() }} contract is not deployed"
        }["address"].toString()

    @Synchronized fun deployments(): Map<String, Map<String, Any?>>? = deployed.takeIf { it.isNotEmpty() }?.toMap()

    fun abi(address: String): Any? {
        val found = deployed.values.firstOrNull { it["address"].toString().equals(address, true) } ?: return null
        return mapOf("address" to address, "name" to found["name"], "abi" to compiler.abi(found["name"].toString()))
    }

    private fun deployNamed(
        name: String,
        slot: String,
        constructorData: String = "",
    ): Pair<String, String> {
        val artifact =
            compiler.artifact(name) ?: run {
                compiler.compile(name)
                requireNotNull(compiler.artifact(name))
            }
        val address = chain.systemDeploy(artifact["bytecode"].asString() + constructorData, Crypto.address(key(255)))
        deployed[slot] =
            mapOf(
                "address" to address,
                "name" to name,
                "deployedAt" to
                    java.time.Instant
                        .now()
                        .toString(),
            )
        val path = Path.of(deploymentFile).toAbsolutePath()
        Files.createDirectories(path.parent)
        Files.writeString(path, mapper.writeValueAsString(deployed))
        return address to ZERO_HASH
    }

    @Synchronized fun deployStaking(): Map<String, String> {
        val (address, hash) = deployNamed("StakingContract", "staking")
        return mapOf("stakingAddress" to address, "stakingTxHash" to hash)
    }

    @Synchronized fun deployStablecoin(): Map<String, String> {
        val (stablecoin, stableHash) = deployNamed("StableCoin", "stablecoin")
        val (vault, vaultHash) = deployNamed("CollateralVault", "vault")
        val from = Crypto.address(key(255))
        chain.systemCall(vault, Abi.call("setStablecoin(address)", stablecoin), from)
        chain.systemCall(stablecoin, Abi.call("setVault(address)", vault), from)
        return mapOf(
            "stablecoinAddress" to stablecoin,
            "vaultAddress" to vault,
            "stablecoinTxHash" to stableHash,
            "vaultTxHash" to vaultHash,
        )
    }

    fun read(
        slot: String,
        signature: String,
        vararg args: Any,
    ): String {
        val result = chain.call(contract(slot), Abi.call(signature, *args), "0x" + "00".repeat(20))
        require(result.success) { "Contract execution reverted" }
        return result.output
    }
}

@RestController
class ContractsController(
    private val contracts: ContractService,
) {
    @PostMapping("/contract/deploy")
    @ResponseStatus(HttpStatus.CREATED)
    fun deploy(
        @RequestBody body: Map<String, String>,
    ): Any = contracts.deploy(requireNotNull(body["bytecode"]))

    @PostMapping("/contract/execute")
    @ResponseStatus(HttpStatus.CREATED)
    fun execute(
        @RequestBody body: Map<String, String>,
    ): Any = contracts.execute(requireNotNull(body["to"]), requireNotNull(body["data"]))

    @GetMapping("/contract/{address}/abi")
    fun abi(
        @PathVariable address: String,
    ): Any? = contracts.abi(address)

    @GetMapping("/contract/deployed")
    fun deployed(): Any? = contracts.deployments()

    @PostMapping("/contract/deploy-staking")
    @ResponseStatus(HttpStatus.CREATED)
    fun staking(): Any = contracts.deployStaking()

    @PostMapping("/contract/deploy-stablecoin")
    @ResponseStatus(HttpStatus.CREATED)
    fun stablecoin(): Any = contracts.deployStablecoin()
}
