package com.dozycoffee.inventory.access.adapter.`in`.event

import com.dozycoffee.inventory.access.application.port.`in`.ReplaceWarehouseAccessUseCase
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import kotlinx.coroutines.runBlocking
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper

/**
 * WMS의 직원 창고 소속 변경 이벤트를 받아 사본을 교체한다(ADR-0032). 한 건씩 처리하고 실패하면 예외를 던져 컨테이너의 오류 처리기가
 * 다시 시도한 뒤 `{토픽}.dlt`로 보낸다(`KafkaConsumerConfig`). 교체는 멱등이라 중복 전달과 재시도에 안전하다. 요청 밖 작업이라 [SystemActor]로 실행한다.
 * 리스너 컨테이너의 스레드에서 실행되므로 코루틴 경계에서 완료를 기다린다. `inventory.warehouse-access.listener.enabled=false`이면
 * 만들어지지 않는다(테스트에서 끈다).
 */
@Component
@ConditionalOnProperty(prefix = "inventory.warehouse-access.listener", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WarehouseAccessEventListener(
    private val replaceWarehouseAccessUseCase: ReplaceWarehouseAccessUseCase,
    private val jsonMapper: JsonMapper,
) {
    @KafkaListener(
        id = "warehouse-access-listener",
        idIsGroup = false,
        topics = ["\${inventory.warehouse-access.listener.topic:dozy.wms.warehouse-access}"],
    )
    fun onMessage(record: ConsumerRecord<String, String>) {
        val message: WarehouseAccessChangedMessage = jsonMapper.readValue(record.value(), WarehouseAccessChangedMessage::class.java)
        runBlocking { ActorContext.with(SystemActor) { replaceWarehouseAccessUseCase.replace(message.toCommand()) } }
        log.info("직원 창고 소속을 교체했다: principalId={}, 창고 {}개, eventId={}", message.principalId, message.warehouseIds?.size, message.eventId)
    }

    private companion object {
        private val log: Logger = LoggerFactory.getLogger(WarehouseAccessEventListener::class.java)
    }
}
