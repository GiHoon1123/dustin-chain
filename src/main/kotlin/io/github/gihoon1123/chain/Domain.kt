package io.github.gihoon1123.chain

import java.math.BigInteger
import java.time.Instant

const val CHAIN_ID = 999L
val WEI = BigInteger.TEN.pow(18)
val EMPTY_ROOT = Crypto.hash(Rlp.bytes(byteArrayOf()))
val EMPTY_HASH = Crypto.hash(byteArrayOf())
val ZERO_HASH = "0x" + "00".repeat(32)
val ZERO_BLOOM = "0x" + "00".repeat(256)

data class Account(
    val address: String,
    var balance: BigInteger = BigInteger.ZERO,
    var nonce: Long = 0,
    var storageRoot: String = EMPTY_ROOT,
    var codeHash: String = EMPTY_HASH,
) {
    fun addBalance(amount: BigInteger) {
        require(amount.signum() > 0) { "Amount must be positive" }
        balance += amount
    }

    fun subtractBalance(amount: BigInteger) {
        require(amount.signum() > 0) { "Amount must be positive" }
        require(balance >= amount) { "Insufficient balance. Current: $balance, Required: $amount" }
        balance -=
            amount
    }

    fun json(): Map<String, Any> =
        linkedMapOf(
            "address" to address,
            "balance" to balance.quantity(),
            "nonce" to nonce.quantity(),
            "storageRoot" to storageRoot,
            "codeHash" to codeHash,
        )
}

data class Transaction(
    val from: String,
    val to: String?,
    val value: BigInteger,
    val nonce: Long,
    val signature: Signature,
    val hash: String,
    val data: String = "0x",
    val gasPrice: BigInteger = BigInteger.valueOf(1_000_000_000),
    val gasLimit: BigInteger = BigInteger.valueOf(21_000),
    var status: String = "pending",
    var blockNumber: Long? = null,
    val timestamp: Instant = Instant.now(),
) {
    fun json(): Map<String, Any?> =
        linkedMapOf<String, Any?>(
            "hash" to hash,
            "from" to from,
            "to" to to,
            "value" to value.quantity(),
            "nonce" to nonce.quantity(),
            "data" to if (data.startsWith("0x")) data else "0x$data",
            "gasPrice" to gasPrice.quantity(),
            "gas" to gasLimit.quantity(),
            "v" to signature.v.quantity(),
            "r" to signature.r,
            "s" to signature.s,
            "status" to status,
        ).apply {
            if (blockNumber != null && blockNumber != 0L) put("blockNumber", blockNumber!!.quantity())
            put("timestamp", timestamp.epochSecond.quantity())
        }

    fun signingBytes(): ByteArray = fields(BigInteger.valueOf(CHAIN_ID), byteArrayOf(), byteArrayOf())

    fun encoded(): ByteArray = fields(BigInteger.valueOf(signature.v), signature.r.bytes(), signature.s.bytes())

    private fun fields(
        v: BigInteger,
        r: ByteArray,
        s: ByteArray,
    ): ByteArray =
        Rlp.list(
            Rlp.number(BigInteger.valueOf(nonce)),
            Rlp.number(gasPrice),
            Rlp.number(gasLimit),
            Rlp.bytes(
                to?.bytes() ?: byteArrayOf(),
            ),
            Rlp.number(value),
            Rlp.bytes(data.bytes()),
            Rlp.number(v),
            Rlp.bytes(r),
            Rlp.bytes(s),
        )

    fun verify(): Boolean = Crypto.hash(encoded()) == hash && Crypto.verify(Crypto.hash(signingBytes()), signature, from)

    companion object {
        fun sign(
            privateKey: String,
            to: String?,
            value: BigInteger,
            nonce: Long,
            data: String = "0x",
            gasPrice: BigInteger = BigInteger.valueOf(1_000_000_000),
            gasLimit: BigInteger = BigInteger.valueOf(21_000),
        ): Transaction {
            val unsigned = Transaction(Crypto.address(privateKey), to, value, nonce, Signature(0, "0x", "0x"), "", data, gasPrice, gasLimit)
            val signature = Crypto.sign(Crypto.hash(unsigned.signingBytes()), privateKey, CHAIN_ID)
            val signed = unsigned.copy(signature = signature)
            return signed.copy(hash = Crypto.hash(signed.encoded()))
        }
    }
}

data class Log(
    val address: String,
    val topics: List<String>,
    val data: String = "0x",
)

class TransactionPool {
    private val pending = linkedMapOf<String, Transaction>()
    private val queued = linkedMapOf<String, Transaction>()

    @Synchronized fun add(
        tx: Transaction,
        accountNonce: Long,
    ): Boolean {
        if (get(tx.hash) != null || tx.nonce < accountNonce) return false
        (if (tx.nonce == accountNonce) pending else queued)[tx.hash] = tx
        return true
    }

    @Synchronized fun get(hash: String): Transaction? = pending[hash] ?: queued[hash]

    @Synchronized fun pending(): List<Transaction> =
        pending.values.sortedWith { a, b ->
            b.gasPrice.compareTo(a.gasPrice).takeIf { it != 0 }
                ?: if (a.from.equals(b.from, true)) a.nonce.compareTo(b.nonce) else a.hash.compareTo(b.hash)
        }

    @Synchronized fun queued(): List<Transaction> = queued.values.toList()

    @Synchronized fun promote(
        address: String,
        nonce: Long,
    ) {
        queued.values.filter { it.from.equals(address, true) && it.nonce == nonce }.toList().forEach {
            queued.remove(it.hash)
            pending[it.hash] =
                it
        }
    }

    @Synchronized fun remove(hash: String) {
        pending.remove(hash)
        queued.remove(hash)
    }

    @Synchronized fun size(): Int = pending.size + queued.size

    @Synchronized fun clear() {
        pending.clear()
        queued.clear()
    }
}
