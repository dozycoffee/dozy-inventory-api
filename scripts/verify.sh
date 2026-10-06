#!/usr/bin/env bash
# 로컬과 CI가 공통으로 쓰는 검증 스크립트: 서식 검사(spotless) + 빌드 + 테스트.
# 테스트가 Testcontainers(MySQL)를 쓰므로 Docker가 실행 중이어야 한다.
set -euo pipefail

cd "$(dirname "$0")/.."

if ! docker info > /dev/null 2>&1; then
  echo "Docker가 실행 중이어야 합니다 (Testcontainers가 MySQL 컨테이너를 사용합니다)." >&2
  exit 1
fi

if [ -z "${GPR_TOKEN:-}" ] && ! grep -qs '^gpr.token' ~/.gradle/gradle.properties; then
  echo "dozy-auth 스타터를 받으려면 GPR_USER·GPR_TOKEN(read:packages 토큰) 환경변수가 필요합니다." >&2
  exit 1
fi

./gradlew spotlessCheck build --console=plain
