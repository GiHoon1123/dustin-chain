package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ConsensusTest {
    @Test fun `slot epoch and quorum retain source constants`() {
        val consensus = Consensus(Clock.fixed(Instant.ofEpochMilli(384000), ZoneOffset.UTC), 0)
        assertEquals(32L, consensus.currentSlot())
        assertEquals(1L, consensus.stats()["currentEpoch"])
        assertTrue(consensus.hasSupermajority(86, 128))
        assertFalse(consensus.hasSupermajority(85, 128))
    }

    @Test fun `committee matches original JavaScript fixed vector and contains no lost validators`() {
        val consensus = Consensus()
        assertEquals(listOf("v1", "v3", "v0", "v4", "v2"), consensus.committee(7, (0..4).map { "v$it" }))
        val validators = (0..999).map { "v$it" }
        val committee = consensus.committee(7, validators)
        assertEquals(128, committee.size)
        assertEquals(128, committee.toSet().size)
        assertTrue(committee.all { it in validators })
    }
}
