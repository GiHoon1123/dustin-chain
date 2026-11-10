package io.github.gihoon1123.chain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["chain.data-dir=build/http-test-state", "chain.auto-mine=false", "chain.bot-enabled=false"],
)
class HttpIntegrationTest {
    @LocalServerPort private var port = 0

    @Autowired private lateinit var chain: ChainService
    private val client = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()

    private fun request(
        path: String,
        body: Any? = null,
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI("http://localhost:$port$path"))
        if (body !=
            null
        ) {
            builder
                .header(
                    "Content-Type",
                    "application/json",
                ).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
        } else {
            builder.GET()
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    @Test fun `REST wallet sign submit mine query receipt survives all Spring bindings`() {
        val wallet = request("/account/create-wallet", emptyMap<String, String>())
        assertEquals(201, wallet.statusCode())
        val key = mapper.readTree(wallet.body())["privateKey"].asString()
        val to = "0x" + "11".repeat(20)
        val sent = request("/transaction/send-native", mapOf("privateKey" to key, "to" to to, "amount" to "1"))
        assertEquals(201, sent.statusCode())
        val hash = mapper.readTree(sent.body())["hash"].asString()
        chain.produce()
        val receipt = request("/transaction/$hash/receipt")
        assertEquals(200, receipt.statusCode())
        assertEquals("0x1", mapper.readTree(receipt.body())["status"].asString())
        assertEquals(200, request("/block/latest").statusCode())
        assertTrue(mapper.readTree(request("/bot/status").body())["isRunning"].asBoolean() == false)
        assertEquals(400, request("/account/not-an-address").statusCode())
    }
}
