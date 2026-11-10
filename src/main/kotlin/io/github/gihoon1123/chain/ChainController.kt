package io.github.gihoon1123.chain

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.server.ResponseStatusException
import java.math.BigInteger

@RestController
class ChainController(
    private val chain: ChainService,
) {
    @GetMapping("/account/{address}")
    fun account(
        @PathVariable address: String,
    ): Any = chain.account(address).json()

    @PostMapping("/account/create-wallet")
    @ResponseStatus(HttpStatus.CREATED)
    fun wallet(): Any = chain.createWallet()

    @PostMapping("/account/add-balance")
    @ResponseStatus(HttpStatus.CREATED)
    fun addBalance(
        @RequestBody body: Map<String, Any?>,
    ): Any {
        val address = body.string("address")
        val amount = body.string("amount")
        val account = chain.addBalance(address, amount.number())
        return mapOf("success" to true, "address" to address, "amount" to amount, "newBalance" to account.balance.toString())
    }

    @PostMapping("/transaction/sign")
    @ResponseStatus(HttpStatus.CREATED)
    fun sign(
        @RequestBody body: Map<String, Any?>,
    ): Any =
        signFields(
            chain.sign(
                body.string("privateKey"),
                body["to"]?.toString(),
                body.string("value").number(),
                body["data"]?.toString() ?: "0x",
                body.big("gasPrice", 1_000_000_000),
                body.big("gasLimit", 21000),
            ),
        )

    @PostMapping("/transaction/send")
    @ResponseStatus(HttpStatus.CREATED)
    fun send(
        @RequestBody body: Map<String, Any?>,
    ): Any {
        val unsigned =
            Transaction(
                body.string("from"),
                body["to"]?.toString(),
                body.string("value").number(),
                body.string("nonce").toLong(),
                Signature(body.string("v").toLong(), body.string("r"), body.string("s")),
                "",
                body["data"]?.toString() ?: "0x",
                body.big("gasPrice", 1_000_000_000),
                body.big("gasLimit", 21000),
            )
        val tx = chain.submit(unsigned.copy(hash = Crypto.hash(unsigned.encoded())))
        return mapOf("success" to true, "hash" to tx.hash, "status" to tx.status, "message" to "Transaction submitted to mempool")
    }

    @PostMapping("/transaction/send-native")
    @ResponseStatus(HttpStatus.CREATED)
    fun sendNative(
        @RequestBody body: Map<String, Any?>,
    ): Any {
        val tx =
            chain.submit(
                chain.sign(
                    body.string("privateKey"),
                    body.string("to"),
                    body.string("amount").number(),
                    gasPrice = body.big("gasPrice", 1_000_000_000),
                    gasLimit = body.big("gasLimit", 21000),
                ),
            )
        return mapOf("hash" to tx.hash, "status" to "pending")
    }

    @GetMapping("/transaction/{hash}")
    fun transaction(
        @PathVariable hash: String,
    ): Any = chain.transaction(hash)

    @GetMapping("/transaction/{hash}/receipt")
    fun receipt(
        @PathVariable hash: String,
    ): Any = chain.receipt(hash)

    @GetMapping("/block/latest")
    fun latest(): Any = chain.latest().json()

    @GetMapping("/block/number/{number}")
    fun block(
        @PathVariable number: Long,
    ): Any = chain.block(number).json()

    @GetMapping("/block/hash/{hash}")
    fun blockHash(
        @PathVariable hash: String,
    ): Any = chain.block(hash).json()

    @GetMapping("/block/stats")
    fun stats(): Any = chain.stats()

    @GetMapping("/block/producer-status")
    fun status(): Any = chain.producerStatus()

    @PostMapping("/block/mining/start")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(): Any {
        chain.startMining()
        return mapOf(
            "success" to true,
            "message" to "Block mining started",
            "status" to chain.producerStatus(),
        )
    }

    @PostMapping("/block/mining/stop")
    @ResponseStatus(HttpStatus.CREATED)
    fun stop(): Any {
        chain.stopMining()
        return mapOf(
            "success" to true,
            "message" to "Block mining stopped",
            "status" to chain.producerStatus(),
        )
    }

    @GetMapping("/consensus/stats")
    fun consensus(): Any = chain.consensus.stats()

    @GetMapping("/validator/list")
    fun validators(): Any =
        mapOf(
            "total" to chain.validators.size,
            "validators" to
                chain.validators.map {
                    mapOf(
                        "address" to it,
                        "isActive" to true,
                        "registeredAt" to
                            java.time.Instant
                                .ofEpochMilli(chain.consensus.genesisTime!!)
                                .toString(),
                    )
                },
        )

    @GetMapping("/validator/stats")
    fun validatorStats(): Any =
        mapOf(
            "total" to chain.validators.size,
            "active" to chain.validators.size,
            "inactive" to 0,
            "committeeSize" to 128,
        )

    @GetMapping("/contract/{address}/bytecode")
    fun bytecode(
        @PathVariable address: String,
    ): Any =
        mapOf(
            "address" to address,
            "bytecode" to chain.bytecode(address),
        )

    @PostMapping("/contract/call")
    @ResponseStatus(HttpStatus.CREATED)
    fun call(
        @RequestBody body: Map<String, Any?>,
    ): Any {
        val result = chain.call(body.string("to"), body.string("data"), body["from"]?.toString() ?: chain.validators.first())
        require(result.success) { "Contract execution reverted" }
        return mapOf("result" to result.output, "gasUsed" to result.gasUsed.quantity())
    }

    @GetMapping("/block/logs", "/transaction/logs")
    fun logs(
        @RequestParam(required = false) fromBlock: String?,
        @RequestParam(required = false) toBlock: String?,
        @RequestParam(required = false) address: String?,
        @RequestParam(required = false) topics: String?,
    ): Any {
        val parsed: List<List<String>?>? =
            topics?.let {
                val result = mutableListOf<List<String>?>()
                val json =
                    tools.jackson.databind.json.JsonMapper
                        .builder()
                        .build()
                        .readTree(it)
                for (node in json) {
                    if (node.isNull) {
                        result.add(null)
                    } else if (node.isArray) {
                        val options = mutableListOf<String>()
                        for (value in node) options.add(value.asString())
                        result.add(options)
                    } else {
                        result.add(listOf(node.asString()))
                    }
                }
                result
            }
        return chain.logs(fromBlock, toBlock, address?.split(',')?.map { it.trim() }, parsed)
    }

    private fun signFields(tx: Transaction): Any =
        linkedMapOf(
            "hash" to tx.hash,
            "from" to tx.from,
            "to" to tx.to,
            "value" to tx.value.toString(),
            "nonce" to tx.nonce,
            "v" to tx.signature.v,
            "r" to tx.signature.r,
            "s" to tx.signature.s,
            "gasPrice" to tx.gasPrice.toString(),
            "gasLimit" to tx.gasLimit.toString(),
            "data" to tx.data,
        )
}

private fun Map<String, Any?>.string(key: String): String = requireNotNull(this[key]) { "$key is required" }.toString()

private fun Map<String, Any?>.big(
    key: String,
    default: Long,
): BigInteger = this[key]?.toString()?.number() ?: BigInteger.valueOf(default)

@RestControllerAdvice
class ChainErrors {
    @ExceptionHandler(ResponseStatusException::class)
    fun status(error: ResponseStatusException): ResponseEntity<Any> =
        ResponseEntity.status(error.statusCode).body(
            mapOf(
                "statusCode" to error.statusCode.value(),
                "message" to (error.reason ?: "Error"),
                "error" to HttpStatus.valueOf(error.statusCode.value()).reasonPhrase,
            ),
        )

    @ExceptionHandler(IllegalArgumentException::class)
    fun invalid(error: IllegalArgumentException): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(
            mapOf(
                "statusCode" to 400,
                "message" to (error.message ?: "Bad Request"),
                "error" to "Bad Request",
            ),
        )
}
