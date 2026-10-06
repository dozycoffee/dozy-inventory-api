# ADR-0009: 전역 공통 모듈 설계 결정

## 상태

Accepted (2026-10-06)

## 배경 (Context)

오류 응답, 감사, 작업 주체(Actor), 시간 처리는 모든 도메인이 공유한다. WMS의 `global` 모듈을 기준으로 삼되 IMS의 사정에 맞게 정했다.
IMS는 예약 만료(TTL), 유통기한 스캔처럼 시간에 따라 동작이 달라지는 로직이 많고, WMS와 같은 `product` 도메인을 가져 오류 코드가 겹칠 수 있다.
`dozy-auth`는 갱신되어 audience·role·system client 등록 API가 생겼고, 스타터 0.2.1은 WebFlux와 서비스 간 호출용 system token 클라이언트를 지원한다.

## 결정 (Decision)

1. **오류 응답은 `dozy-auth` 규약을 따른다.** RFC 9457 Problem Details에 `code`, `traceId`를 더하고 모든 응답에 `X-Trace-Id`를 싣는다(WMS ADR-0015와 같다). 범용 코드는 `dozy-auth` 에러 코드 표의 이름(`VALIDATION_FAILED`, `INTERNAL_ERROR`, `NOT_FOUND` 등)을 쓴다.
2. **도메인 오류 코드는 `IMS_` 서비스 접두사를 붙인다**(예: `IMS_PRODUCT_NOT_FOUND`). WMS와 같은 `product` 도메인이 있어 도메인 접두사만으로는 `code`가 겹친다.
3. **시간은 `Clock` 빈(Asia/Seoul)을 주입받아 쓴다.** 감사 시각과 비즈니스 시각이 같은 시계를 쓰고, 테스트에서 시간을 고정하거나 앞당길 수 있다. 타입은 `LocalDateTime`(DB는 `DATETIME(6)`)이다. 코드에서 `now()`를 직접 호출하지 않는다.
4. **감사는 Spring Data Auditing + `BaseEntity`로 처리한다.** `Clock`과 `CurrentActorProvider`를 연결한다. `DatabaseClient`로 직접 쓰는 SQL(예약·출고의 조건부 UPDATE)은 Auditing이 동작하지 않으므로 `updated_at`/`updated_by`를 SQL에 직접 넣는다.
5. **`Actor`는 `UserActor`와 `SystemActor`로 시작한다.** 요청 서비스까지 담는 `ServiceActor`는 인증 연동에서 정한다. `CurrentActorProvider`는 `local` 프로필 전용 구현만 두고, 그 외 프로필에서는 빈이 없어 기동에 실패한다. `local`이 `prod`와 함께 켜지면 기동을 막는다(`LocalProfileGuard`).
6. **`Actor`는 Reactor Context로 전달한다(`ActorContext`).** Spring Data Auditing이 호출하는 `CurrentActorProvider`가 같은 컨텍스트로 주체를 안다. 요청 밖 작업(스케줄러)은 `ActorContext.with(SystemActor) { ... }`로 감싸 실행한다.
7. **인증 연동(F-020)을 첫 API(F-008) 앞으로 옮긴다.** 모든 API가 처음부터 권한 검사를 갖추기 위해서다.

## 결과 (Consequences)

- 얻는 것: 한 서비스 안의 오류 형식 통일과 `dozy-auth` 규약 일치, 코드 충돌 방지, 시간 로직의 테스트 용이성, 감사 컬럼 누락 방지, 인증 없이 배포되는 사고 방지.
- 감수하는 것: 시간이 필요한 곳마다 `Clock`을 받아야 한다. 직접 SQL은 감사 컬럼을 수동으로 채워야 한다. 인증 구현이 들어오기 전에는 `local` 프로필 밖에서 앱이 기동하지 않는다. 오류 코드가 길어진다. 첫 API 전에 `dozy-auth` 서버에서 `ims` audience, role, system client를 등록해야 한다(운영 작업).
- 알려진 이슈: system 토큰에는 클라이언트 이름(`svc-wms`)이 없고 `principalId`(UUID)만 있다. ERD의 `requester_service`에 서비스 이름을 토큰만으로 채울 수 없으므로, UUID를 그대로 저장하거나 설정으로 UUID를 이름에 매핑하는 방식을 인증 연동에서 정해야 한다.
- Spring Security 의존성과 401/403 처리는 인증 연동에서 추가한다. 현재 `GlobalExceptionHandler`는 보안 예외를 다루지 않는다.
