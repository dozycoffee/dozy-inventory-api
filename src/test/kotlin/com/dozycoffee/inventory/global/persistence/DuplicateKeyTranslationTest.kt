package com.dozycoffee.inventory.global.persistence

import com.dozycoffee.inventory.global.error.DomainException
import com.dozycoffee.inventory.global.error.ErrorCode
import com.dozycoffee.inventory.global.error.ErrorType
import com.dozycoffee.inventory.support.InventoryIntegrationTest
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated

internal enum class DuplicateErrorCode(
    override val errorType: ErrorType,
    override val code: String,
    override val message: String,
) : ErrorCode {
    DUPLICATED(ErrorType.CONFLICT, "INV_SAMPLE_DUPLICATED", "이미 존재합니다."),
}

internal class DuplicatedException : DomainException(DuplicateErrorCode.DUPLICATED)

@InventoryIntegrationTest
class DuplicateKeyTranslationTest {
    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @AfterEach
    fun cleanUp() =
        runBlocking<Unit> {
            execute("DELETE FROM lot")
            execute("DELETE FROM product")
        }

    @Test
    fun `벤더 코드 1062가 있는 무결성 위반은 중복 키로 판별한다`() {
        val duplicate: DataIntegrityViolationException =
            DataIntegrityViolationException("dup", R2dbcDataIntegrityViolationException("Duplicate entry", "23000", 1062))
        val other: DataIntegrityViolationException =
            DataIntegrityViolationException("fk", R2dbcDataIntegrityViolationException("FK fails", "23000", 1452))

        assertThat(duplicate.isDuplicateKey()).isTrue()
        assertThat(other.isDuplicateKey()).isFalse()
    }

    @Test
    fun `유니크 제약 위반은 도메인 예외로 변환된다`() =
        runBlocking<Unit> {
            insertProduct(id = 1, code = "P-1")

            assertThrows<DuplicatedException> {
                translatingDuplicateKey(duplicate = { DuplicatedException() }) { insertProduct(id = 2, code = "P-1") }
            }
            Unit
        }

    @Test
    fun `외래키 위반은 변환하지 않고 그대로 던진다`() =
        runBlocking<Unit> {
            assertThrows<DataIntegrityViolationException> {
                translatingDuplicateKey(duplicate = { DuplicatedException() }) {
                    execute(
                        """
                        INSERT INTO lot (lot_id, product_id, lot_number, lot_status, created_at, created_by, updated_at, updated_by)
                        VALUES (1, 999, 'LOT-1', 'NORMAL', NOW(6), 't', NOW(6), 't')
                        """.trimIndent(),
                    )
                }
            }
            Unit
        }

    private suspend fun execute(sql: String): Long = databaseClient.sql(sql).fetch().awaitRowsUpdated()

    private suspend fun insertProduct(
        id: Long,
        code: String,
    ) {
        execute(
            """
            INSERT INTO product (product_id, product_code, product_name, category, unit, product_status,
                                 created_at, created_by, updated_at, updated_by)
            VALUES ($id, '$code', 'name', 'BEAN', 'KG', 'ACTIVE', NOW(6), 't', NOW(6), 't')
            """.trimIndent(),
        )
    }
}
