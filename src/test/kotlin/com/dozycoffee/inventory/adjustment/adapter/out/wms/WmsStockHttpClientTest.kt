package com.dozycoffee.inventory.adjustment.adapter.out.wms

import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import com.dozycoffee.inventory.global.domain.QualityStatus
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.ObjectProvider
import org.springframework.web.reactive.function.client.WebClient
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

class WmsStockHttpClientTest {
    private lateinit var server: HttpServer
    private val requests: MutableList<String> = CopyOnWriteArrayList()
    private val authorizations: MutableList<String?> = CopyOnWriteArrayList()
    private var status: Int = 200
    private var body: String = """{"items":[{"productId":100,"lotNumber":"LOT-A","qualityStatus":"NORMAL","quantity":92}]}"""
    private var delayMillis: Long = 0L

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/v1/stock-quantities") { exchange: HttpExchange ->
            requests += exchange.requestURI.toString()
            authorizations += exchange.requestHeaders.getFirst("Authorization")
            if (delayMillis > 0) Thread.sleep(delayMillis)
            val bytes: ByteArray = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun client(
        baseUrl: String = "http://127.0.0.1:${server.address.port}",
        timeout: Duration = Duration.ofSeconds(5),
        tokenFilter: Boolean = false,
    ): WmsStockHttpClient {
        val builder: WebClient.Builder =
            WebClient.builder().also { b ->
                if (tokenFilter) {
                    b.filter {
                        request,
                        next,
                        ->
                        next.exchange(
                            org.springframework.web.reactive.function.client.ClientRequest
                                .from(
                                    request,
                                ).header("Authorization", "Bearer system-token")
                                .build(),
                        )
                    }
                }
            }
        val provider: ObjectProvider<WebClient.Builder> =
            object : ObjectProvider<WebClient.Builder> {
                override fun getObject(vararg args: Any?): WebClient.Builder = builder

                override fun getObject(): WebClient.Builder = builder

                override fun getIfAvailable(): WebClient.Builder = builder

                override fun getIfUnique(): WebClient.Builder = builder
            }
        return WmsStockHttpClient(provider, baseUrl, timeout)
    }

    @Test
    fun `창고 ID로 조회해 수량 목록으로 바꾼다`() =
        runBlocking<Unit> {
            val result: List<WmsStockQuantity> = client().findQuantities(10L, null, null)

            assertEquals(listOf(WmsStockQuantity(100L, "LOT-A", QualityStatus.NORMAL, 92)), result)
            assertEquals("/api/v1/stock-quantities?warehouseId=10", requests.single())
        }

    @Test
    fun `상품과 Lot 번호 조건을 쿼리로 보내고 Lot 번호는 인코딩한다`() =
        runBlocking<Unit> {
            client().findQuantities(10L, 100L, "LOT A/1")

            assertEquals("/api/v1/stock-quantities?warehouseId=10&productId=100&lotNumber=LOT%20A/1", requests.single())
        }

    @Test
    fun `system 토큰 필터가 붙은 WebClient 빌더를 쓰면 인증 헤더를 보낸다`() =
        runBlocking<Unit> {
            client(tokenFilter = true).findQuantities(10L, null, null)

            assertEquals("Bearer system-token", authorizations.single())
        }

    @Test
    fun `오류 응답과 계약에 맞지 않는 응답과 JSON이 아닌 응답은 WMS 조회 실패다`() =
        runBlocking<Unit> {
            status = 500
            assertThrows<WmsUnavailableException> { client().findQuantities(10L, null, null) }
            status = 404
            assertThrows<WmsUnavailableException> { client().findQuantities(10L, null, null) }
            status = 200
            body = """{"items":[{"productId":100}]}"""
            assertThrows<WmsUnavailableException> { client().findQuantities(10L, null, null) }
            body = "not json"
            assertThrows<WmsUnavailableException> { client().findQuantities(10L, null, null) }
        }

    @Test
    fun `시간 안에 응답하지 않으면 WMS 조회 실패다`() =
        runBlocking<Unit> {
            delayMillis = 1500L

            assertThrows<WmsUnavailableException> { client(timeout = Duration.ofMillis(200)).findQuantities(10L, null, null) }
        }

    @Test
    fun `연결할 수 없거나 주소가 없으면 WMS 조회 실패다`() =
        runBlocking<Unit> {
            assertThrows<WmsUnavailableException> { client(baseUrl = "http://127.0.0.1:1").findQuantities(10L, null, null) }
            assertThrows<WmsUnavailableException> { client(baseUrl = "").findQuantities(10L, null, null) }
        }
}
