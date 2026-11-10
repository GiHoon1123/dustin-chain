package io.github.gihoon1123.chain

import java.math.BigInteger

fun String.number(): BigInteger = if (startsWith("0x")) BigInteger(removePrefix("0x"), 16) else BigInteger(this)

data class Receipt(
    val tx: Transaction,
    val index: Long,
    var blockHash: String,
    val number: Long,
    val success: Boolean,
    val gasUsed: BigInteger,
    val cumulativeGas: BigInteger,
    val contractAddress: String? = null,
    val logs: List<Log> = emptyList(),
) {
    val bloom: String get() = Crypto.logsBloom(logs)

    fun json(): Map<String, Any?> =
        linkedMapOf(
            "transactionHash" to tx.hash,
            "transactionIndex" to index.quantity(),
            "blockHash" to blockHash,
            "blockNumber" to number.quantity(),
            "from" to tx.from,
            "to" to tx.to,
            "status" to (if (success) 1L else 0L).quantity(),
            "gasUsed" to gasUsed.quantity(),
            "cumulativeGasUsed" to cumulativeGas.quantity(),
            "contractAddress" to contractAddress,
            "logs" to logs.mapIndexed { i, log -> logJson(log, i.toLong()) },
            "logsBloom" to bloom,
        )

    fun logJson(
        log: Log,
        logIndex: Long,
    ): Map<String, Any> =
        linkedMapOf(
            "address" to log.address,
            "topics" to log.topics,
            "data" to log.data,
            "blockNumber" to number.quantity(),
            "transactionHash" to tx.hash,
            "transactionIndex" to index.quantity(),
            "blockHash" to blockHash,
            "logIndex" to logIndex.quantity(),
            "removed" to false,
        )

    fun trieValue(): ByteArray =
        Rlp.list(
            Rlp.bytes((if (success) "1" else "0").toByteArray()),
            Rlp.bytes(cumulativeGas.toString().toByteArray()),
            Rlp.bytes(bloom.bytes()),
            Rlp.list(
                *logs
                    .map {
                        Rlp.list(
                            Rlp.bytes(it.address.bytes()),
                            Rlp.list(
                                *it.topics
                                    .map { topic ->
                                        Rlp.bytes(topic.bytes())
                                    }.toTypedArray(),
                            ),
                            Rlp.bytes(it.data.bytes()),
                        )
                    }.toTypedArray(),
            ),
        )
}

data class Block(
    val number: Long,
    val parentHash: String,
    val timestamp: Long,
    val proposer: String,
    val transactions: List<Transaction>,
    val stateRoot: String,
    val transactionsRoot: String,
    val receiptsRoot: String,
    val logsBloom: String,
    val hash: String,
) {
    fun json(): Map<String, Any> =
        linkedMapOf(
            "number" to number.quantity(),
            "hash" to hash,
            "parentHash" to parentHash,
            "timestamp" to (timestamp / 1000).quantity(),
            "proposer" to proposer,
            "transactionCount" to transactions.size.toLong().quantity(),
            "transactions" to
                transactions.mapIndexed {
                    i,
                    tx,
                    ->
                    tx.json().toMutableMap().apply {
                        remove("status")
                        put("blockHash", hash)
                        put("blockNumber", number.quantity())
                        put("transactionIndex", i.toLong().quantity())
                    }
                },
            "stateRoot" to stateRoot,
            "transactionsRoot" to transactionsRoot,
            "receiptsRoot" to receiptsRoot,
            "logsBloom" to logsBloom,
        )

    companion object {
        fun create(
            number: Long,
            parentHash: String,
            timestamp: Long,
            proposer: String,
            txs: List<Transaction>,
            stateRoot: String,
            receipts: List<Receipt>,
        ): Block {
            val txRoot =
                Trie.root(
                    txs.mapIndexed { i, tx ->
                        Rlp.number(BigInteger.valueOf(i.toLong())) to
                            Rlp.list(
                                Rlp.number(BigInteger.valueOf(tx.nonce)),
                                Rlp.bytes(tx.to?.bytes() ?: byteArrayOf()),
                                Rlp.number(tx.value),
                                Rlp.bytes(tx.from.bytes()),
                                Rlp.number(BigInteger.valueOf(tx.signature.v)),
                                Rlp.bytes(tx.signature.r.bytes()),
                                Rlp.bytes(tx.signature.s.bytes()),
                            )
                    },
                )
            val receiptRoot =
                Trie.root(
                    receipts.mapIndexed { i, receipt ->
                        Rlp.number(BigInteger.valueOf(i.toLong())) to
                            receipt.trieValue()
                    },
                )
            val bloom = Crypto.logsBloom(receipts.flatMap { it.logs })
            val hash =
                Crypto.hash(
                    Rlp.list(
                        Rlp.bytes(parentHash.bytes()),
                        Rlp.bytes(stateRoot.bytes()),
                        Rlp.bytes(txRoot.bytes()),
                        Rlp.bytes(receiptRoot.bytes()),
                        Rlp.bytes(bloom.bytes()),
                        Rlp.number(BigInteger.valueOf(number)),
                        Rlp.number(BigInteger.valueOf(timestamp)),
                        Rlp.bytes(proposer.bytes()),
                    ),
                )
            return Block(number, parentHash, timestamp, proposer, txs, stateRoot, txRoot, receiptRoot, bloom, hash)
        }
    }
}
