package io.github.gihoon1123.chain

import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import java.io.Closeable
import java.math.BigInteger
import java.nio.file.Path
import java.time.Instant

class BlockStore(
    path: Path,
) : Closeable {
    private val db = factory.open(path.toFile(), Options().createIfMissing(true))

    @Synchronized fun save(block: Block) {
        db.createWriteBatch().use { batch ->
            fun put(
                key: String,
                value: ByteArray,
            ) {
                batch.put(key.toByteArray(), value)
            }
            put(
                "h${block.number}${block.hash}",
                WireRlp.encode(
                    listOf(
                        block.number.toString(),
                        block.hash,
                        block.parentHash,
                        block.timestamp.toString(),
                        block.proposer,
                        block.stateRoot,
                        block.transactionsRoot,
                        block.receiptsRoot,
                        block.logsBloom,
                        block.transactions.size.toString(),
                    ),
                ),
            )
            put(
                "b${block.number}${block.hash}",
                WireRlp.encode(
                    block.transactions.map {
                        listOf(
                            it.hash,
                            it.from,
                            it.to ?: "",
                            it.value.toString(),
                            it.nonce.toString(),
                            it.timestamp.toEpochMilli().toString(),
                            it.signature.v,
                            it.signature.r,
                            it.signature.s,
                            it.data,
                            it.gasPrice.toString(),
                            it.gasLimit.toString(),
                        )
                    },
                ),
            )
            put("H${block.number}", block.hash.toByteArray())
            put("n${block.hash}", block.number.toString().toByteArray())
            put("LastBlock", block.hash.toByteArray())
            block.transactions.forEachIndexed {
                i,
                tx,
                ->
                put("l${tx.hash}", WireRlp.encode(listOf(block.hash, block.number.toString(), i.toString())))
            }
            db.write(batch)
        }
    }

    @Synchronized fun latest(): Block? = db.get("LastBlock".toByteArray())?.toString(Charsets.UTF_8)?.let { byHash(it) }

    @Synchronized fun byNumber(number: Long): Block? = db.get("H$number".toByteArray())?.toString(Charsets.UTF_8)?.let { byHash(it) }

    @Synchronized fun byHash(hash: String): Block? {
        val number = db.get("n$hash".toByteArray())?.toString(Charsets.UTF_8)?.toLong() ?: return null
        val header = WireRlp.decode(db.get("h$number$hash".toByteArray()) ?: return null) as List<*>
        val body = WireRlp.decode(db.get("b$number$hash".toByteArray()) ?: return null) as List<*>
        val txs =
            body.map { raw ->
                val row = raw as List<*>

                fun text(i: Int): String = (row[i] as ByteArray).toString(Charsets.UTF_8)

                fun hex(i: Int): String = (row[i] as ByteArray).hex()
                Transaction(
                    hex(1),
                    (row[2] as ByteArray)
                        .takeIf {
                            it.isNotEmpty()
                        }?.hex(),
                    text(3).toBigInteger(),
                    text(4).toLong(),
                    Signature(BigInteger(1, row[6] as ByteArray).toLong(), hex(7), hex(8)),
                    hex(0),
                    if (row.size >
                        9
                    ) {
                        hex(9)
                    } else {
                        "0x"
                    },
                    if (row.size >
                        10
                    ) {
                        text(10).toBigInteger()
                    } else {
                        BigInteger.valueOf(1_000_000_000)
                    },
                    if (row.size >
                        11
                    ) {
                        text(11).toBigInteger()
                    } else {
                        BigInteger.valueOf(21_000)
                    },
                    "confirmed",
                    number,
                    Instant.ofEpochMilli(text(5).toLong()),
                )
            }

        fun text(i: Int): String = (header[i] as ByteArray).toString(Charsets.UTF_8)

        fun hex(i: Int): String = (header[i] as ByteArray).hex()
        return Block(
            number,
            hex(2),
            text(3).toLong(),
            hex(4),
            txs,
            hex(5),
            hex(6),
            hex(7),
            if (header.size >=
                10
            ) {
                hex(8)
            } else {
                ZERO_BLOOM
            },
            hash,
        )
    }

    @Synchronized fun saveReceipt(receipt: Receipt) {
        val mapper =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
        val logs =
            receipt.logs.mapIndexed {
                i,
                log,
                ->
                receipt.logJson(log, i.toLong()).toMutableMap().apply {
                    put("blockNumber", receipt.number)
                    put("transactionIndex", receipt.index)
                    put("logIndex", i)
                }
            }
        db.put(
            "r${receipt.tx.hash}".toByteArray(),
            WireRlp.encode(
                listOf(
                    receipt.tx.hash,
                    receipt.index.toString(),
                    receipt.blockHash,
                    receipt.number.toString(),
                    receipt.tx.from,
                    receipt.tx.to ?: "",
                    if (receipt.success) "1" else "0",
                    receipt.gasUsed.toString(),
                    receipt.cumulativeGas.toString(),
                    receipt.contractAddress ?: "",
                    mapper.writeValueAsString(logs),
                    receipt.bloom,
                ),
            ),
        )
    }

    @Synchronized fun receipt(hash: String): Map<String, Any?>? {
        val values = db.get("r$hash".toByteArray())?.let { WireRlp.decode(it) as List<*> } ?: return null

        fun text(i: Int): String = (values[i] as ByteArray).toString(Charsets.UTF_8)

        fun hex(i: Int): String = (values[i] as ByteArray).hex()
        val mapper =
            tools.jackson.databind.json.JsonMapper
                .builder()
                .build()
        val logs =
            mapper.readTree(text(10)).toList().map { node ->
                linkedMapOf<String, Any?>(
                    "address" to node["address"].asString(),
                    "topics" to node["topics"].toList().map { it.asString() },
                    "data" to node["data"].asString(),
                    "blockNumber" to node["blockNumber"].asLong().quantity(),
                    "transactionHash" to node["transactionHash"].asString(),
                    "transactionIndex" to node["transactionIndex"].asLong().quantity(),
                    "blockHash" to node["blockHash"].asString(),
                    "logIndex" to node["logIndex"].asLong().quantity(),
                    "removed" to false,
                )
            }
        return linkedMapOf(
            "transactionHash" to hash,
            "transactionIndex" to text(1).toLong().quantity(),
            "blockHash" to hex(2),
            "blockNumber" to text(3).toLong().quantity(),
            "from" to hex(4),
            "to" to (values[5] as ByteArray).takeIf { it.isNotEmpty() }?.hex(),
            "status" to text(6).toLong().quantity(),
            "gasUsed" to text(7).toBigInteger().quantity(),
            "cumulativeGasUsed" to text(8).toBigInteger().quantity(),
            "contractAddress" to (values[9] as ByteArray).takeIf { it.isNotEmpty() }?.hex(),
            "logs" to logs,
            "logsBloom" to hex(11),
        )
    }

    @Synchronized fun transaction(hash: String): Map<String, Any?>? {
        val lookup = db.get("l$hash".toByteArray())?.let { WireRlp.decode(it) as List<*> } ?: return null
        val block = byHash((lookup[0] as ByteArray).hex()) ?: return null
        val index = (lookup[2] as ByteArray).toString(Charsets.UTF_8).toInt()
        return block.transactions[index].json().toMutableMap().apply {
            put("blockHash", block.hash)
            put("blockNumber", block.number.quantity())
            put("transactionIndex", index.toLong().quantity())
        }
    }

    override fun close() {
        db.close()
    }
}

object WireRlp {
    fun encode(value: Any?): ByteArray =
        when (value) {
            null -> Rlp.bytes(byteArrayOf())
            is ByteArray -> Rlp.bytes(value)
            is Number -> Rlp.number(BigInteger(value.toString()))
            is String -> Rlp.bytes(if (value.startsWith("0x")) value.bytes() else value.toByteArray())
            is List<*> -> Rlp.list(*value.map { encode(it) }.toTypedArray())
            else -> error("Unsupported RLP type")
        }

    fun decode(bytes: ByteArray): Any {
        val parsed = parse(bytes, 0)
        require(parsed.second == bytes.size)
        return parsed.first
    }

    private fun parse(
        bytes: ByteArray,
        offset: Int,
    ): Pair<Any, Int> {
        require(offset < bytes.size)
        val prefix = bytes[offset].toInt() and 255
        if (prefix < 128) return byteArrayOf(bytes[offset]) to offset + 1
        val list = prefix >= 192
        val count = prefix - if (list) 192 else 128
        val sizeBytes = if (count <= 55) 0 else count - 55
        val start = offset + 1 + sizeBytes
        require(start <= bytes.size)
        val length = if (sizeBytes == 0) count else BigInteger(1, bytes.copyOfRange(offset + 1, start)).intValueExact()
        val end = start + length
        require(length >= 0 && end.toLong() == start.toLong() + length && end <= bytes.size)
        if (!list) return bytes.copyOfRange(start, end) to end
        val values = mutableListOf<Any>()
        var current = start
        while (current < end) {
            val parsed = parse(bytes, current)
            values += parsed.first
            current = parsed.second
        }
        require(current == end)
        return values to end
    }
}
