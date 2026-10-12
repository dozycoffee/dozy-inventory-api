package com.dozycoffee.inventory.adjustment.adapter.out.wms

import com.dozycoffee.auth.starter.DozySystemClientReactiveAutoConfiguration
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockPort
import com.dozycoffee.inventory.adjustment.application.port.out.WmsStockQuantity
import com.dozycoffee.inventory.adjustment.domain.exception.WmsUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * WMS의 창고 수량 조회 API를 호출한다(ADR-0033). `dozy-auth` system client가 켜져 있으면(`dozy.auth.client.enabled`) 스타터가 만든
 * `WebClient`가 system 토큰을 붙이고, 꺼져 있으면(로컬 개발) 토큰 없이 호출한다. 주소는 `inventory.reconciliation.wms.base-url`이고
 * 비어 있으면 조회가 실패한다. 연결 실패, 시간 초과, 오류 응답, 계약에 맞지 않는 응답은 모두 [WmsUnavailableException]이다.
 */
@Component
class WmsStockHttpClient(
    @Qualifier(DozySystemClientReactiveAutoConfiguration.WEB_CLIENT_BEAN_NAME) builders: ObjectProvider<WebClient.Builder>,
    @Value("\${inventory.reconciliation.wms.base-url:}") private val baseUrl: String,
    @Value("\${inventory.reconciliation.wms.timeout:PT10S}") private val timeout: Duration,
) : WmsStockPort {
    private val webClient: WebClient by lazy { builders.getIfAvailable { WebClient.builder() }.baseUrl(baseUrl).build() }

    override suspend fun findQuantities(
        warehouseId: Long,
        productId: Long?,
        lotNumber: String?,
    ): List<WmsStockQuantity> {
        if (baseUrl.isBlank()) {
            throw WmsUnavailableException(
                IllegalStateException("WMS 주소(inventory.reconciliation.wms.base-url)가 설정되지 않았다"),
            )
        }
        try {
            val payload: WmsStockPayload =
                webClient
                    .get()
                    .uri { builder ->
                        builder.path(PATH).queryParam("warehouseId", warehouseId)
                        productId?.let { builder.queryParam("productId", it) }
                        lotNumber?.let { builder.queryParam("lotNumber", it) }
                        builder.build()
                    }.retrieve()
                    .bodyToMono(WmsStockPayload::class.java)
                    .timeout(timeout)
                    .awaitSingle()
            return payload.toQuantities()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw WmsUnavailableException(e)
        }
    }

    private companion object {
        const val PATH: String = "/api/v1/stock-quantities"
    }
}
