package com.dozycoffee.inventory.adjustment.adapter.`in`.scheduler

import com.dozycoffee.inventory.adjustment.application.port.`in`.ReconcileStockUseCase
import com.dozycoffee.inventory.adjustment.application.port.`in`.result.ReconciliationRunResult
import com.dozycoffee.inventory.global.security.ActorContext
import com.dozycoffee.inventory.global.security.SystemActor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever

@ExtendWith(MockitoExtension::class)
class ReconciliationSchedulerTest {
    @Mock
    private lateinit var reconcileStockUseCase: ReconcileStockUseCase

    private lateinit var scheduler: ReconciliationScheduler

    @BeforeEach
    fun setUp() {
        scheduler = ReconciliationScheduler(reconcileStockUseCase)
    }

    @Test
    fun `시스템 주체로 대사를 실행한다`() =
        runBlocking<Unit> {
            var actor: Any? = null
            val capturing =
                object : ReconcileStockUseCase {
                    override suspend fun reconcile(): List<ReconciliationRunResult> {
                        actor = ActorContext.current()
                        return emptyList()
                    }
                }

            ReconciliationScheduler(capturing).reconcile()

            assertEquals(SystemActor, actor)
        }

    @Test
    fun `대사가 실패해도 예외를 밖으로 던지지 않는다`() =
        runBlocking<Unit> {
            whenever(reconcileStockUseCase.reconcile()).thenThrow(IllegalStateException("boom"))

            scheduler.reconcile()

            verifyBlocking(reconcileStockUseCase) { reconcile() }
        }
}
