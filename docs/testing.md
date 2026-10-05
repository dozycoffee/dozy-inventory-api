# 테스트

변경하거나 추가한 기능에는 반드시 테스트가 따라야 한다. 완료 전에 `./scripts/verify.sh`가 통과해야 한다.

## 계층별 테스트

테스트는 계층별로 나누고 서로 겹치지 않게 한다.

| 클래스 접미사 | 어노테이션 | Spring 컨텍스트 | 목적 |
|---------------|-----------|-----------------|------|
| `*EntityTest` | 없음 | 없음 | 도메인 로직·Entity 불변식 |
| `*ServiceTest` | `@ExtendWith(MockitoExtension::class)` | 없음 | Mock Repository로 서비스 로직 검증 |
| `*ControllerTest` | `@WebFluxTest` | 슬라이스 | API 계약 (`WebTestClient`) |
| `*PersistenceAdapterTest` | `@DataR2dbcTest` | 슬라이스 | Testcontainers MySQL로 실제 쿼리 검증 |

- 예약 동시성(조건부 UPDATE)과 멱등(유니크 제약)처럼 DB가 보장하는 규칙은 `*PersistenceAdapterTest`나 통합 테스트에서 **실제 MySQL로** 검증한다. Mock으로는 검증할 수 없다.
- 동시 요청 시나리오(같은 재고 행에 여러 예약, 같은 멱등 키 동시 요청)는 코루틴으로 병렬 실행해 한쪽만 성공하는지 확인한다.

## 테스트 DB

Spring 컨텍스트를 로드하는 테스트는 `MySqlTestContainerInitializer`(`src/test/resources/META-INF/spring.factories`로 자동 등록)가 띄운 Testcontainers MySQL 8.0에 연결한다. 개발 DB(`dozy_inventory`)와 완전히 분리되며 Docker가 필요하다.
테스트 JVM당 컨테이너 1개를 공유하므로 각 테스트는 `@AfterEach`에서 자기 데이터를 정리한다.

## 작성 방법

- 테스트 메서드명은 백틱 한글 문장형으로 쓴다.
- Given-When-Then 구조로 읽기 쉽게 쓴다. 테스트 하나는 하나의 책임에 집중한다.
- 정상 흐름뿐 아니라 경계값, 실패, 중복·동시 요청을 포함한다.
- 테스트 픽스처는 도메인별 `fixture/` 패키지에 두고 `XxxTestBuilder`(Entity), `XxxDtoBuilder`(DTO)를 쓴다.
- Controller 테스트는 `@WebFluxTest`와 `WebTestClient`를 쓴다.
