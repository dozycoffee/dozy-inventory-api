# 테스트

변경하거나 추가한 기능에는 반드시 테스트가 따라야 한다. 완료 전에 `./scripts/verify.sh`가 통과해야 한다.

## 계층별 테스트

테스트는 계층별로 나누고 서로 겹치지 않게 한다.

| 클래스 접미사 | 어노테이션 | Spring 컨텍스트 | 목적 |
|---------------|-----------|-----------------|------|
| `*ModelTest` | 없음 | 없음 | 도메인 모델의 로직·불변식 |
| `*ServiceTest` | `@ExtendWith(MockitoExtension::class)` | 없음 | Mock Repository로 서비스 로직 검증 |
| `*ControllerTest` | `@WebFluxTest` | 슬라이스 | API 계약 (`WebTestClient`) |
| `*PersistenceAdapterTest` | `@DataR2dbcTest` | 슬라이스 | Testcontainers MySQL로 실제 쿼리와 `XxxEntity` ↔ 도메인 모델 변환 검증 |

- 예약 동시성(조건부 UPDATE)과 멱등(유니크 제약)처럼 DB가 보장하는 규칙은 `*PersistenceAdapterTest`나 통합 테스트에서 **실제 MySQL로** 검증한다. Mock으로는 검증할 수 없다.
- 동시 요청 시나리오(같은 재고 행에 여러 예약, 같은 멱등 키 동시 요청)는 코루틴으로 병렬 실행해 한쪽만 성공하는지 확인한다.
- 스키마 제약(수량 CHECK, 유니크, 복합 FK 등)은 `SchemaConstraintTest`가 실제 MySQL에서 위반이 거부되는지 검증한다. 마이그레이션에 제약을 추가·변경하면 이 테스트도 함께 고친다.

- 전체 컨텍스트를 올리는 테스트는 `@ImsIntegrationTest`를 쓴다. 인증을 거치지 않도록 이 어노테이션이 `local` 프로필(고정 개발 사용자)을 켠다. 보안 체인(401·403·role)은 `@WebFluxTest`와 `auth-test`의 `DozyTestTokens`로 별도 검증한다.
- 오류 응답 형식은 `@WebFluxTest`와 테스트 전용 Controller로 검증한다. 감사 컬럼은 테스트 전용 Entity로 실제 MySQL에서 검증하고, 시간은 `MutableClock`으로 조작한다.

## 테스트 DB

Spring 컨텍스트를 로드하는 테스트는 `MySqlTestContainerInitializer`(`src/test/resources/META-INF/spring.factories`로 자동 등록)가 띄운 Testcontainers MySQL 8.0에 연결한다. 개발 DB(`dozy_inventory`)와 완전히 분리되며 Docker가 필요하다.
테스트 JVM당 컨테이너 1개를 공유하므로 각 테스트는 `@AfterEach`에서 자기 데이터를 정리한다.

## 작성 방법

- 테스트 메서드명은 백틱 한글 문장형으로 쓴다.
- Given-When-Then 구조로 읽기 쉽게 쓴다. 테스트 하나는 하나의 책임에 집중한다.
- 정상 흐름뿐 아니라 경계값, 실패, 중복·동시 요청을 포함한다.
- 테스트 픽스처는 도메인별 `fixture/` 패키지에 두고 `XxxTestBuilder`(도메인 모델), `XxxDtoBuilder`(DTO)를 쓴다.
- Controller 테스트는 `@WebFluxTest`와 `WebTestClient`를 쓴다.

## API 문서

API 명세는 컨트롤러 테스트가 만든다. 컨트롤러에 문서용 어노테이션을 붙이지 않는다. 결정 배경은 [ADR-0013](adr/0013-api-docs-with-rest-docs.md)이다.

- 새 API(엔드포인트)를 만들면 그 컨트롤러 테스트에서 **성공 응답을 반드시 문서화**한다. 별도의 문서 전용 테스트를 만들지 않고, 같은 `*ControllerTest`의 주요 성공·실패 케이스에 `.consumeWith(XxxApiDocs.register())`처럼 한 줄을 붙인다.
- 필드 설명 같은 명세 본문은 컨트롤러 테스트에 쓰지 않고 도메인별 `XxxApiDocs` 객체(`ProductApiDocs`)에 `ApiDoc.operation(...)`으로 정의한다. 테스트는 검증, `XxxApiDocs`는 명세로 나뉜다.
- 호출자가 구분해서 처리해야 하는 오류 응답(400 검증 실패, 404, 409 등)도 대표 케이스를 문서화한다. 같은 경로와 메서드의 문서는 하나의 operation으로 합쳐진다. 모든 오류 케이스를 문서화할 필요는 없다.
- 컨트롤러 테스트 클래스에 `@ExtendWith(RestDocumentationExtension::class)`를 붙이고 `@BeforeEach`에서 `documentationConfiguration(restDocumentation)`을 필터로 가진 `WebTestClient`를 만든다(`ProductControllerTest` 참고).
- `ApiDoc` 도우미(`support/ApiDoc.kt`)를 쓴다. 토큰 값은 문서에 남지 않고 `Bearer {access-token}`으로 치환된다. 인증이 필요한 요청은 `ApiDoc.authorization`을, 오류 응답은 `ApiDoc.problem()`(검증 실패는 `withErrors = true`)를 붙인다.
- 요청·응답 필드를 `requestFields`, `responseFields`로 빠짐없이 적는다. 적지 않은 필드가 있으면 테스트가 실패한다. 경로 변수는 `uri("/api/v1/products/{productId}", id)`처럼 템플릿으로 쓰고 `pathParameters`로, 쿼리 파라미터는 `queryParameters`로 적는다.
- 제약(필수 여부, 길이, enum 값, 허용 role)은 자동으로 문서에 나오지 않으므로 필드 설명과 operation 설명에 적는다.
- `requestSchema`, `responseSchema`에 `RegisterProductRequest`, `ProductResponse`처럼 DTO 클래스 이름을 넣어 스키마 이름을 정한다. 오류 응답은 `Problem`이다.
- 생성: `./gradlew openapi3`(`test`를 먼저 실행)는 `build/api-spec/openapi3.yaml`을 만든다. `./gradlew build`와 `./scripts/verify.sh`도 만든다. CI는 이 파일을 `openapi-spec` 아티팩트로 올린다.
