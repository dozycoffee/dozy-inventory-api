package com.dozycoffee.inventory.access.application.port.`in`

import com.dozycoffee.inventory.access.application.port.`in`.command.ReplaceWarehouseAccessCommand

interface ReplaceWarehouseAccessUseCase {
    /**
     * 직원의 창고 소속을 [command]의 목록으로 통째로 교체한다. 목록에 없는 창고는 지우고 새 창고는 추가하며 빈 목록은 소속 없음이다.
     * 같은 명령을 다시 실행해도 결과가 같다. 형식이 잘못되면 [com.dozycoffee.inventory.global.error.InvalidDomainValueException]이다.
     */
    suspend fun replace(command: ReplaceWarehouseAccessCommand)
}
