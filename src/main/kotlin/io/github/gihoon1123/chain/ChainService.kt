package io.github.gihoon1123.chain

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

@Service
class ChainService(
    @Value("\${chain.data-dir:data}") dataDirectory: String,
    @Value("\${chain.genesis-file:}") genesisFile: String,
    @Value("\${chain.auto-mine:true}") autoMine: Boolean,
) {
    private val directory = Path.of(dataDirectory).also { Files.createDirectories(it) }
    val state = StateStore(directory.resolve("state"))
    private val blocks = BlockStore(directory.resolve("blocks"))
    private val contracts = ContractStore(directory.resolve("contracts"))
    private val vm = Evm()
    val pool = TransactionPool()
    val consensus = Consensus(Clock.systemUTC())
    val validators = (1..10).map { "0x" + it.toString(16).padStart(40, '0') }
    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var mining: ScheduledFuture<*>? = null
    final val genesisProposer: String

    init {
        contracts.load(vm, state)
        var genesis = blocks.byNumber(0)
        if (genesis == null) {
            if (genesisFile.isNotBlank()) {
                val json =
                    tools.jackson.databind.json.JsonMapper
                        .builder()
                        .build()
                        .readTree(Files.readString(Path.of(genesisFile)))
                json["alloc"].properties().forEach { entry ->
                    val address = if (entry.key.startsWith("0x")) entry.key else "0x${entry.key}"
                    state.put(Account(address.lowercase(), entry.value["balance"].asString().number()))
                }
                state.flush()
                contracts.load(vm, state)
            }
            val proposer = state.all().firstOrNull()?.address ?: validators.first()
            genesis = Block.create(0, ZERO_HASH, System.currentTimeMillis(), proposer, emptyList(), state.root(), emptyList())
            blocks.save(genesis)
        }
        genesisProposer = genesis.proposer
        consensus.genesisTime = genesis.timestamp
        if (autoMine) startMining()
    }

    @Synchronized fun account(address: String): Account {
        require(address.matches(Regex("0x[0-9a-fA-F]{40}"))) { "Invalid account address" }
        return state.get(address) ?: Account(address.lowercase()).also { state.put(it) }
    }

    @Synchronized fun addBalance(
        address: String,
        amount: BigInteger,
    ): Account {
        val account = account(address)
        account.addBalance(amount)
        state.put(account)
        state.flush()
        vm.setBalance(account.address, account.balance)
        return account
    }

    @Synchronized fun createWallet(): Map<String, Any> {
        val key = Crypto.generateKeyPair()
        val account = addBalance(key.address, WEI * BigInteger.valueOf(10_000))
        return linkedMapOf(
            "privateKey" to key.privateKey,
            "publicKey" to key.publicKey,
            "address" to key.address,
            "balance" to account.balance.quantity(),
            "nonce" to account.nonce,
        )
    }

    @Synchronized fun sign(
        privateKey: String,
        to: String?,
        amount: BigInteger,
        data: String = "0x",
        gasPrice: BigInteger = BigInteger.valueOf(1_000_000_000),
        gasLimit: BigInteger = BigInteger.valueOf(21000),
    ): Transaction {
        val from = Crypto.address(privateKey)
        val existing = (pool.pending() + pool.queued()).filter { it.from.equals(from, true) }.maxOfOrNull { it.nonce }
        return Transaction.sign(privateKey, to, amount, existing?.plus(1) ?: account(from).nonce, data, gasPrice, gasLimit)
    }

    @Synchronized fun submit(transaction: Transaction): Transaction {
        require(transaction.verify()) { "Invalid transaction signature" }
        require(transaction.value.signum() >= 0) { "Value must not be negative" }
        val account = account(transaction.from)
        require(transaction.nonce >= account.nonce) { "Nonce too low: ${transaction.nonce} < ${account.nonce}" }
        require(
            (pool.pending() + pool.queued()).none {
                it.from.equals(transaction.from, true) && it.nonce == transaction.nonce
            },
        ) { "Duplicate nonce: transaction with nonce ${transaction.nonce} already exists in pool" }
        require(transaction.gasPrice.signum() > 0) { "Gas price must be greater than zero" }
        require(transaction.gasLimit.signum() > 0) { "Gas limit must be greater than zero" }
        val required = transaction.value + transaction.gasPrice * transaction.gasLimit
        require(account.balance >= required) { "Insufficient balance: ${account.balance} Wei, required: $required Wei (value + gas fee)" }
        require(pool.add(transaction, account.nonce)) { "Transaction already exists in pool" }
        return transaction
    }

    @Synchronized fun latest(): Block = requireNotNull(blocks.latest())

    @Synchronized fun block(number: Long): Block =
        blocks.byNumber(number) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Block #$number not found")

    @Synchronized fun block(hash: String): Block =
        blocks.byHash(
            hash,
        ) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Block $hash not found")

    @Synchronized fun transaction(hash: String): Map<String, Any?> =
        pool.get(hash)?.json() ?: blocks.transaction(hash)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found: $hash")

    @Synchronized fun receipt(hash: String): Map<String, Any?> =
        blocks.receipt(hash) ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Receipt not found: $hash")

    @Synchronized fun stats(): Map<String, Any?> {
        val latest = latest()
        return linkedMapOf(
            "height" to latest.number,
            "latestBlockNumber" to latest.number,
            "latestBlockHash" to latest.hash,
            "totalTransactions" to (0..latest.number).sumOf { blocks.byNumber(it)?.transactions?.size ?: 0 },
            "genesisProposer" to genesisProposer,
        )
    }

    @Synchronized fun producerStatus(): Map<String, Any?> =
        linkedMapOf(
            "isRunning" to (mining != null),
            "genesisTime" to Instant.ofEpochMilli(consensus.genesisTime!!).toString(),
            "currentSlot" to consensus.currentSlot(),
            "blockTime" to 12,
        )

    @Synchronized fun startMining() {
        if (mining != null) return
        val delay = 12000 - (System.currentTimeMillis() - consensus.genesisTime!!) % 12000
        mining =
            scheduler.scheduleAtFixedRate({
                runCatching {
                    produce()
                }.onFailure {
                    org.slf4j.LoggerFactory
                        .getLogger(javaClass)
                        .error("Block production failed", it)
                }
            }, delay, 12000, TimeUnit.MILLISECONDS)
    }

    @Synchronized fun stopMining() {
        mining?.cancel(false)
        mining = null
    }

    @Synchronized fun produce(timestamp: Long = System.currentTimeMillis()): Block {
        val parent = latest()
        val number = parent.number + 1
        val proposer = consensus.proposer(consensus.currentSlot(), validators)
        val executed = mutableListOf<Transaction>()
        val receipts = mutableListOf<Receipt>()
        var cumulative = BigInteger.ZERO
        for (tx in pool.pending().take(1000)) {
            val sender = account(tx.from)
            if (sender.nonce != tx.nonce || sender.balance < tx.value + tx.gasPrice * tx.gasLimit) {
                pool.remove(tx.hash)
                continue
            }
            val intrinsic = 21000L + (if (tx.to == null) 32000 else 0) + tx.data.bytes().sumOf { if (it == 0.toByte()) 4L else 16L }
            val address =
                tx.to ?: "0x" + Crypto.hash(Rlp.list(Rlp.bytes(tx.from.bytes()), Rlp.number(BigInteger.valueOf(tx.nonce)))).takeLast(40)
            val remaining = tx.gasLimit.longValueExact() - intrinsic
            var success = false
            var gas = tx.gasLimit
            var logs = emptyList<Log>()
            sender.nonce++
            state.put(sender)
            vm.setNonce(sender.address, sender.nonce)
            if (remaining >= 0) {
                val result =
                    vm.execute(
                        tx.from,
                        address,
                        if (tx.to ==
                            null
                        ) {
                            tx.data
                        } else {
                            vm.code(address)
                        },
                        if (tx.to ==
                            null
                        ) {
                            "0x"
                        } else {
                            tx.data
                        },
                        remaining,
                        tx.value,
                        tx.to == null,
                        timestamp = timestamp / 1000,
                    )
                success = result.success
                gas = BigInteger.valueOf(intrinsic + result.gasUsed)
                logs = result.logs
            }
            contracts.save(vm, state)
            val after = account(tx.from)
            after.balance -= gas * tx.gasPrice
            state.put(after)
            vm.setBalance(after.address, after.balance)
            tx.status = if (success) "confirmed" else "failed"
            tx.blockNumber = number
            cumulative += gas
            receipts +=
                Receipt(
                    tx,
                    executed.size.toLong(),
                    "",
                    number,
                    success,
                    gas,
                    cumulative,
                    if (tx.to == null &&
                        success
                    ) {
                        address
                    } else {
                        null
                    },
                    logs,
                )
            executed += tx
            pool.remove(tx.hash)
            pool.promote(tx.from, sender.nonce)
        }
        state.flush()
        val block = Block.create(number, parent.hash, timestamp, proposer, executed, state.root(), receipts)
        blocks.save(block)
        receipts.forEach {
            it.blockHash = block.hash
            blocks.saveReceipt(it)
        }
        return block
    }

    @Synchronized fun systemCall(
        to: String,
        data: String,
        from: String,
        value: BigInteger = BigInteger.ZERO,
    ): Evm.Result {
        val result = vm.execute(from, to, vm.code(to), data, value = value)
        require(result.success) { "Contract execution reverted: ${result.output}" }
        contracts.save(vm, state)
        state.flush()
        return result
    }

    @Synchronized fun call(
        to: String,
        data: String,
        from: String = validators.first(),
        value: BigInteger = BigInteger.ZERO,
    ): Evm.Result = vm.execute(from, to, vm.code(to), data, value = value, readOnly = true)

    @Synchronized fun bytecode(address: String): String = vm.code(address)

    @Synchronized fun logs(
        from: String?,
        to: String?,
        addresses: List<String>?,
        topics: List<List<String>?>?,
    ): List<Map<String, Any?>> {
        fun height(value: String?): Long =
            if (value == null ||
                value == "latest"
            ) {
                latest().number
            } else if (value == "earliest") {
                0
            } else {
                value.number().longValueExact()
            }
        val start = height(from)
        val end = height(to)
        if (start > end) return emptyList()
        return (start..minOf(end, latest().number)).flatMap { number ->
            blocks.byNumber(number)?.transactions.orEmpty().flatMap { tx ->
                val receipt = blocks.receipt(tx.hash)

                @Suppress("UNCHECKED_CAST")
                val logs = receipt?.get("logs") as? List<Map<String, Any?>> ?: emptyList()
                logs
                    .filter { log ->
                        @Suppress("UNCHECKED_CAST")
                        val actual = log["topics"] as List<String>
                        (addresses.isNullOrEmpty() || addresses.any { it.equals(log["address"] as String, true) }) &&
                            (
                                topics == null ||
                                    topics.withIndex().all { (i, alternatives) ->
                                        alternatives == null ||
                                            (i < actual.size && alternatives.any { it.equals(actual[i], true) })
                                    }
                            )
                    }.map { it + ("blockTimestamp" to block(number).timestamp.quantity()) }
            }
        }
    }

    @PreDestroy fun close() {
        stopMining()
        scheduler.shutdownNow()
        contracts.close()
        blocks.close()
        state.close()
    }
}
