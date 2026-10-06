package com.dozycoffee.ims.support

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/** 전체 컨텍스트를 올리는 통합 테스트. 인증을 거치지 않도록 `local` 프로필의 Actor를 쓴다 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@ActiveProfiles("local")
annotation class ImsIntegrationTest
