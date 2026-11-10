package io.github.gihoon1123.chain

import tools.jackson.databind.json.JsonMapper
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

object GenesisTool {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 1..2) { "Usage: generateGenesis <new-output-directory> [account-count=256]" }
        val directory = Path.of(args[0])
        require(!Files.exists(directory)) { "Output directory already exists; refusing to replace keys" }
        val count = args.getOrNull(1)?.toInt() ?: 256
        require(count in 1..10000)
        Files.createDirectories(directory)
        val accounts =
            (0 until count).map { index ->
                val pair = Crypto.generateKeyPair()
                linkedMapOf("index" to index, "address" to pair.address, "privateKey" to pair.privateKey, "publicKey" to pair.publicKey)
            }
        val mapper = JsonMapper.builder().build()
        val keys = directory.resolve("genesis-accounts.json")
        Files.writeString(keys, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(accounts))
        runCatching { Files.setPosixFilePermissions(keys, PosixFilePermissions.fromString("rw-------")) }
        val alloc = accounts.associate { it.getValue("address") to mapOf("balance" to (WEI * BigInteger.valueOf(1_000_000)).toString()) }
        Files.writeString(
            directory.resolve("genesis.json"),
            mapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                mapOf(
                    "chainId" to 999,
                    "alloc" to alloc,
                ),
            ),
        )
        println(
            "Generated $count local development accounts in $directory. Private keys were written to a restricted file and are not printed.",
        )
    }
}
