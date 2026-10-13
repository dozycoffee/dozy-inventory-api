package com.dozycoffee.inventory.outbox

import com.dozycoffee.inventory.global.domain.UuidV7

/** 순번으로 만드는 고정 이벤트 ID(같은 순번은 같은 값). 재구성한 이벤트를 동등성·중복 검사에 쓸 때 쓴다 */
fun eventIdOf(sequence: Long): String = "00000000-0000-7000-8000-%012d".format(sequence)

/** 저장 테스트용 새 이벤트 ID. 호출마다 달라 유니크 제약에 걸리지 않는다 */
fun newEventId(): String = UuidV7.generate(1_800_000_000_000L)
