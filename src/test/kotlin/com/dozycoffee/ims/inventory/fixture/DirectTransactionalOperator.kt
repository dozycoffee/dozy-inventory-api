package com.dozycoffee.ims.inventory.fixture

import org.springframework.transaction.ReactiveTransaction
import org.springframework.transaction.reactive.TransactionCallback
import org.springframework.transaction.reactive.TransactionalOperator
import reactor.core.publisher.Flux

/** 서비스 단위 테스트용. 트랜잭션 없이 콜백을 그대로 실행한다. 롤백은 흉내 내지 않는다 */
class DirectTransactionalOperator : TransactionalOperator {
    override fun <T : Any> execute(action: TransactionCallback<T>): Flux<T> = Flux.defer { action.doInTransaction(NoTransaction) }

    private object NoTransaction : ReactiveTransaction {
        override fun isNewTransaction(): Boolean = true

        override fun setRollbackOnly() = Unit

        override fun isRollbackOnly(): Boolean = false

        override fun isCompleted(): Boolean = false
    }
}
