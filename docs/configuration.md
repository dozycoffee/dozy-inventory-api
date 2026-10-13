# 설정과 운영 환경

환경에 따라 달라지는 값은 모두 환경변수로 넣는다. 설정 파일(`application.yaml`)을 고치지 않고, 비밀은 코드·설정 기본값·이미지에 넣지 않는다(공개 레포).
결정 배경은 [ADR-0036](adr/0036-configuration.md)이고, 컨테이너 이미지는 [ADR-0035](adr/0035-container-image.md), 헬스 체크는 [ADR-0034](adr/0034-health-checks.md)에 있다.

## 프로필

| 프로필 | 용도 | 동작 |
|--------|------|------|
| `local` | 로컬 개발 | 토큰 없이 모든 role을 가진 개발 사용자로 동작한다. `application.yaml`의 `localhost` 기본값을 쓴다. **운영에서 켜지 않는다** |
| `prod` | 운영 | `application-prod.yaml`이 연결 정보의 로컬 기본값을 없앤다. 필수 환경변수가 하나라도 없으면 기동 단계에서 실패한다 |
| (없음) | 테스트, 기타 | 로컬 기본값을 쓰고 토큰이 필요하다 |

`local`과 `prod`를 함께 켜면 `LocalProfileGuard`가 기동을 막는다. 운영은 `SPRING_PROFILES_ACTIVE=prod`로 띄운다.

## 환경변수

### 필수 (`prod`)
| 이름 | 설명 |
|------|------|
| `DB_HOST`, `DB_PORT`, `DB_NAME` | MySQL 주소와 스키마(`dozy_inventory`) |
| `DB_USERNAME`, `DB_PASSWORD` | MySQL 계정. **비밀**이라 시크릿 매니저 등으로 주입한다 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka 주소. Outbox 이벤트 발행과 직원 소속 이벤트 수신에 쓴다. 이름이 풀리지 않는 호스트면 컨슈머가 기동 단계에서 실패하고, 이름은 풀리지만 브로커가 죽어 있으면 기동은 되고 재시도한다 |
| `AUTH_ISSUER_BASE_URI` | `dozy-auth` 주소. 토큰 검증용 JWKS를 여기서 받는다 |

### 선택
| 이름 | 기본값 | 설명 |
|------|--------|------|
| `SPRING_PROFILES_ACTIVE` | 없음 | 운영은 `prod` |
| `SERVER_PORT` | `8082` | HTTP 포트 |
| `WMS_BASE_URL` | 비어 있음 | 대사 배치가 호출할 WMS 주소. 비어 있으면 대사 실행이 `FAILED`로 기록된다(ADR-0033) |
| `AUTH_CLIENT_ENABLED` | `false` | WMS를 `dozy-auth` system client 토큰으로 호출하려면 `true` |
| `AUTH_CLIENT_ID`, `AUTH_CLIENT_SECRET` | 비어 있음 | system client 자격. `AUTH_CLIENT_ENABLED=true`일 때 필요하다. **비밀** |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError` | JVM 옵션(이미지 기본값). 덮어쓰면 기본값이 사라진다 |

비밀(`DB_PASSWORD`, `AUTH_CLIENT_SECRET`)은 로그나 이슈·PR에 적지 않는다. 로컬에서는 `.env`(Git 제외)에 둔다.

## 속성 덮어쓰기 규칙

설정 파일의 모든 속성은 환경변수로 덮어쓸 수 있다. 이름은 **점(`.`)과 하이픈(`-`)을 밑줄(`_`)로 바꾸고 대문자로** 쓴다.
`inventory.outbox.publisher.batch-size` → `INVENTORY_OUTBOX_PUBLISHER_BATCH_SIZE`. 이 규칙은 테스트로 확인한다(`EnvironmentVariableBindingTest`).

맵 항목(카테고리별 임계치, 채널별 TTL)은 **하이픈을 밑줄로 바꾸지 않고 그냥 빼서** 키를 이름 끝에 붙인다. 밑줄을 넣으면 맵 항목으로 인식되지 않아 조용히 무시된다.
환경변수로 넘기면 키가 소문자가 되므로 카테고리·채널 이름은 대소문자를 구분하지 않고 찾는다.
- `INVENTORY_ADJUSTMENT_APPROVALTHRESHOLD_CATEGORIES_BEAN=20`
- `INVENTORY_RESERVATION_MAXTTL_CHANNELS_OMS=2h`

### 조정할 수 있는 속성

| 속성 | 기본값 | 설명 |
|------|--------|------|
| `spring.r2dbc.pool.initial-size`, `max-size` | 5, 20 | DB 커넥션 풀. 부하 테스트 후 정한다 |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | 종료 시 진행 중인 요청을 기다리는 시간. 플랫폼의 종료 유예 시간보다 짧아야 한다 |
| `inventory.adjustment.approval-threshold.default` | `100` | 실사 조정·대사 보정의 승인 임계치. **가정한 값이라 운영 전에 정한다**(ADR-0026) |
| `inventory.adjustment.approval-threshold.categories.{카테고리}` | 없음 | 카테고리별 임계치 |
| `inventory.reservation.max-ttl.default` | `1h` | 예약 만료 시각의 상한(생성 시각 + TTL) |
| `inventory.reservation.max-ttl.channels.{채널}` | 없음 | 채널별 상한. 채널 값이 확정되지 않아 설정으로만 둔다(ADR-0022) |
| `inventory.expiring-soon-days` | `30` | 유통기한이 이 일수 이내이면 Lot을 임박 상태로 본다 |
| `inventory.outbox.publisher.interval`, `batch-size` | `PT1S`, 100 | Outbox 발행 간격과 한 번에 발행하는 건수 |
| `inventory.outbox.publisher.max-attempts` | 20 | 이벤트 고유의 발행 실패가 이 횟수에 이르면 `DEAD`로 격리(ADR-0037). 브로커 장애처럼 공통 문제로 판단한 실패는 세지 않는다 |
| `inventory.outbox.publisher.topic-prefix` | `dozy.inventory` | 발행 토픽 접두사(`{접두사}.{집계}`) |
| `inventory.outbox.cleanup.retention` | `P7D` | 발행이 끝난 이벤트를 지우기 전 보관 기간 |
| `inventory.warehouse-access.listener.topic` | `dozy.wms.warehouse-access` | 직원 창고 소속 이벤트를 받을 토픽 |
| `inventory.reconciliation.wms.timeout` | `PT10S` | WMS 수량 조회 시간 제한 |

## 스케줄러와 여러 인스턴스

인스턴스를 여러 개 띄워도 같은 일이 중복 반영되지 않도록 모든 배치가 조건부 갱신·멱등 키·락으로 설계되어 있다. 각 배치는 `enabled` 속성으로 끌 수 있다.

| 배치 | 주기 | 여러 인스턴스에서 안전한 이유 | 끄는 속성 |
|------|------|------------------------------|-----------|
| Outbox 발행 | 1초 간격 | MySQL `GET_LOCK`으로 한 인스턴스만 발행한다. 중복 전달은 구독자가 값의 `eventId`로 거른다(at-least-once) | `inventory.outbox.publisher.enabled` |
| Outbox 정리 | 1시간 간격 | 삭제가 멱등이고 데드락은 재시도한다 | `inventory.outbox.cleanup.enabled` |
| 예약 만료 | 1분 간격 | 만료가 `RESERVED` 조건부 UPDATE라 같은 예약은 한 번만 처리된다 | `inventory.reservation.expiry-scan.enabled` |
| 유통기한 스캔 | 매일 00:10 (`Asia/Seoul`) | 상태 전환이 조건부이고 이력 키가 `lot-expiration-{lotId}-{날짜}`다 | `inventory.lot-expiration-scan.enabled` |
| 대사 | 매일 01:30 (`Asia/Seoul`) | 보정안 멱등 키가 `reconciliation-{창고}-{날짜}-{lotId}-{품질}`이고 처리 대기 보정안은 중복 생성하지 않는다 | `inventory.reconciliation.enabled` |
| 직원 소속 수신 | 이벤트 | 같은 컨슈머 그룹(`dozy-inventory-api`)이 파티션을 나눠 받고, 교체가 멱등이다 | `inventory.warehouse-access.listener.enabled` |

스케일 아웃 때 주의할 점:
- Kafka 리스너의 동시 처리 한도는 토픽 파티션 수다. 인스턴스를 늘려도 파티션보다 많은 인스턴스는 놀게 된다.
- DB 커넥션은 `인스턴스 수 × max-size`만큼 쓴다. MySQL `max_connections`보다 작게 유지한다.
- 스키마 마이그레이션(Flyway)은 앱이 기동할 때 적용한다. 빈 스키마에 처음 배포할 때는 한 인스턴스로 시작해 마이그레이션이 끝난 뒤 늘린다.

## 시간대

- 업무 시각은 `Clock`(`Asia/Seoul`)으로만 만들고, DB 연결 URL의 `serverTimezone=Asia/Seoul`로 해석한다. 컨테이너와 DB 서버의 시간대가 달라도 저장·조회 값은 같다.
- 배치의 크론은 `*.zone`(기본 `Asia/Seoul`)을 쓴다.
- 이벤트와 로그의 시각은 오프셋을 포함한 ISO-8601이다.

## 아직 정하지 않은 것
- DB 연결의 TLS 사용 여부(현재 URL은 `useSSL=false`). 인프라 정책이 정해지면 정한다.
- CORS: 어드민 콘솔이 브라우저에서 직접 호출하게 되면 추가한다(ADR-0011).
- 메트릭, 구조화 로그, 알림 기준: 서비스 간 규약과 부하 테스트 시점에 정한다.
