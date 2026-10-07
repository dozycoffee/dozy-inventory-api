# ADR-0011: dozy-auth 인증 연동

## 상태

Accepted (2026-10-06). audience·role 이름 `ims`는 [ADR-0018](0018-rename-ims-to-inventory.md)에서 `inventory`로 바꿨다

## 배경 (Context)

F-020에서 `dozy-auth` 스타터(0.2.1, WebFlux)를 붙인다. 확인한 사실: 스타터는 토큰 검증(RS256, JWKS), `{audience}:` role의 `ROLE_{code}` 변환, 401·403 Problem Details, `auth-test`(`DozyTestTokens`, `@WithDozyPrincipal`)를 제공하고 IMS에 필요한 기능이 모두 있다. system 토큰에는 클라이언트 이름이 없고 `principalId`(UUID)만 있다.
ADR-0006은 role을 `ims:service`와 `ims:admin` 둘로 정했는데, 창고 관리자와 본사 관리자를 구분할 수 없다.

## 결정 (Decision)

1. **role은 기능 인가만 맡고 3개로 나눈다.** `ims:service`(서비스 간 호출, system client), `ims:warehouse_manager`(창고 관리자, 배정된 창고만), `ims:admin`(본사 관리자, 전체 창고와 마스터 관리·승인). 창고별 접근 범위는 role이 아니라 `warehouse_access` 사본으로 판단한다(F-021). 기능별로 더 잘게 나누는 것은 `dozy-auth`의 "role은 크게" 원칙에 따라 하지 않고, 필요하면 서비스 설정(예: 조정 승인 임계치)으로 처리한다. ADR-0006의 role 구성(2개)을 이 결정으로 대체한다.
2. **보안 체인은 스타터의 디코더·권한 변환기·401/403 핸들러를 쓰고 체인은 직접 정의한다.** `local` 프로필은 토큰 없이 모든 role을 가진 고정 개발 사용자(`LocalActorProvider`)로 동작한다. `local` 외 프로필은 토큰이 없으면 401이다. `local`이 `prod`와 함께 켜지면 기동을 막는 `LocalProfileGuard`는 유지한다.
3. **행위자는 `SecurityContextActorProvider`가 보안 컨텍스트에서 꺼낸다.** 보안 컨텍스트가 없으면 요청 밖 작업이므로 `SystemActor`다. system client도 직원처럼 `principalId`(UUID)를 `UserActor`에 담는다.
4. **`inventory_history.requester_service`는 system 토큰의 `principalId`(UUID)를 그대로 저장한다.** 사람이 읽으려면 `dozy-auth` 관리자 API(`GET /admin/system-clients`)로 매핑해 본다. 이름 매핑 설정은 만들지 않는다.
5. **`GlobalExceptionHandler`는 `AccessDeniedException`·`AuthenticationException`을 다시 던져** 필터 체인의 401·403 핸들러가 응답하게 한다(안 하면 500이 된다).
6. **이번에는 CORS를 두지 않고 공개 경로(`dozy.auth.public-paths`)도 두지 않는다.** 어드민 콘솔이 IMS를 직접 호출하게 되면 추가한다.
7. 설정은 `dozy.auth.audience=ims`, `accepted-realms=[internal]`, `issuer-base-uri`(환경변수 `AUTH_ISSUER_BASE_URI`)다. 스타터는 GitHub Packages에서 받으므로 빌드에 `GPR_USER`·`GPR_TOKEN`(또는 `gpr.user`·`gpr.token`)이 필요하고 CI는 `packages: read`와 `GITHUB_TOKEN`을 쓴다.

## 결과 (Consequences)

- 얻는 것: 토큰 검증·role 변환을 직접 만들지 않는다. 본사와 창고 관리자를 role로 구분하고 범위는 소속 데이터로 판단한다. `auth-test`로 Auth 서버 없이 401·403·role을 실제 검증 체인으로 테스트한다.
- 감수하는 것: 빌드와 CI에 GitHub Packages 접근이 필요하다. role이 3개라 서비스 간 권한 분리(예: OMS는 예약만)는 여전히 role로 하지 못한다. system client의 이름이 이력에 남지 않아 UUID와 클라이언트의 대응은 `dozy-auth`에서 확인해야 한다. role code는 등록 후 바꿀 수 없다.
- 운영 작업(개발·테스트의 선행 조건은 아님): 실제 서버 연동과 배포 전에 `dozy-auth`에서 `ims` audience(owner), `ims:service`·`ims:warehouse_manager`·`ims:admin` role, system client(`svc-wms`, `svc-oms`, `svc-store`)를 등록한다.
