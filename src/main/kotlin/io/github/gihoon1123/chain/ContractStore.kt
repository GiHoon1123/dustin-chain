package io.github.gihoon1123.chain

import org.iq80.leveldb.Options
import org.iq80.leveldb.impl.Iq80DBFactory.factory
import java.io.Closeable
import java.math.BigInteger
import java.nio.file.Path

class ContractStore(
    path: Path,
) : Closeable {
    private val db = factory.open(path.toFile(), Options().createIfMissing(true))

    fun load(
        vm: Evm,
        state: StateStore,
    ) {
        state.all().forEach { account ->
            vm.setBalance(account.address, account.balance)
            vm.setNonce(account.address, account.nonce)
            val code = db.get("code:${account.codeHash}".toByteArray())?.toString(Charsets.UTF_8)
            if (!code.isNullOrEmpty()) vm.setCode(account.address, "0x$code")
        }
        db.iterator().use {
            it.seek("storage:".toByteArray())
            while (it.hasNext()) {
                val entry = it.next()
                val key = entry.key.toString(Charsets.UTF_8)
                if (!key.startsWith("storage:")) break
                val (_, address, slot) = key.split(':', limit = 3)
                val value = entry.value.toString(Charsets.UTF_8)
                if (value.isNotEmpty()) vm.setStorage(address, BigInteger(slot, 16), BigInteger(value, 16))
            }
        }
    }

    fun save(
        vm: Evm,
        state: StateStore,
    ) {
        db.createWriteBatch().use { batch ->
            for (account in vm.accounts()) {
                val address = account.address.toHexString()
                val previous = state.get(address) ?: Account(address)
                val code = account.code.toArrayUnsafe()
                val hash = Crypto.hash(code)
                var root = previous.storageRoot
                val mutable = vm.world.getAccount(account.address)
                for ((slot, value) in mutable.updatedStorage) {
                    val slotHex = slot.toBytes().toUnprefixedHexString()
                    val key = "storage:$address:$slotHex".toByteArray()
                    val raw = value.toBigInteger().unsignedBytes()
                    val rawHex = raw.hex().removePrefix("0x")
                    val old = db.get(key)?.toString(Charsets.UTF_8)
                    if (old != rawHex) {
                        batch.put(key, rawHex.toByteArray())
                        val prefix = if (root == EMPTY_ROOT) byteArrayOf() else root.bytes()
                        root = Crypto.hash(prefix + Crypto.hash(address.bytes() + slot.toBytes().toArrayUnsafe() + raw).bytes())
                    }
                }
                state.put(Account(address, account.balance.toBigInteger(), account.nonce, root, hash))
                if (code.isNotEmpty()) {
                    batch.put("code:$hash".toByteArray(), code.hex().removePrefix("0x").toByteArray())
                    batch.put("account_codehash:$address".toByteArray(), hash.toByteArray())
                }
            }
            db.write(batch)
        }
    }

    override fun close() {
        db.close()
    }
}
