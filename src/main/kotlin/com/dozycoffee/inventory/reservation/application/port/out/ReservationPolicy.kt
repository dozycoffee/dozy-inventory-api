package com.dozycoffee.inventory.reservation.application.port.out

import java.time.Duration

/** 예약의 업무 설정. 채널별 TTL 상한처럼 환경에 따라 달라지는 값을 포트로 받는다 */
fun interface ReservationPolicy {
    /** 채널의 예약 만료 시각 상한(생성 시각 + 이 값). 설정에 없는 채널은 기본값을 쓴다 */
    fun maxTtlFor(channel: String): Duration
}
