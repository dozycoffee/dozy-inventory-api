package com.dozycoffee.inventory.access.application.service

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.dozycoffee.inventory.access.application.port.`in`.command.ReplaceWarehouseAccessCommand
import com.dozycoffee.inventory.access.application.port.out.WarehouseAccessRepository
import com.dozycoffee.inventory.access.domain.model.WarehouseAccess
import com.dozycoffee.inventory.access.domain.valueobject.PrincipalId
import com.dozycoffee.inventory.global.domain.WarehouseScope
import com.dozycoffee.inventory.global.error.InvalidDomainValueException
import com.dozycoffee.inventory.global.error.WarehouseAccessDeniedException
import com.dozycoffee.inventory.global.security.SystemActor
import com.dozycoffee.inventory.global.security.UserActor
import com.dozycoffee.inventory.inventory.fixture.DirectTransactionalOperator
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class WarehouseAccessServiceTest {
    @Mock
    private lateinit var warehouseAccessRepository: WarehouseAccessRepository

    private lateinit var service: WarehouseAccessService

    private val principalId: UUID = UUID.fromString("0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73")
    private val appender: ListAppender<ILoggingEvent> = ListAppender()
    private val serviceLogger: Logger = LoggerFactory.getLogger(WarehouseAccessService::class.java) as Logger

    @BeforeEach
    fun setUp() {
        service = WarehouseAccessService(warehouseAccessRepository, DirectTransactionalOperator())
        appender.start()
        serviceLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        serviceLogger.detachAppender(appender)
    }

    private fun actor(vararg roles: String): UserActor = UserActor(principalId, roles.toSet())

    @Nested
    inner class `소속 교체` {
        @Test
        fun `직원의 소속을 목록으로 교체한다`() =
            runBlocking<Unit> {
                service.replace(ReplaceWarehouseAccessCommand("0199A3C2-8E5A-7F30-8C4B-9D1E2F6A0B73", listOf(10L, 20L)))

                val saved = argumentCaptor<WarehouseAccess>()
                verifyBlocking(warehouseAccessRepository) { replace(saved.capture()) }
                assertEquals(PrincipalId.of(principalId), saved.firstValue.principalId)
                assertEquals(setOf(10L, 20L), saved.firstValue.warehouseIds)
            }

        @Test
        fun `빈 목록도 교체한다`() =
            runBlocking<Unit> {
                service.replace(ReplaceWarehouseAccessCommand(principalId.toString(), emptyList()))

                verifyBlocking(warehouseAccessRepository) { replace(any()) }
            }

        @Test
        fun `형식이 잘못되면 저장하지 않는다`() =
            runBlocking<Unit> {
                assertThrows<InvalidDomainValueException> { service.replace(ReplaceWarehouseAccessCommand("not-uuid", listOf(1L))) }
                assertThrows<InvalidDomainValueException> {
                    service.replace(
                        ReplaceWarehouseAccessCommand(principalId.toString(), listOf(1L, 1L)),
                    )
                }
                assertThrows<InvalidDomainValueException> { service.replace(ReplaceWarehouseAccessCommand(null, listOf(1L))) }

                verifyBlocking(warehouseAccessRepository, never()) { replace(any()) }
            }
    }

    @Nested
    inner class `접근 범위` {
        @Test
        fun `admin이나 service나 요청 밖 작업은 모든 창고이고 사본을 조회하지 않는다`() =
            runBlocking<Unit> {
                assertEquals(WarehouseScope.All, service.resolve(actor("admin")))
                assertEquals(WarehouseScope.All, service.resolve(actor("service")))
                assertEquals(WarehouseScope.All, service.resolve(actor("warehouse_manager", "admin")))
                assertEquals(WarehouseScope.All, service.resolve(SystemActor))

                verifyBlocking(warehouseAccessRepository, never()) { findWarehouseIds(any()) }
            }

        @Test
        fun `창고 관리자는 사본의 창고로 제한된다`() =
            runBlocking<Unit> {
                whenever(warehouseAccessRepository.findWarehouseIds(PrincipalId.of(principalId))).thenReturn(setOf(10L, 20L))

                assertEquals(WarehouseScope.Only(setOf(10L, 20L)), service.resolve(actor("warehouse_manager")))
            }

        @Test
        fun `창고 관리자의 사본이 없으면 거부하고 ERROR 로그를 남긴다`() =
            runBlocking<Unit> {
                whenever(warehouseAccessRepository.findWarehouseIds(PrincipalId.of(principalId))).thenReturn(emptySet())

                assertThrows<WarehouseAccessDeniedException> { service.resolve(actor("warehouse_manager")) }

                val logged: ILoggingEvent = appender.list.single()
                assertEquals(Level.ERROR, logged.level)
                assertEquals(true, logged.formattedMessage.contains("WAREHOUSE_ACCESS_COPY_MISSING"))
                assertEquals(true, logged.formattedMessage.contains(principalId.toString()))
            }

        @Test
        fun `inventory role이 하나도 없으면 거부한다`() =
            runBlocking<Unit> {
                assertThrows<WarehouseAccessDeniedException> { service.resolve(actor()) }

                verifyBlocking(warehouseAccessRepository, never()) { findWarehouseIds(any()) }
            }
    }
}
