package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger

class CryptoDomainTest {
    private val key = "0x" + "00".repeat(31) + "01"
    private val recipient = "0x" + "11".repeat(20)

    @Test fun `keccak matches Ethereum empty and hello vectors`() {
        assertEquals("0xc5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470", Crypto.hash(byteArrayOf()))
        assertEquals("0x1c8aff950685c2ed4bc3174f3472287b56d9517b9c948127319a09a7a36deac8", Crypto.hashUtf8("hello"))
    }

    @Test fun `secp256k1 private scalar one matches public Ethereum address`() {
        assertEquals("0x7e5f4552091a69125d5dfcb7b8c2659029395bdf", Crypto.address(key))
        assertEquals(128, Crypto.publicKey(key).length)
    }

    @Test fun `deterministic low s signatures recover under EIP155`() {
        val hash = Crypto.hashUtf8("test message")
        val first = Crypto.sign(hash, key, CHAIN_ID)
        assertEquals(first, Crypto.sign(hash, key, CHAIN_ID))
        assertTrue(first.v in 2033L..2034L)
        assertEquals(Crypto.address(key), Crypto.recoverAddress(hash, first))
        assertTrue(Crypto.verify(hash, first, Crypto.address(key)))
        assertFalse(Crypto.verify(hash, first, recipient))
    }

    @Test fun `invalid private scalars are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { Crypto.address("0x" + "00".repeat(32)) }
        assertThrows(IllegalArgumentException::class.java) { Crypto.address("0x" + "ff".repeat(32)) }
    }

    @Test fun `rlp zero empty string and list are distinct`() {
        assertEquals("0x80", Rlp.number(BigInteger.ZERO).hex())
        assertEquals("0xc0", Rlp.list().hex())
        assertEquals("0xc88363617483646f67", Rlp.list(Rlp.bytes("cat".toByteArray()), Rlp.bytes("dog".toByteArray())).hex())
        assertEquals("0x7f", Rlp.number(BigInteger.valueOf(127)).hex())
        assertEquals("0x8180", Rlp.number(BigInteger.valueOf(128)).hex())
    }

    @Test fun `transaction signing protects value destination and chain id`() {
        val tx = Transaction.sign(key, recipient, WEI, 0)
        assertTrue(tx.verify())
        assertEquals("0xa58dc672e8eb885a9727c48a367281b48c7aa65c802d97a393fab2d24d63cefb", tx.hash)
        assertEquals("0x2a8604f6e39656fa034cd429ac34f491bd4e2787df0b74f1716edc2873846480", tx.signature.r)
        assertEquals("0x5a15e995a262dec747b428e4741d1af0189218f4cb5d76f98cf527e237a27417", tx.signature.s)
        assertFalse(tx.copy(value = WEI + BigInteger.ONE).verify())
        assertFalse(tx.copy(to = Crypto.address(key)).verify())
        assertEquals("0xde0b6b3a7640000", tx.json()["value"])
        assertEquals("0x5208", tx.json()["gas"])
        assertFalse(tx.json().containsKey("blockNumber"))
    }

    @Test fun `contract creation uses empty destination and preserves data`() {
        val tx = Transaction.sign(key, null, BigInteger.ZERO, 0, "0x6000")
        assertTrue(tx.verify())
        assertNull(tx.json()["to"])
        assertEquals("0x6000", tx.json()["data"])
    }

    @Test fun `account balance uses integers beyond JavaScript safe range`() {
        val account = Account(recipient)
        val amount = WEI.pow(4) + BigInteger.ONE
        account.addBalance(amount)
        account.subtractBalance(BigInteger.ONE)
        assertEquals(WEI.pow(4), account.balance)
        assertThrows(IllegalArgumentException::class.java) { account.subtractBalance(amount) }
        assertEquals(EMPTY_ROOT, account.storageRoot)
    }

    @Test fun `pool rejects duplicate and stale transactions and promotes next nonce`() {
        val pool = TransactionPool()
        val first = Transaction.sign(key, recipient, BigInteger.ONE, 0)
        val next = Transaction.sign(key, recipient, BigInteger.ONE, 1)
        assertTrue(pool.add(next, 0))
        assertTrue(pool.add(first, 0))
        assertFalse(pool.add(first, 0))
        assertEquals(listOf(first), pool.pending())
        assertEquals(listOf(next), pool.queued())
        pool.remove(first.hash)
        pool.promote(first.from, 1)
        assertEquals(listOf(next), pool.pending())
        assertFalse(pool.add(first, 1))
    }

    @Test fun `bloom preserves original big endian bit layout`() {
        val bloom = Crypto.logsBloom(listOf(Log(recipient, listOf(ZERO_HASH))))
        assertEquals(514, bloom.length)
        assertEquals(ZERO_BLOOM, Crypto.logsBloom(emptyList()))
        assertNotEquals(ZERO_BLOOM, bloom)
    }
}
