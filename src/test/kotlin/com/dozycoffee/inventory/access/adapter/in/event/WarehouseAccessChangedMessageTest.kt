package com.dozycoffee.inventory.access.adapter.`in`.event

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.json.JsonMapper

class WarehouseAccessChangedMessageTest {
    private val jsonMapper: JsonMapper = JsonMapper.builder().build()
    private val principal = "0199a3c2-1111-7f30-8c4b-9d1e2f6a0b73"

    private fun parse(json: String): WarehouseAccessChangedMessage = jsonMapper.readValue(json, WarehouseAccessChangedMessage::class.java)

    private fun envelope(
        eventType: String = "WAREHOUSE_ACCESS_CHANGED",
        eventVersion: String = "1",
        source: String = "wms",
        data: String = """{"principalId":"$principal","warehouseIds":[10,20]}""",
    ): String =
        """{"eventId":"0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73","eventType":"$eventType","eventVersion":$eventVersion,""" +
            """"occurredAt":"2026-10-12T10:00:00+09:00","source":"$source","data":$data}"""

    @Nested
    inner class `수신 형식` {
        @Test
        fun `data 아래의 principalId와 warehouseIds로 교체 명령을 만든다`() {
            val command = parse(envelope()).toCommand()

            assertEquals(principal, command.principalId)
            assertEquals(listOf(10L, 20L), command.warehouseIds)
        }

        @Test
        fun `빈 목록과 알 수 없는 필드는 받는다`() {
            val message =
                parse(
                    envelope(
                        data = """{"principalId":"$principal","warehouseIds":[],"extra":1}""",
                    ).replace("\"source\"", "\"x\":1,\"source\""),
                )

            assertEquals(emptyList<Long>(), message.toCommand().warehouseIds)
        }
    }

    @Nested
    inner class `거부` {
        @Test
        fun `유형·버전·발행처가 다르면 거부한다`() {
            assertThrows<IllegalArgumentException> { parse(envelope(eventType = "OTHER")).toCommand() }
            assertThrows<IllegalArgumentException> { parse(envelope(eventVersion = "2")).toCommand() }
            assertThrows<IllegalArgumentException> { parse(envelope(source = "oms")).toCommand() }
        }

        @Test
        fun `평면 형식은 data가 없어 거부한다`() {
            val flat =
                """{"eventType":"WAREHOUSE_ACCESS_CHANGED","eventId":"x","occurredAt":"2026-10-12T10:00:00+09:00",""" +
                    """"principalId":"$principal","warehouseIds":[10]}"""

            assertThrows<IllegalArgumentException> { parse(flat).toCommand() }
        }

        @Test
        fun `data나 warehouseIds가 없으면 거부한다`() {
            assertThrows<IllegalArgumentException> { parse(envelope(data = "null")).toCommand() }
            assertThrows<IllegalArgumentException> { parse(envelope(data = """{"principalId":"$principal"}""")).toCommand() }
            assertThrows<IllegalArgumentException> { parse(envelope().replace(""""eventVersion":1,""", "")).toCommand() }
        }
    }
}
