package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path

class StateStoreTest {
    @TempDir lateinit var directory: Path
    private val alice = "0x" + "01".repeat(20)
    private val bob = "0x" + "02".repeat(20)

    @Test fun `state persists RLP records and root across restart`() {
        val root =
            StateStore(directory).use { store ->
                store.put(Account(alice, WEI, 3))
                store.put(Account(bob, WEI * BigInteger.TWO, 0))
                store.flush()
                store.root()
            }
        StateStore(directory).use { store ->
            assertEquals(WEI, store.get(alice)?.balance)
            assertEquals(3, store.get(alice)?.nonce)
            assertEquals(root, store.root())
        }
    }

    @Test fun `nested checkpoints revert failed effects without losing outer changes`() {
        StateStore(directory).use { store ->
            store.checkpoint()
            store.put(Account(alice, WEI))
            store.checkpoint()
            store.put(Account(bob, WEI))
            store.revert()
            assertNull(store.get(bob))
            assertEquals(WEI, store.get(alice)?.balance)
            assertThrows(IllegalStateException::class.java) { store.flush() }
            store.accept()
            store.flush()
        }
    }

    @Test fun `MPT root is independent of input ordering and includes account changes`() {
        val pairs = listOf(byteArrayOf(1, 2) to byteArrayOf(3), byteArrayOf(1, 3) to byteArrayOf(4))
        assertEquals("0x9eaddea9f2442f469856e9efca085b87c8179e9bbe9d4cb3dcaa5efb4749777d", Trie.root(pairs))
        assertEquals(Trie.root(pairs), Trie.root(pairs.reversed()))
        assertEquals(EMPTY_ROOT, Trie.root(emptyList()))
    }
}
