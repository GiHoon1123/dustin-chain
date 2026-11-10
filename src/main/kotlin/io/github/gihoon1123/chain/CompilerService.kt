package io.github.gihoon1123.chain

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

@Service
class CompilerService(
    @Value("\${chain.solc-binary:solc}") private val binary: String,
) {
    private val mapper = JsonMapper.builder().build()

    @Synchronized fun compile(name: String? = null): Map<String, Any?> {
        require(name == null || name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))) { "Invalid contract name" }
        val directory = Path.of("contracts").toAbsolutePath()
        val paths =
            if (name ==
                null
            ) {
                Files.walk(directory).use { it.filter { file -> file.toString().endsWith(".sol") }.sorted().toList() }
            } else {
                listOf(directory.resolve("$name.sol"))
            }
        val found = paths.isNotEmpty() && paths.all { Files.isRegularFile(it) }
        require(found) { "Contract file not found: $name" }
        val sources = paths.associate { directory.relativize(it).toString() to mapOf("content" to Files.readString(it)) }
        val input =
            mapOf(
                "language" to "Solidity",
                "sources" to sources,
                "settings" to
                    mapOf(
                        "outputSelection" to mapOf("*" to mapOf("*" to listOf("abi", "evm.bytecode.object"))),
                        "optimizer" to mapOf("enabled" to false, "runs" to 200),
                    ),
            )
        val inputFile = Files.createTempFile("chain-solc-input-", ".json")
        val outputFile = Files.createTempFile("chain-solc-output-", ".json")
        val errorFile = Files.createTempFile("chain-solc-error-", ".log")
        try {
            Files.writeString(inputFile, mapper.writeValueAsString(input))
            val process =
                ProcessBuilder(
                    binary,
                    "--standard-json",
                ).redirectInput(inputFile.toFile()).redirectOutput(outputFile.toFile()).redirectError(errorFile.toFile()).start()
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("Compilation timed out")
            }
            check(process.exitValue() == 0) { "Compilation failed: ${Files.readString(errorFile).take(2000)}" }
            val output = mapper.readTree(Files.readString(outputFile))
            val errors =
                output["errors"]?.toList().orEmpty().filter { it["severity"].asString() == "error" }.map {
                    it["formattedMessage"]?.asString()
                        ?: it["message"].asString()
                }
            val results = mutableListOf<Map<String, Any?>>()
            output["contracts"]?.properties()?.forEach { file ->
                file.value.properties().forEach { artifact ->
                    val bytecode =
                        artifact.value["evm"]
                            ?.get("bytecode")
                            ?.get("object")
                            ?.asString() ?: ""
                    if (bytecode.isNotEmpty()) {
                        results +=
                            mapOf(
                                "name" to artifact.key,
                                "bytecode" to "0x$bytecode",
                                "abi" to artifact.value["abi"],
                            )
                    }
                }
            }
            if (results.isNotEmpty()) save(results)
            return if (name ==
                null
            ) {
                mapOf("success" to (errors.isEmpty() && results.isNotEmpty()), "contracts" to results, "errors" to errors)
            } else {
                mapOf(
                    "success" to (errors.isEmpty() && results.isNotEmpty()),
                    "contract" to results.firstOrNull { it["name"] == name },
                    "errors" to errors,
                )
            }
        } finally {
            Files.deleteIfExists(inputFile)
            Files.deleteIfExists(outputFile)
            Files.deleteIfExists(errorFile)
        }
    }

    private fun save(contracts: List<Map<String, Any?>>) {
        for ((file, field) in listOf("contract-bytecodes.json" to "bytecode", "contract-abis.json" to "abi")) {
            val path = Path.of(file)
            val existing = if (Files.exists(path)) mapper.readTree(Files.readString(path))["contracts"]?.toList().orEmpty() else emptyList()
            val names = contracts.map { it["name"] }.toSet()
            val merged =
                existing.filter { it["name"].asString() !in names } + contracts.map { mapOf("name" to it["name"], field to it[field]) }
            val temporary = Files.createTempFile(path.toAbsolutePath().parent, "compile-", ".json")
            Files.writeString(temporary, mapper.writeValueAsString(mapOf("contracts" to merged)))
            Files.move(temporary, path.toAbsolutePath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    @Synchronized fun results(name: String? = null): Map<String, Any> =
        listOf(
            "bytecodes" to "contract-bytecodes.json",
            "abis" to "contract-abis.json",
        ).associate { (key, file) ->
            val path = Path.of(file)
            val contracts =
                if (Files.exists(
                        path,
                    )
                ) {
                    mapper.readTree(Files.readString(path))["contracts"]?.toList().orEmpty()
                } else {
                    emptyList()
                }
            key to mapOf("contracts" to if (name == null) contracts else contracts.filter { it["name"].asString() == name })
        }

    fun artifact(name: String): JsonNode? = results(name)["bytecodes"].let { mapper.valueToTree<JsonNode>(it)["contracts"].firstOrNull() }

    fun abi(name: String): JsonNode? = results(name)["abis"].let { mapper.valueToTree<JsonNode>(it)["contracts"].firstOrNull()?.get("abi") }
}

@RestController
class CompilerController(
    private val compiler: CompilerService,
) {
    @PostMapping("/compiler/compile-all")
    @ResponseStatus(HttpStatus.CREATED)
    fun all(): Any = compiler.compile()

    @PostMapping("/compiler/compile/{name}")
    @ResponseStatus(HttpStatus.CREATED)
    fun one(
        @PathVariable name: String,
    ): Any = compiler.compile(name)

    @GetMapping("/compiler/results")
    fun results(): Any = compiler.results()

    @GetMapping("/compiler/results/{name}")
    fun result(
        @PathVariable name: String,
    ): Any = compiler.results(name)
}
