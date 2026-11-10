package io.github.gihoon1123.chain

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigInteger

@RestController
class StablecoinController(
    private val contracts: ContractService,
) {
    private fun send(
        body: Map<String, String>,
        signature: String,
        args: List<Any> = emptyList(),
        value: BigInteger = BigInteger.ZERO,
        slot: String = "vault",
    ): Any =
        contracts.submit(contracts.contract(slot), Abi.call(signature, *args.toTypedArray()), requireNotNull(body["privateKey"]), value)

    @PostMapping("/stablecoin/deposit")
    @ResponseStatus(HttpStatus.CREATED)
    fun deposit(
        @RequestBody body: Map<String, String>,
    ): Any = send(body, "depositCollateral()", value = requireNotNull(body["amount"]).number())

    @PostMapping("/stablecoin/mint")
    @ResponseStatus(HttpStatus.CREATED)
    fun mint(
        @RequestBody body: Map<String, String>,
    ): Any = send(body, "mintStablecoin(uint256)", listOf(requireNotNull(body["stablecoinAmount"]).number()))

    @PostMapping("/stablecoin/redeem")
    @ResponseStatus(HttpStatus.CREATED)
    fun redeem(
        @RequestBody body: Map<String, String>,
    ): Any = send(body, "redeemStablecoin(uint256)", listOf(requireNotNull(body["stablecoinAmount"]).number()))

    @PostMapping("/stablecoin/withdraw")
    @ResponseStatus(HttpStatus.CREATED)
    fun withdraw(
        @RequestBody body: Map<String, String>,
    ): Any = send(body, "withdrawCollateral(uint256)", listOf(requireNotNull(body["amount"]).number()))

    @PostMapping("/stablecoin/liquidate")
    @ResponseStatus(HttpStatus.CREATED)
    fun liquidate(
        @RequestBody body: Map<String, String>,
    ): Any = send(body, "liquidate(address)", listOf(requireNotNull(body["userAddress"])))

    @PostMapping("/stablecoin/transfer")
    @ResponseStatus(HttpStatus.CREATED)
    fun transfer(
        @RequestBody body: Map<String, String>,
    ): Any =
        send(
            body,
            "transfer(address,uint256)",
            listOf(requireNotNull(body["to"]), requireNotNull(body["amount"]).number()),
            slot = "stablecoin",
        )

    @GetMapping("/stablecoin/position/{address}")
    fun position(
        @PathVariable address: String,
    ): Any {
        val words = Abi.words(contracts.read("vault", "getPosition(address)", address))
        require(words.size == 3) { "Invalid position response" }
        return mapOf("collateralAmount" to "0x${words[0]}", "debtAmount" to "0x${words[1]}", "collateralRatio" to "0x${words[2]}")
    }

    @GetMapping("/stablecoin/health/{address}")
    fun health(
        @PathVariable address: String,
    ): Any =
        mapOf(
            "result" to contracts.read("vault", "isHealthy(address)", address),
        )

    @GetMapping("/stablecoin/balance/{address}")
    fun balance(
        @PathVariable address: String,
    ): Any =
        mapOf(
            "balance" to contracts.read("stablecoin", "balanceOf(address)", address),
        )
}
