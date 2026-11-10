package io.github.gihoon1123.chain

import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import java.io.Closeable
import java.math.BigInteger
import java.nio.file.Path

object Trie {
    private data class Entry(
        val path: List<Int>,
        val value: ByteArray,
    )

    fun root(entries: List<Pair<ByteArray, ByteArray>>): String {
        if (entries.isEmpty()) return EMPTY_ROOT
        val expanded = entries.map { (key, value) -> Entry(key.flatMap { listOf((it.toInt() and 255) shr 4, it.toInt() and 15) }, value) }
        return Crypto.hash(node(expanded, 0))
    }

    private fun node(
        entries: List<Entry>,
        depth: Int,
    ): ByteArray {
        if (entries.size == 1) return Rlp.list(Rlp.bytes(compact(entries[0].path.drop(depth), true)), Rlp.bytes(entries[0].value))
        var common = 0
        while (entries.all { it.path.size > depth + common } && entries.map { it.path[depth + common] }.distinct().size == 1) common++
        if (common >
            0
        ) {
            return Rlp.list(
                Rlp.bytes(compact(entries[0].path.subList(depth, depth + common), false)),
                ref(node(entries, depth + common)),
            )
        }
        val children =
            (0..15).map { branch ->
                entries.filter { it.path.size > depth && it.path[depth] == branch }.let {
                    if (it.isEmpty()) {
                        Rlp.bytes(byteArrayOf())
                    } else {
                        ref(
                            node(
                                it,
                                depth + 1,
                            ),
                        )
                    }
                }
            }
        return Rlp.list(*(children + Rlp.bytes(entries.firstOrNull { it.path.size == depth }?.value ?: byteArrayOf())).toTypedArray())
    }

    private fun ref(encoded: ByteArray): ByteArray = if (encoded.size < 32) encoded else Rlp.bytes(Crypto.hash(encoded).bytes())

    private fun compact(
        path: List<Int>,
        leaf: Boolean,
    ): ByteArray {
        val flag = if (leaf) 2 else 0
        val nibbles = if (path.size % 2 == 1) listOf(flag + 1) + path else listOf(flag, 0) + path
        return nibbles.chunked(2).map { ((it[0] shl 4) + it[1]).toByte() }.toByteArray()
    }
}

class StateStore(
    path: Path,
) : Closeable {
    private val db = factory.open(path.toFile(), Options().createIfMissing(true).paranoidChecks(true))
    private val accounts = linkedMapOf<String, Account>()
    private val checkpoints = mutableListOf<Map<String, Account>>()

    init {
        db.iterator().use { iterator ->
            iterator.seek("account:".toByteArray())
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val key = entry.key.toString(Charsets.UTF_8)
                if (!key.startsWith("account:")) break
                val address = key.removePrefix("account:")
                val values = RlpReader.list(entry.value.toString(Charsets.UTF_8).bytes())
                accounts[address] =
                    Account(
                        address,
                        values[1].integer(),
                        values[0].integer().longValueExact(),
                        values.getOrNull(2)?.hex() ?: EMPTY_ROOT,
                        values.getOrNull(3)?.hex() ?: EMPTY_HASH,
                    )
            }
        }
    }

    @Synchronized fun get(address: String): Account? = accounts[address.lowercase()]?.copy()

    @Synchronized fun all(): List<Account> = accounts.values.map { it.copy() }

    @Synchronized fun put(account: Account) {
        require(account.address.matches(Regex("0x[0-9a-fA-F]{40}"))) { "Invalid account address" }
        accounts[account.address.lowercase()] = account.copy(address = account.address.lowercase())
    }

    @Synchronized fun checkpoint() {
        checkpoints += accounts.mapValues { it.value.copy() }
    }

    @Synchronized fun revert() {
        check(checkpoints.isNotEmpty())
        val previous = checkpoints.removeLast()
        accounts.clear()
        accounts.putAll(previous)
    }

    @Synchronized fun accept() {
        check(checkpoints.isNotEmpty())
        checkpoints.removeLast()
    }

    @Synchronized fun root(): String = Trie.root(accounts.values.map { Crypto.hash(it.address.bytes()).bytes() to encode(it) })

    @Synchronized fun flush() {
        check(checkpoints.isEmpty()) { "Cannot persist uncommitted checkpoints" }
        db.createWriteBatch().use { batch ->
            accounts.values.forEach { batch.put("account:${it.address}".toByteArray(), encode(it).hex().removePrefix("0x").toByteArray()) }
            db.write(batch)
        }
    }

    private fun encode(account: Account): ByteArray =
        Rlp.list(
            Rlp.number(BigInteger.valueOf(account.nonce)),
            Rlp.number(account.balance),
            Rlp.bytes(account.storageRoot.bytes()),
            Rlp.bytes(account.codeHash.bytes()),
        )

    override fun close() {
        db.close()
    }
}

private fun ByteArray.integer(): BigInteger = if (isEmpty()) BigInteger.ZERO else BigInteger(1, this)

object RlpReader {
    fun list(encoded: ByteArray): List<ByteArray> {
        val (isList, start, length, end) = header(encoded, 0)
        require(isList && end == encoded.size)
        val values = mutableListOf<ByteArray>()
        var index = start
        while (index < start + length) {
            val child = header(encoded, index)
            require(!child.isList)
            values += encoded.copyOfRange(child.start, child.start + child.length)
            index = child.end
        }
        require(index == end)
        return values
    }

    private data class Header(
        val isList: Boolean,
        val start: Int,
        val length: Int,
        val end: Int,
    )

    private fun header(
        bytes: ByteArray,
        offset: Int,
    ): Header {
        require(offset < bytes.size)
        val prefix = bytes[offset].toInt() and 255
        if (prefix < 128) return Header(false, offset, 1, offset + 1)
        val list = prefix >= 192
        val base = if (list) 192 else 128
        val small = prefix - base
        val start: Int
        val length: Int
        if (small <= 55) {
            start = offset + 1
            length = small
        } else {
            val size = small - 55
            require(offset + 1 + size <= bytes.size)
            length = BigInteger(1, bytes.copyOfRange(offset + 1, offset + 1 + size)).intValueExact()
            start = offset + 1 + size
        }
        require(length >= 0 && start.toLong() + length <= bytes.size)
        return Header(list, start, length, start + length)
    }
}
