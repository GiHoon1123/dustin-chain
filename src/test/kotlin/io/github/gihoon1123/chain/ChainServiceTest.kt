package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path

class ChainServiceTest {
    @TempDir lateinit var directory: Path
    private val key = "0x" + "00".repeat(31) + "01"
    private val recipient = "0x" + "22".repeat(20)

    @Test fun `native transfer is executed with gas and persists block receipt and balances`() {
        val sender = Crypto.address(key)
        var hash = ""
        ChainService(directory.toString(), "", false).let { chain ->
            try {
                chain.addBalance(sender, WEI * BigInteger.TEN)
                val tx = chain.submit(chain.sign(key, recipient, WEI))
                hash = tx.hash
                val block = chain.produce()
                assertEquals(1L, block.number)
                assertEquals(WEI, chain.account(recipient).balance)
                assertEquals(WEI * BigInteger.valueOf(9) - BigInteger.valueOf(21000L * 1_000_000_000L), chain.account(sender).balance)
                assertEquals(1L, chain.account(sender).nonce)
                assertEquals("0x1", chain.receipt(hash)["status"])
                assertEquals(block.hash, chain.transaction(hash)["blockHash"])
                assertEquals(0, chain.pool.size())
            } finally {
                chain.close()
            }
        }
        ChainService(directory.toString(), "", false).let { chain ->
            try {
                assertEquals(1L, chain.latest().number)
                assertEquals(WEI, chain.account(recipient).balance)
                assertEquals(hash, chain.receipt(hash)["transactionHash"])
            } finally {
                chain.close()
            }
        }
    }

    @Test fun `promoted consecutive nonces execute in the same block while nonce gaps remain queued`() {
        val chain = ChainService(directory.toString(), "", false)
        try {
            chain.addBalance(Crypto.address(key), WEI * BigInteger.TEN)
            val first = chain.submit(chain.sign(key, recipient, BigInteger.ONE))
            val second = chain.submit(chain.sign(key, recipient, BigInteger.ONE))
            val gap = chain.submit(Transaction.sign(key, recipient, BigInteger.ONE, 3))
            val block = chain.produce()
            assertEquals(listOf(first.hash, second.hash), block.transactions.map { it.hash })
            assertEquals("0x1", chain.receipt(second.hash)["status"])
            assertEquals(2L, chain.account(Crypto.address(key)).nonce)
            assertEquals(listOf(gap.hash), chain.pool.queued().map { it.hash })
        } finally {
            chain.close()
        }
    }

    @Test fun `invalid account query cannot poison state and stop block production`() {
        val chain = ChainService(directory.toString(), "", false)
        try {
            assertThrows(IllegalArgumentException::class.java) { chain.account("stats") }
            assertThrows(IllegalArgumentException::class.java) { chain.account("0x1") }
            assertEquals(1L, chain.produce().number)
        } finally {
            chain.close()
        }
    }

    @Test fun `pool validation rejects overdraft duplicate nonce and mutated signature`() {
        val chain = ChainService(directory.toString(), "", false)
        try {
            val tx = chain.sign(key, recipient, WEI)
            assertThrows(IllegalArgumentException::class.java) { chain.submit(tx) }
            chain.addBalance(tx.from, WEI * BigInteger.TEN)
            chain.submit(tx)
            assertThrows(IllegalArgumentException::class.java) { chain.submit(tx) }
            assertThrows(IllegalArgumentException::class.java) { chain.submit(tx.copy(value = WEI * BigInteger.TWO)) }
        } finally {
            chain.close()
        }
    }

    @Test fun `contract deployment installs runtime code and read only call executes it`() {
        val chain = ChainService(directory.toString(), "", false)
        try {
            chain.addBalance(Crypto.address(key), WEI * BigInteger.TEN)
            // Initialization code copies and returns a runtime that returns uint256(42).
            val init = "0x600a600c600039600a6000f3602a60005260206000f3"
            val tx = chain.submit(chain.sign(key, null, BigInteger.ZERO, init, gasLimit = BigInteger.valueOf(200000)))
            chain.produce()
            val receipt = chain.receipt(tx.hash)
            assertEquals("0x1", receipt["status"])
            val address = receipt["contractAddress"] as String
            assertEquals("0x602a60005260206000f3", chain.bytecode(address))
            val call = chain.call(address, "0x")
            assertTrue(call.success)
            assertEquals("0x" + "00".repeat(31) + "2a", call.output)
        } finally {
            chain.close()
        }
    }
}
