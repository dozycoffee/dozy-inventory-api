package com.dozycoffee.inventory.global.config

import com.dozycoffee.inventory.global.error.DomainException
import org.apache.kafka.common.TopicPartition
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.listener.CommonErrorHandler
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff
import tools.jackson.core.JacksonException

/**
 * Kafka 리스너의 오류 처리. 처리에 실패하면 [RETRY_INTERVAL_MILLIS]ms 간격으로 [RETRY_COUNT]번 다시 시도하고, 그래도 실패하면
 * `{토픽}.dlt`로 보낸 뒤 다음 메시지로 넘어간다(한 메시지가 파티션을 막지 않는다). 형식이 잘못된 메시지(JSON이 아님, 도메인 값 오류)는
 * 다시 시도해도 같으므로 바로 보낸다. DLT는 파티션 수가 원 토픽과 달라도 받도록 파티션을 지정하지 않는다.
 */
@Configuration
class KafkaConsumerConfig {
    @Bean
    fun kafkaConsumerErrorHandler(kafkaOperations: KafkaOperations<String, String>): CommonErrorHandler {
        val recoverer =
            DeadLetterPublishingRecoverer(kafkaOperations) { record, _ -> TopicPartition(record.topic() + DLT_SUFFIX, -1) }
        return DefaultErrorHandler(recoverer, FixedBackOff(RETRY_INTERVAL_MILLIS, RETRY_COUNT)).apply {
            addNotRetryableExceptions(JacksonException::class.java, IllegalArgumentException::class.java, DomainException::class.java)
        }
    }

    private companion object {
        const val DLT_SUFFIX: String = ".dlt"
        const val RETRY_INTERVAL_MILLIS: Long = 1000L
        const val RETRY_COUNT: Long = 3L
    }
}
