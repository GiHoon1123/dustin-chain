package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path

class BotServiceTest {
    @TempDir lateinit var directory: Path

    @Test fun `bot stop suppresses generation and start submits mined native transfers`() {
        val file = directory.resolve("accounts.json")
        val keys = (1..3).map { "0x" + it.toString(16).padStart(64, '0') }
        Files.writeString(
            file,
            JsonMapper.builder().build().writeValueAsString(
                keys.mapIndexed { i, key ->
                    mapOf(
                        "index" to 100 + i,
                        "privateKey" to key,
                    )
                },
            ),
        )
        val chain = ChainService(directory.resolve("state").toString(), "", false)
        val contracts =
            ContractService(chain, CompilerService("unconfigured-solc"), file.toString(), directory.resolve("deployed.json").toString())
        val bot = BotService(chain, contracts, file.toString(), false)
        try {
            keys.forEach { chain.addBalance(Crypto.address(it), WEI * BigInteger.valueOf(1000)) }
            bot.generateNative()
            assertEquals(0, chain.pool.pending().size)
            bot.start()
            bot.generateNative()
            val submitted = (bot.status()["totalTransactions"] as Long).toInt()
            assertTrue(submitted in 4..5)
            val mined = (0 until 5).flatMap { chain.produce().transactions }
            assertEquals(submitted, mined.size)
            mined.forEach { assertEquals("0x1", chain.receipt(it.hash)["status"]) }
            bot.stop()
            bot.generateNative()
            assertEquals(submitted.toLong(), bot.status()["totalTransactions"])
        } finally {
            bot.close()
            chain.close()
        }
    }
}
