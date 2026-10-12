# ADR-0035: 컨테이너 이미지

## 상태

Accepted (2026-10-12). 배포 플랫폼이 아직 정해지지 않아 이미지 자체와 로컬 검증까지만 정한다. 레지스트리 푸시와 배포 워크플로는 플랫폼이 정해진 뒤에 만든다.

## 배경 (Context)

서비스를 띄우려면 실행 가능한 이미지가 필요하다. 이미지는 Kubernetes, ECS, 단일 VM(`docker run`) 어디서나 같게 쓰이므로 플랫폼과 무관하게 정할 수 있다. 빌드 때 `dozy-auth` 스타터를 GitHub Packages에서 받아야 해서 `read:packages` 자격이 필요한데, 이 비밀이 이미지에 남으면 안 된다(공개 레포, 비밀 하드코딩 금지).

## 결정 (Decision)

1. **멀티 스테이지 빌드이다.** 빌드 스테이지(`eclipse-temurin:21-jdk`)에서 `bootJar`를 만들고 실행 스테이지(`eclipse-temurin:21-jre`)에는 결과만 복사한다. 테스트는 이미지 빌드에서 돌리지 않는다(CI의 `verify`가 한다).
2. **빌드 자격은 BuildKit secret으로만 넘긴다**(`--secret id=gpr_user,env=GPR_USER --secret id=gpr_token,env=GPR_TOKEN`). `RUN` 한 줄 안에서만 환경변수로 풀어 Gradle에 전달하므로 이미지 레이어와 이력에 남지 않는다(이미지 이력과 저장된 레이어에서 토큰이 없음을 확인했다). `ARG`나 `ENV`로 받지 않는다.
3. **Spring Boot 레이어드 jar를 쓴다**(`jarmode=tools extract --layers --launcher`). 의존성 레이어가 코드 변경과 분리되어 이미지 갱신 때 전송량이 줄고 빌드 캐시가 잘 맞는다. Gradle 캐시는 BuildKit 캐시 마운트로 재사용한다. 산출물 이름은 `app.jar`로 고정한다(`tasks.bootJar`).
4. **비루트(uid 10001)로 실행한다.** 시작은 `JarLauncher`를 `exec` 형식 `ENTRYPOINT`로 호출해 종료 신호(SIGTERM)가 JVM에 직접 전달된다. graceful shutdown(ADR-0034)이 동작하며 SIGTERM으로 끝나면 종료 코드는 143이다.
5. **JVM 메모리는 컨테이너 한도의 비율로 정한다**(`-XX:MaxRAMPercentage=75.0`, `-XX:+ExitOnOutOfMemoryError`로 OOM이면 종료해 플랫폼이 재시작하게 한다). `JAVA_TOOL_OPTIONS`로 덮어쓸 수 있다.
6. **이미지에는 설정과 비밀을 넣지 않는다.** DB, Kafka, 인증 서버 주소와 비밀은 모두 환경변수로 주입한다(`.dockerignore`가 `.env`를 컨텍스트에서 제외한다).
7. **이미지에 헬스 체크를 넣지 않는다.** 플랫폼마다 probe 방식이 다르다. 로컬 확인용 `docker-compose.yml`의 `app` 서비스(프로필 `app`, 평소 개발에는 쓰지 않는다)가 이미지에 `curl`이 없어 bash의 `/dev/tcp`로 `readiness`를 확인한다. 컨테이너에서 Kafka에 접근하도록 로컬 Kafka에 compose 네트워크용 리스너(`INTERNAL`, `kafka:29092`)를 추가했다(호스트용 `localhost:9092`는 그대로다).

## 결과 (Consequences)

- 얻는 것: 어느 배포 환경에서나 같은 이미지를 쓰고, 비밀이 이미지에 남지 않으며, 종료 시 요청이 끊기지 않는다. 로컬에서 이미지를 실제로 띄워 헬스, 마이그레이션, Outbox 발행(컨테이너 → Kafka)을 확인할 수 있다.
- 감수하는 것: 이미지 빌드에 BuildKit이 필요하다. 첫 빌드는 Gradle 의존성을 모두 받아 오래 걸린다(캐시 마운트로 이후는 빨라진다). 이미지에 `curl` 같은 도구가 없어 컨테이너 안 진단은 제한적이다. 이미지 푸시와 배포 파이프라인은 플랫폼이 정해진 뒤에 만든다.
