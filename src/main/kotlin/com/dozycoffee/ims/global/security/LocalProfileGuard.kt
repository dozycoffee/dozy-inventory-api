package com.dozycoffee.ims.global.security

import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import org.springframework.core.env.Profiles
import org.springframework.stereotype.Component

/** 인증을 우회하는 `local` 프로필이 운영 프로필과 함께 켜지면 기동을 막는다 */
@Component
@Profile("local")
class LocalProfileGuard(
    environment: Environment,
) {
    init {
        check(!environment.acceptsProfiles(Profiles.of("prod"))) {
            "local 프로필은 인증을 우회하므로 prod 프로필과 함께 사용할 수 없습니다."
        }
    }
}
