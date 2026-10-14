# ADR-0040: 어드민 콘솔을 위한 CORS

## 상태

Accepted (2026-10-14). ADR-0011 결정 6("이번에는 CORS를 두지 않는다")을 대체한다. 어드민 콘솔이 inventory 서비스를 브라우저에서 직접 호출하게 되어 추가한다.

## 배경 (Context)

어드민 콘솔(React)이 관리자 재고 조회(ADR-0030)를 브라우저에서 `Authorization: Bearer` 헤더로 직접 호출한다. 헤더가 붙는 요청은 브라우저가 먼저 토큰 없는 preflight(`OPTIONS`)를 보내고, 인증 필터는 핸들러 매핑보다 먼저 실행되므로 CORS를 보안 필터 체인 밖에서 처리하면 preflight와 401·403 응답에 CORS 헤더가 붙지 않는다. 그러면 브라우저는 실제 상태 코드를 읽지 못한다. WMS(`wms.cors.allowed-origins`)와 dozy-auth(`AUTH_CORS_ALLOWED_ORIGINS`)는 이미 같은 방식으로 허용 origin을 설정한다.

## 결정 (Decision)

1. **CORS를 보안 필터 체인 안에서 처리한다.** `SecurityConfig`가 `CorsConfigurationSource`를 만들고 두 체인(`local` 포함) 모두에 `cors { configurationSource = ... }`를 건다. 토큰 없는 preflight는 200, 허용된 origin의 401·403 응답에도 `Access-Control-Allow-Origin`이 붙는다.
2. **허용 origin은 설정으로 둔다.** `inventory.cors.allowed-origins`(환경변수 `CORS_ALLOWED_ORIGINS`, 쉼표 구분, 기본값 `http://localhost:5173`). 와일드카드는 쓰지 않는다. 운영은 콘솔의 origin을 환경변수로 주입한다.
3. **메서드는 `GET`, `POST`, `PATCH`, `OPTIONS`, 헤더는 `Authorization`, `Content-Type`, `Idempotency-Key`만 허용한다.** 인증은 Bearer 토큰이라 쿠키·자격 증명 모드(`Access-Control-Allow-Credentials`)는 켜지 않는다.
4. **허용되지 않은 origin의 요청은 403이다.** `Origin` 헤더가 없는 서버 간 호출(WMS, OMS, Store)은 영향을 받지 않는다.

## 결과 (Consequences)

- 얻는 것: 콘솔이 프록시 없이 inventory API를 호출할 수 있다. preflight와 오류 응답도 브라우저가 읽을 수 있다.
- 감수하는 것: 환경마다 콘솔 origin을 설정해야 한다. 새 헤더나 메서드를 쓰는 API가 생기면 허용 목록을 함께 고쳐야 한다.
