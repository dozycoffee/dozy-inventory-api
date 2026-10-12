# syntax=docker/dockerfile:1

# ---- 빌드 ----
# dozy-auth 스타터를 GitHub Packages에서 받아야 해서 빌드 때 read:packages 자격이 필요하다.
# 자격은 BuildKit secret으로만 넘기고(이미지 레이어와 이력에 남지 않는다) 환경변수로 풀어 Gradle에 전달한다:
#   docker build --secret id=gpr_user,env=GPR_USER --secret id=gpr_token,env=GPR_TOKEN -t dozy-inventory-api .
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
COPY src src

# 테스트는 CI(verify)에서 이미 돌린다. 이미지 빌드는 실행 가능한 jar만 만든다
RUN --mount=type=secret,id=gpr_user \
    --mount=type=secret,id=gpr_token \
    --mount=type=cache,target=/root/.gradle \
    GPR_USER="$(cat /run/secrets/gpr_user)" GPR_TOKEN="$(cat /run/secrets/gpr_token)" \
    ./gradlew bootJar --no-daemon --console=plain

# 의존성 레이어와 애플리케이션 레이어를 분리해 코드만 바뀌면 의존성 레이어를 재사용한다
RUN java -Djarmode=tools -jar build/libs/app.jar extract --layers --launcher --destination /workspace/layers

# ---- 실행 ----
FROM eclipse-temurin:21-jre AS runtime

# 비루트로 실행한다
RUN groupadd --system --gid 10001 app && useradd --system --uid 10001 --gid app --no-create-home app
WORKDIR /app

COPY --from=build --chown=app:app /workspace/layers/dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/spring-boot-loader/ ./
COPY --from=build --chown=app:app /workspace/layers/snapshot-dependencies/ ./
COPY --from=build --chown=app:app /workspace/layers/application/ ./

USER app
EXPOSE 8082

# 컨테이너 메모리 한도의 일부를 힙으로 쓴다. 환경변수 JAVA_TOOL_OPTIONS로 덮어쓸 수 있다
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

# exec 형식이라 종료 신호(SIGTERM)가 JVM에 직접 전달되어 graceful shutdown이 동작한다
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
