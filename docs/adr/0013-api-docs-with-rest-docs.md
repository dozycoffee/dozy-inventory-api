# ADR-0013: API 명세는 Spring REST Docs로 만든 OpenAPI 파일로 관리한다

## 상태

Accepted (2026-10-06)

## 배경 (Context)

API 명세를 Notion 같은 정적 문서로 쓰면 코드와 어긋난다. Swagger 어노테이션(springdoc)은 컨트롤러 로직 옆에 문서용 코드가 붙어 지저분하고 테스트를 강제하지 못한다. Spring REST Docs는 테스트로 문서를 만들어 테스트와 문서의 일치를 강제하지만 기본 출력(Asciidoctor HTML)의 UI가 불편하다.
다른 프로젝트에서는 REST Docs로 만든 명세를 OpenAPI로 변환해 Swagger UI로 보는 방식을 썼다.

## 결정 (Decision)

1. **REST Docs + `restdocs-api-spec`으로 컨트롤러 테스트에서 OpenAPI 3 파일(`build/api-spec/openapi3.yaml`)을 만든다.** 컨트롤러에는 문서용 어노테이션을 붙이지 않는다. 문서화하지 않은 필드가 있으면 테스트가 실패한다.
2. **문서 전용 테스트를 따로 두지 않는다.** 컨트롤러 테스트의 주요 성공·실패 케이스에 `.consumeWith(XxxApiDocs.xxx())` 한 줄을 붙이고, 명세 본문(`ApiDoc.operation(...)`)은 도메인별 `XxxApiDocs` 객체에 둬 검증 코드와 분리한다. 새 API는 성공 응답 문서화가 필수다(규칙은 [testing.md](../testing.md)).
3. **`./gradlew build`가 `openapi3`을 실행하고 CI가 `openapi3.yaml`을 `openapi-spec` 아티팩트로 올린다.** 서버나 DB 없이 테스트만으로 명세가 나오고 Swagger UI, Redoc, Postman 등으로 열 수 있다. 정적 사이트 배포는 이번에 하지 않는다(이 레포가 공개라 명세 공개 여부를 정한 뒤 결정한다).
4. 버전은 `restdocs-api-spec` 0.20.1이다. Spring Boot 4.1.1, Gradle 9.5.1, Kotlin 2.3에서 동작을 확인했다.

## 결과 (Consequences)

- 얻는 것: 코드와 어긋나지 않는 명세, 컨트롤러 로직이 깨끗하게 유지됨, 문서화 누락을 테스트가 잡음, 서버 없이 CI에서 명세 생성.
- 감수하는 것:
  - 필드 설명을 테스트에 모두 적어야 해서 장황하다.
  - 필수 여부·길이·enum 같은 제약은 자동으로 문서에 나오지 않아 설명에 적어야 한다. 정수와 실수를 구분하지 못하고 `number`로 나온다.
  - Kotlin에서 `document()`는 기본값 뒤의 vararg 때문에 이름 인자가 필요해 `ApiDoc` 도우미로 감쌌다.
  - `restdocs-api-spec`이 servlet 스택(`spring-boot-starter-web`)을 끌어와 테스트 컨텍스트가 reactive가 아니게 되므로 빌드에서 제외한다. 이 제외를 빼면 전체 컨텍스트 테스트가 실패한다.
  - 라이브러리 릴리스 간격이 길어 Spring Boot를 올릴 때 호환을 확인해야 한다.
- 대안: springdoc-openapi(어노테이션이 로직 옆에 붙고 테스트 강제가 없음), REST Docs의 Asciidoctor HTML(UI가 불편).
