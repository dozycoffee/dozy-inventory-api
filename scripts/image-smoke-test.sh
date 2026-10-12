#!/usr/bin/env bash
# 컨테이너 이미지를 실제로 띄워 운영(prod) 프로필로 기동되고 헬스 체크가 UP이며 비루트로 실행되고
# 종료 신호에 정상 종료(SIGTERM, 종료 코드 143)하는지 확인한다. CI와 로컬이 공통으로 쓴다.
# 사용: scripts/image-smoke-test.sh [이미지 이름]   (기본 dozy-inventory-api:local)
# 임시 MySQL 컨테이너와 네트워크를 만들고 끝나면 모두 지운다. DB 비밀번호는 실행마다 새로 만든다.
set -euo pipefail

IMAGE="${1:-dozy-inventory-api:local}"
SUFFIX="$(openssl rand -hex 4)"
NETWORK="inventory-smoke-${SUFFIX}"
MYSQL="inventory-smoke-mysql-${SUFFIX}"
APP="inventory-smoke-app-${SUFFIX}"
DB_PASSWORD="$(openssl rand -hex 16)"

cleanup() {
  status=$?
  if [ "$status" -ne 0 ]; then
    echo "--- 앱 로그 ---" >&2
    docker logs "$APP" 2>&1 | tail -n 80 >&2 || true
  fi
  docker rm -f "$APP" "$MYSQL" > /dev/null 2>&1 || true
  docker network rm "$NETWORK" > /dev/null 2>&1 || true
  exit "$status"
}
trap cleanup EXIT

docker network create "$NETWORK" > /dev/null

# 운영 프로필에서 필수 환경변수가 없으면 기동 단계에서 바로 실패한다(ADR-0036)
if docker run --rm --name "$APP" -e SPRING_PROFILES_ACTIVE=prod "$IMAGE" > /tmp/inventory-smoke-missing-env.log 2>&1; then
  echo "필수 환경변수 없이 prod 프로필로 기동되었다" >&2
  exit 1
fi
grep -q "DB_HOST" /tmp/inventory-smoke-missing-env.log || { echo "누락된 환경변수 이름이 로그에 없다" >&2; tail -n 20 /tmp/inventory-smoke-missing-env.log >&2; exit 1; }
rm -f /tmp/inventory-smoke-missing-env.log
echo "필수 환경변수 누락 시 기동 실패 확인"
docker run -d --name "$MYSQL" --network "$NETWORK" \
  -e MYSQL_ROOT_PASSWORD="$DB_PASSWORD" -e MYSQL_DATABASE=dozy_inventory -e TZ=Asia/Seoul \
  mysql:8.0 --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci > /dev/null

echo "MySQL 준비를 기다린다"
for _ in $(seq 1 60); do
  if docker exec "$MYSQL" mysqladmin ping -h 127.0.0.1 -uroot -p"$DB_PASSWORD" --silent > /dev/null 2>&1; then
    # 초기화 중 임시 서버가 먼저 응답하므로 실제 스키마 접속까지 확인한다
    if docker exec "$MYSQL" mysql -h 127.0.0.1 -uroot -p"$DB_PASSWORD" -e "USE dozy_inventory" > /dev/null 2>&1; then
      break
    fi
  fi
  sleep 2
done

# Kafka와 인증 서버는 띄우지 않는다. 기동과 헬스(readiness는 DB만 본다)에는 필요하지 않고, 브로커에 닿지 못해도 클라이언트가 재시도한다.
# 다만 Kafka 주소는 이름이 풀리는 형식이어야 한다(풀리지 않는 호스트는 컨슈머가 기동 단계에서 실패한다)
docker run -d --name "$APP" --network "$NETWORK" -p 127.0.0.1::8082 \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e DB_HOST="$MYSQL" -e DB_PORT=3306 -e DB_NAME=dozy_inventory -e DB_USERNAME=root -e DB_PASSWORD="$DB_PASSWORD" \
  -e KAFKA_BOOTSTRAP_SERVERS=localhost:9092 -e AUTH_ISSUER_BASE_URI=http://auth.invalid \
  "$IMAGE" > /dev/null

PORT="$(docker port "$APP" 8082/tcp | head -n 1 | sed 's/.*://')"
echo "앱 readiness를 기다린다 (127.0.0.1:${PORT})"
ready=false
for _ in $(seq 1 60); do
  if curl -fsS "http://127.0.0.1:${PORT}/actuator/health/readiness" 2> /dev/null | grep -q '"status":"UP"'; then
    ready=true
    break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$APP")" != "true" ]; then
    echo "앱 컨테이너가 종료되었다" >&2
    exit 1
  fi
  sleep 2
done
[ "$ready" = true ] || { echo "readiness가 UP이 되지 않았다" >&2; exit 1; }

curl -fsS "http://127.0.0.1:${PORT}/actuator/health/liveness" | grep -q '"status":"UP"'
echo "liveness, readiness UP"

# 헬스 외의 actuator 엔드포인트는 노출되지 않는다(토큰이 없으면 401)
code="$(curl -s -o /dev/null -w '%{http_code}' "http://127.0.0.1:${PORT}/actuator/env")"
[ "$code" = "401" ] || { echo "actuator/env가 ${code}로 응답했다(401이어야 한다)" >&2; exit 1; }

# 비루트로 실행된다
uid="$(docker exec "$APP" id -u)"
[ "$uid" != "0" ] || { echo "루트로 실행되고 있다" >&2; exit 1; }
echo "실행 사용자 uid=${uid}"

# 마이그레이션이 적용되었다
tables="$(docker exec "$MYSQL" mysql -h 127.0.0.1 -uroot -p"$DB_PASSWORD" -N -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='dozy_inventory' AND table_name='inventory'")"
[ "$tables" = "1" ] || { echo "마이그레이션이 적용되지 않았다" >&2; exit 1; }

# 종료 신호를 받으면 정상 종료한다(SIGTERM이면 종료 코드 143)
docker stop -t 40 "$APP" > /dev/null
exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$APP")"
[ "$exit_code" = "143" ] || [ "$exit_code" = "0" ] || { echo "종료 코드가 ${exit_code}이다(143 또는 0이어야 한다)" >&2; exit 1; }
docker logs "$APP" 2>&1 | grep -q "Graceful shutdown complete" || { echo "graceful shutdown 로그가 없다" >&2; exit 1; }
echo "정상 종료 확인(종료 코드 ${exit_code})"
echo "이미지 스모크 테스트 통과"
