package io.github.gihoon1123.chain

import java.math.BigInteger
import java.time.Clock
import java.time.Instant

class Consensus(
    private val clock: Clock = Clock.systemUTC(),
    var genesisTime: Long? = null,
) {
    fun currentSlot(): Long = (clock.millis() - requireNotNull(genesisTime) { "Genesis Time not set" }) / 12000

    fun stats(): Map<String, Any?> =
        if (genesisTime ==
            null
        ) {
            linkedMapOf("genesisTime" to null, "currentSlot" to null, "currentEpoch" to null)
        } else {
            val slot = currentSlot()
            val epoch = slot / 32
            linkedMapOf(
                "genesisTime" to Instant.ofEpochMilli(genesisTime!!).toString(),
                "currentSlot" to slot,
                "currentEpoch" to epoch,
                "epochStartSlot" to epoch * 32,
                "epochEndSlot" to (epoch + 1) * 32 - 1,
                "slotsPerEpoch" to 32,
            )
        }

    fun proposer(
        slot: Long,
        validators: List<String>,
    ): String {
        require(validators.isNotEmpty()) { "No active validators" }
        val seed = Crypto.hashUtf8("randao-$slot-proposer").removePrefix("0x").take(16)
        return validators[BigInteger(seed, 16).mod(BigInteger.valueOf(validators.size.toLong())).toInt()]
    }

    fun committee(
        slot: Long,
        validators: List<String>,
    ): List<String> {
        val result = validators.toMutableList()
        val seed = Crypto.hashUtf8("randao-$slot-committee").removePrefix("0x")
        var seedIndex = 0
        for (i in result.lastIndex downTo 1) {
            val word = seed.substring(seedIndex * 8, seedIndex * 8 + 8)
            val j = BigInteger(word, 16).mod(BigInteger.valueOf(i + 1L)).toInt()
            seedIndex = (seedIndex + 1) % (seed.length / 8)
            val previous = result[i]
            result[i] = result[j]
            result[j] = previous
        }
        return result.take(128)
    }

    fun hasSupermajority(
        attestations: Int,
        size: Int,
    ): Boolean = attestations >= (size * 2 + 2) / 3
}
