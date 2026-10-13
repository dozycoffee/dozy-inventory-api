# 운영 런북 (초안)

inventory 서비스를 운영하면서 이상 징후를 만났을 때 원인을 좁히고 복구하는 절차다. 설정과 환경변수는 [configuration.md](configuration.md), 이벤트 계약은 [events.md](events.md), 설계 배경은 [ADR](adr/README.md)에 있다.

> **초안이다.** 코드와 설계를 기준으로 쓴 절차이며 실제 배포 환경(로그 수집 도구, 메트릭, 알림, 접속 방법)이 정해지면 그 도구의 이름과 명령으로 다듬는다. 메트릭이 아직 없어(관측성 작업 보류) 로그와 읽기 전용 SQL, 헬스 엔드포인트로 판단한다. 🔶 표시는 아직 확인하지 못한 가정이다.

## 원칙

1. **수량을 SQL로 직접 고치지 않는다.** 수량은 정해진 경로(입고·출고·반품·폐기 확정, 실사 조정, 대사 보정)로만 바뀐다(원칙 6). 어긋난 수량은 [대사 보정안 승인](#6-대사-보정안이-쌓이거나-대사가-실패했다)이나 WMS의 실사 조정으로 바로잡는다.
2. **조회는 읽기 전용으로 한다.** 아래 SQL은 모두 `SELECT`다. 운영 DB에서 `INSERT`/`UPDATE`/`DELETE`는 이 문서가 "마지막 수단"으로 표시한 것만, 승인과 기록을 남기고 한다.
3. **실패한 요청은 재시도해도 안전하다.** 수량을 바꾸는 요청은 멱등 키가 있다. 호출한 서비스가 같은 키로 다시 보내면 중복 반영되지 않는다.
4. **외부 의존성(Kafka, WMS, `dozy-auth`)이 죽어도 서비스는 가능한 범위에서 계속 동작한다.** 무엇이 영향을 받는지는 아래 표를 본다.
5. 로그는 요청의 `traceId`로 따라간다. 오류 응답 본문에 `traceId`가 있다.

## 증상으로 찾기

| 증상 | 먼저 볼 절 |
|------|------------|
| 서비스가 안 뜬다, 재시작을 반복한다 | [1](#1-서비스가-뜨지-않거나-재시작을-반복한다) |
| readiness가 503이다, 요청이 모두 5xx다 | [2](#2-db에-접속하지-못한다) |
| OMS/WMS가 "재고 이벤트가 안 온다"고 한다 | [3](#3-outbox-이벤트가-밀린다-kafka-장애) |
| 창고 관리자가 모든 요청에서 403을 받는다 | [4](#4-창고-관리자가-403을-받는다-소속-사본-문제) |
| `dozy.wms.warehouse-access.dlt`에 메시지가 쌓인다 | [4](#4-창고-관리자가-403을-받는다-소속-사본-문제) |
| 대사 보정안이 쌓인다, 대사가 `FAILED`다, 승인이 409/502다 | [5](#5-wms-연동이-실패한다), [6](#6-대사-보정안이-쌓이거나-대사가-실패했다) |
| 예약이 만료되지 않는다, 예약 요청이 409다 | [7](#7-예약이-이상하다) |
| 특정 재고 행이 할당되지 않는다(보류) | [8](#8-할당-보류-행) |
| 유통기한이 지난 Lot이 정상 상태로 남아 있다 | [9](#9-유통기한-스캔이-동작하지-않는다) |
| 모든 요청이 401이다 | [10](#10-모든-요청이-401이다) |
| "WMS와 수량이 다르다"는 문의 | [11](#11-수량이-다르다는-문의) |
| 배포 후 문제가 생겼다 | [12](#12-배포와-롤백) |

## 일상 점검

매일 한 번(예: 오전) 확인한다. 메트릭과 알림이 갖춰지면 대시보드와 알림으로 바꾼다.

1. **헬스**: 모든 인스턴스의 `/actuator/health/readiness`가 `UP`이다.
2. **Outbox 적체**: 아래 쿼리의 대기 건수가 0이거나 작고 가장 오래된 대기 시각이 몇 초 전이다.
3. **대사**: 어젯밤 대사가 창고마다 `COMPLETED`이고, 대기 중인 보정안이 처리되고 있다.
4. **DLT**: `dozy.wms.warehouse-access.dlt`에 새 메시지가 없다.
5. **로그**: `ERROR` 로그가 반복되지 않는다([로그 키워드](#로그-키워드)).

```sql
-- Outbox 대기 건수와 가장 오래된 대기 시각, 실패한 적이 있는 이벤트
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest, MAX(attempt_count) AS max_attempts
  FROM outbox_event WHERE status = 'PENDING';

-- 어젯밤 대사 실행 결과(창고별)
SELECT warehouse_id, status, checked_count, mismatch_count, started_at, finished_at
  FROM reconciliation_run WHERE started_at >= CURRENT_DATE - INTERVAL 1 DAY ORDER BY reconciliation_run_id DESC;

-- 처리 대기 중인 대사 보정안
SELECT warehouse_id, COUNT(*) AS pending, MIN(created_at) AS oldest
  FROM stock_adjustment WHERE adjustment_type = 'RECONCILIATION' AND status = 'PENDING' GROUP BY warehouse_id;
```

> 시각은 `Asia/Seoul` 기준으로 저장된다. DB 서버의 시간대가 다르면 `NOW()` 비교가 어긋나므로 시각 비교는 쿼리에 직접 시각을 쓰거나 `SELECT NOW(6)`으로 DB 시각을 먼저 확인한다.

---

## 1. 서비스가 뜨지 않거나 재시작을 반복한다

로그의 첫 `ERROR`/`APPLICATION FAILED TO START`를 본다.

| 로그 | 원인 | 조치 |
|------|------|------|
| `Could not resolve placeholder 'DB_HOST'` 등 | `prod` 프로필의 필수 환경변수 누락(`DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USERNAME`, `DB_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `AUTH_ISSUER_BASE_URI`) | 환경변수를 채운다. 이름 목록은 [configuration.md](configuration.md) |
| `local 프로필은 인증을 우회하므로 prod 프로필과 함께 사용할 수 없습니다` | `SPRING_PROFILES_ACTIVE`에 `local`이 섞였다 | `prod`만 남긴다 |
| `Unable to obtain connection` / `Communications link failure` (Flyway) | DB에 접속하지 못한다 | [2](#2-db에-접속하지-못한다) |
| `Migration checksum mismatch` (Flyway) | 이미 적용된 마이그레이션 파일이 바뀌었다 | **적용된 파일은 고치지 않는다.** 이미지와 배포된 마이그레이션이 맞는지 확인하고, 이전 이미지로 되돌린다. 새 변경은 새 버전 파일로 추가한다(ADR-0021: 첫 배포 이후 수정 금지) |
| `No resolvable bootstrap urls given in bootstrap.servers` | `KAFKA_BOOTSTRAP_SERVERS`의 호스트 이름이 풀리지 않는다(오타 등) | 주소를 고친다. 이름이 풀리면 브로커가 죽어 있어도 기동은 된다 |
| `Out of memory` 후 컨테이너 종료 | 메모리 부족(`ExitOnOutOfMemoryError`로 즉시 종료해 재시작을 유도한다) | 메모리 한도를 올리거나 부하 원인을 찾는다(JVM은 한도의 75%를 힙으로 쓴다) |
| 마이그레이션 중 실패(첫 배포 직후) | 🔶 빈 스키마에 여러 인스턴스가 동시에 마이그레이션을 시도했을 가능성 | 한 인스턴스만 남기고 재시작한 뒤 늘린다. 실패 내용은 로그의 Flyway 메시지로 확인한다 |

## 2. DB에 접속하지 못한다

- **영향**: readiness가 `DOWN`(503)이 되어 트래픽이 끊긴다. liveness는 `UP`이라 인스턴스는 재시작되지 않는다(의도: DB 장애로 앱을 재시작하면 장애가 커진다). Outbox 발행, 예약 만료, 배치는 실패 로그를 남기고 다음 주기에 다시 한다.
- **확인**: DB 주소·계정·네트워크, MySQL 상태, `max_connections` 초과 여부(인스턴스 수 × 풀 크기 20). `Too many connections`가 보이면 풀 크기(`SPRING_R2DBC_POOL_MAX_SIZE`)나 인스턴스 수를 줄인다.
- **복구 후**: 앱을 재시작할 필요 없이 커넥션 풀이 회복되고 readiness가 `UP`이 된다. 회복되지 않으면 인스턴스를 재시작한다.
- **복구 뒤 점검**: [Outbox 적체](#3-outbox-이벤트가-밀린다-kafka-장애)가 풀리는지, 호출 서비스가 보낸 요청 중 5xx로 실패한 것은 호출 서비스가 같은 멱등 키로 재시도하는지 확인한다.

## 3. Outbox 이벤트가 밀린다 (Kafka 장애)

### 증상 확인
```sql
SELECT COUNT(*) AS pending, MIN(created_at) AS oldest, MAX(attempt_count) AS max_attempts
  FROM outbox_event WHERE status = 'PENDING';

-- 앞쪽 대기 이벤트: attempt_count가 크면 이 이벤트에서 발행이 계속 실패하고 있다
SELECT outbox_event_id, aggregate_type, event_type, partition_key, attempt_count, last_error, created_at
  FROM outbox_event WHERE status = 'PENDING' ORDER BY outbox_event_id LIMIT 10;

-- 격리된(DEAD) 이벤트 수. 0이 아니면 아래 "격리된 이벤트"를 본다
SELECT COUNT(*) FROM outbox_event WHERE status = 'DEAD';
```
로그에 `이벤트 발행에 실패했다: outboxEventId=…`가 1초마다(발행 간격) 반복된다. 이벤트가 격리되면 `OUTBOX_EVENT_DEAD` ERROR 로그가 한 번 남는다.

### 동작 이해
- 변경은 이미 DB에 반영되어 있고(수량·예약은 정상), 구독자(OMS/WMS)에게 알리는 이벤트만 밀린 것이다.
- 발행기는 이벤트를 `outbox_event_id` 순서로 발행한다. 한 이벤트가 실패하면 **같은 파티션 키의 뒤 이벤트만 그 실행에서 건너뛰고** 다른 키는 계속 발행한다(ADR-0037).
- 같은 토픽의 다른 이벤트가 같은 실행에서 성공했는데 이 이벤트만 실패하면 이벤트 고유의 문제로 보고 `attempt_count`를 늘린다. `inventory.outbox.publisher.max-attempts`(기본 20)에 이르면 `DEAD`로 격리한다. 브로커 장애처럼 그 토픽의 이벤트가 하나도 성공하지 못하면 횟수를 세지 않으므로 Kafka가 오래 죽어 있어도 이벤트를 잃지 않는다. 한 실행에서 연속 5건이 실패하면 나머지는 다음 주기로 넘긴다.
- 발행기는 MySQL `GET_LOCK`(`dozy_inventory.outbox_publisher`)으로 한 인스턴스만 돈다.

### 원인별 조치
| 원인 | 판단 | 조치 |
|------|------|------|
| Kafka 브로커/네트워크 장애 | 모든 이벤트가 실패, 로그에 연결 오류·타임아웃 | Kafka를 복구한다. 복구되면 자동으로 순서대로 발행하며 밀린 건수가 줄어든다. 별도 조치 없음 |
| 토픽이 없고 자동 생성이 꺼져 있다 | 특정 집계(`dozy.inventory.inventory`/`reservation`/`lot`/`product`)의 이벤트만 막힘 | 토픽을 만든다([토픽 목록](events.md)) |
| 메시지가 브로커 한도를 넘는다 | 특정 이벤트만 반복 실패(`RecordTooLargeException` 등) | 브로커 `message.max.bytes`를 확인한다. 이벤트 크기는 보통 작아 드물다 |
| 인증/ACL 오류 | 모든 이벤트가 권한 오류 | Kafka ACL/자격을 확인한다 |
| 특정 이벤트만 계속 실패(원인 불명) | 같은 `outboxEventId`가 반복해서 실패하고 attempt_count가 계속 증가 | 로그의 예외와 `last_error`를 확인한다. 임계치에 이르면 자동으로 격리된다. 아래 "격리된 이벤트" |
| 한 토픽의 모든 이벤트가 영구 실패(예: 직렬화 오류) | 같은 집계의 이벤트가 모두 PENDING으로 쌓이는데 `attempt_count`가 늘지 않음 | 성공하는 이벤트가 없어 격리되지 않는다(알림 대상). 로그의 예외로 원인을 고친다 |

### 격리된 이벤트
`DEAD` 이벤트는 발행기가 가져가지 않고 정리 배치도 지우지 않는다. 격리되는 동안 같은 키의 뒤 이벤트는 계속 발행되므로 **구독자가 이 이벤트를 놓친 채 뒤 이벤트를 먼저 받을 수 있다.**
1. **목록과 오류를 본다.** 운영자(`inventory:admin`) 토큰으로 호출한다.
```
GET  /api/v1/outbox-events?page=0&size=20       격리된 이벤트 목록(최신순)
GET  /api/v1/outbox-events/{outboxEventId}      payload, attemptCount, lastError, deadAt
```
2. **원인을 고친다.** `lastError`가 `RecordTooLargeException`이면 브로커 한도, 토픽/ACL 오류이면 Kafka 설정을 고친다. 원인을 모르면 고치기 전에 재발행하지 않는다(다시 20번 실패하고 격리된다).
3. **구독자 영향을 판단한다.** 같은 키의 뒤 이벤트가 이미 나갔다면 구독자가 순서가 뒤집힌 채 받는다. 필요하면 해당 서비스에 알리거나 조회 API·대사로 맞춘다.
4. **재발행한다.** 발행 대기로 되돌리면 발행기가 다음 주기에 다시 가져가고 시도 횟수는 0부터 센다. 격리 상태가 아니면 409(`INV_INVALID_OUTBOX_STATE`), 없으면 404이다. 구독자는 `event-id`로 중복을 거른다.
```
POST /api/v1/outbox-events/{outboxEventId}/requeue
```
5. 재발행할 수 없는 이벤트(구독자에게 더는 의미가 없는 이벤트 등)는 격리 상태로 둔다. 운영 DB를 직접 고치지 않는다. 격리 이벤트의 보관·삭제 정책은 🔶 미확정이다.

### 발행 후 확인
`pending`이 0에 수렴하는지, 구독자가 이벤트를 받는지 확인한다. 구독자는 값의 `eventId`로 중복을 거르므로 복구 직후 같은 이벤트가 다시 와도 정상이다(at-least-once).

## 4. 창고 관리자가 403을 받는다 (소속 사본 문제)

창고 관리자의 접근 범위는 WMS가 보낸 소속 이벤트로 만든 사본(`warehouse_access`)으로 판단한다(ADR-0032).

### 확인
```sql
-- 이 직원의 소속 사본 (principal_id는 dozy-auth의 principalId, UUID 소문자)
SELECT principal_id, warehouse_id, updated_at FROM warehouse_access WHERE principal_id = :principalId;
```
로그에서 `WAREHOUSE_ACCESS_COPY_MISSING … principalId=…`를 찾는다. 이 로그는 사본이 하나도 없는 창고 관리자가 요청할 때마다 `ERROR`로 남는다.

### 원인별 조치
| 상황 | 조치 |
|------|------|
| 사본이 없다(처음 연동 직후, 새 직원) | WMS가 그 직원의 소속 이벤트를 발행한다. 연동 초기에는 WMS가 **전 직원에게 한 번씩** 발행해야 한다 |
| 사본은 있는데 요청한 창고가 없다 | 소속이 맞는지 WMS에서 확인한다. 맞으면 WMS가 최신 소속 이벤트를 다시 발행한다(교체 의미라 안전하다) |
| 사본이 오래됐다(소속을 바꿨는데 반영 안 됨) | `dozy.wms.warehouse-access` 토픽과 컨슈머 상태, DLT를 확인한다 |

### DLT에 메시지가 쌓인다 (`dozy.wms.warehouse-access.dlt`)
수신에 3번 실패했거나 형식이 잘못된 메시지가 간 곳이다. DLT 메시지의 헤더에 예외 정보가 있다(`kafka_dlt-exception-message` 등).
1. **형식 오류**(JSON이 아님, `eventType`이 다름, `principalId`가 UUID가 아님, `warehouseIds`에 중복·0 이하·1000개 초과): WMS가 계약([events.md](events.md))대로 고쳐 다시 보내야 한다. DLT 메시지를 그대로 되돌려 보내도 같은 이유로 또 실패한다.
2. **일시 오류**(DB 장애 등으로 3번 실패): 원인이 복구된 뒤 DLT 메시지를 원 토픽에 다시 보내거나, WMS가 같은 이벤트를 다시 발행한다. 교체가 멱등이라 여러 번 처리해도 결과가 같다.
3. 처리가 끝난 DLT 메시지는 보관 기간이 지나면 브로커가 지운다. 필요하면 확인 기록을 남긴다.

### 컨슈머가 멈춰 있다
컨슈머 그룹 `dozy-inventory-api`의 lag이 계속 늘면 리스너가 동작하지 않는 것이다. 앱 로그에서 리스너 컨테이너 오류를 확인하고 인스턴스를 재시작한다. 브로커 주소를 잘못 쓰면 기동 단계에서 실패한다(1번).

## 5. WMS 연동이 실패한다

WMS 창고 수량 조회 API(`GET /api/v1/stock-quantities`)를 호출하는 곳은 둘이다: 매일 새벽 대사 배치, 대사 보정안 승인.

| 증상 | 의미 | 조치 |
|------|------|------|
| 대사 실행이 `FAILED`(해당 창고만) | WMS 호출 실패. 다른 창고는 영향 없다 | 아래 확인 후 WMS가 복구되면 다음 날 새벽 배치에서 다시 한다 |
| 승인 응답 502 `INV_WMS_UNAVAILABLE` | 승인 시점 재확인에서 WMS 조회 실패. 아무것도 바뀌지 않았다 | WMS 복구 후 다시 승인한다 |

**확인할 것**
1. `WMS_BASE_URL`이 맞는가(비어 있으면 모든 호출이 실패한다).
2. WMS가 살아 있고 이 경로를 구현했는가(계약: [ADR-0033](adr/0033-reconciliation.md)).
3. system client 자격이 맞는가(`AUTH_CLIENT_ENABLED=true`, `AUTH_CLIENT_ID`/`AUTH_CLIENT_SECRET`). 토큰 발급 실패는 로그의 `DozySystemTokenException`으로 보인다.
4. WMS 응답이 계약에 맞는가(`items[].productId/lotNumber/qualityStatus/quantity`). 계약을 어기면 조회 실패로 처리된다.
5. 시간 제한(기본 10초) 안에 응답하는가. 느리면 `inventory.reconciliation.wms.timeout`을 조정한다.

**수동 재실행**: 대사를 즉시 다시 돌리는 API는 없다. 급하면 인스턴스에 `INVENTORY_RECONCILIATION_CRON`을 가까운 시각으로 바꿔 재시작하는 방법이 있지만(사용 후 원복), 웬만하면 다음 새벽 배치를 기다린다. 🔶

## 6. 대사 보정안이 쌓이거나 대사가 실패했다

### 보정안 처리 흐름
1. 대사가 불일치한 행마다 `PENDING` 보정안을 만든다(수량은 바뀌지 않는다).
2. 담당자가 `GET /api/v1/reconciliations/proposals`로 보고 원인을 확인한 뒤 항목별로 **승인**(`POST …/{id}/approval`) 또는 **거부**(`…/rejection`)한다. 창고 관리자는 배정된 창고만, 변동량이 임계치를 넘는 건은 `admin`만 승인한다.
3. 승인하면 WMS 수량을 다시 확인해 값이 같을 때만 반영한다.

### 승인 응답별 조치
| 응답 | 의미 | 조치 |
|------|------|------|
| 409 `INV_RECONCILIATION_CHANGED` | 보정안을 만든 뒤 수량이 바뀌었다. 보정안은 새 숫자로 고쳐졌고 반영은 안 됐다 | 보정안을 다시 조회해 새 숫자를 확인하고 다시 승인한다. 불일치가 사라졌으면 보정안은 이미 거부 상태로 닫혔다 |
| 409 `INV_INSUFFICIENT_AVAILABLE_QUANTITY` | 감소하려는 수량이 가용 수량(총 − 예약)을 넘는다 | 예약이 그 재고를 잡고 있다. 예약을 해제/재할당하거나 출고가 끝나길 기다린 뒤 다시 승인한다. 보정안은 `PENDING`으로 남아 있다 |
| 403 `INV_RECONCILIATION_ADMIN_REQUIRED` | 임계치를 넘는 보정안을 창고 관리자가 승인했다 | `admin`이 승인한다 |
| 403 `INV_WAREHOUSE_ACCESS_DENIED` | 배정되지 않은 창고 | [4](#4-창고-관리자가-403을-받는다-소속-사본-문제) |
| 409 `INV_INVALID_ADJUSTMENT_STATE` | 이미 거부된 보정안을 승인하려 했다 | 다음 대사가 같은 불일치를 다시 올린다 |
| 502 `INV_WMS_UNAVAILABLE` | WMS 조회 실패 | [5](#5-wms-연동이-실패한다) |

### 대사 상태 점검
```sql
-- 실패했거나 오래 끝나지 않은 실행
SELECT reconciliation_run_id, warehouse_id, status, started_at, finished_at
  FROM reconciliation_run WHERE status <> 'COMPLETED' ORDER BY reconciliation_run_id DESC LIMIT 20;
```
- `FAILED`: [5](#5-wms-연동이-실패한다). 로그 `대사에 실패했다: 창고 …`에 원인이 있다.
- `RUNNING`이 수십 분 넘게 남아 있다: 실행 중 프로세스가 죽은 것이다. 다음 날 배치가 새 실행을 만들고, 멱등 키 덕분에 중복 보정안은 생기지 않는다. 그 행은 그대로 둔다(이력용).
- 불일치 건수가 갑자기 많다: 입고·출고 확정이 WMS에서 inventory로 전달되지 않았을 가능성이 크다. WMS의 재전송 상태와 Outbox/호출 실패를 먼저 본다. 같은 날 보정안을 일괄 거부하지 말고 원인부터 찾는다.
- 보정안이 오래 `PENDING`이다: 담당자 알림이 없으므로 일상 점검에서 건수를 본다.
- 보정안은 **같은 행에 `PENDING`이 있으면 새로 만들지 않고**, 거부한 행은 같은 날 다시 만들지 않는다(다음 날 대사에서 올라온다).

## 7. 예약이 이상하다

### 예약이 만료되지 않는다
`RESERVED` 예약은 1분 간격 스캔이 만료 시각이 지나면 `EXPIRED`로 바꾸고 수량을 되돌린다.
```sql
-- 만료 시각이 5분 넘게 지났는데 아직 RESERVED인 예약 (DB 시각이 서울 기준이라는 가정, 위 안내 참고)
SELECT reservation_id, channel, external_order_id, expires_at FROM reservation
 WHERE status = 'RESERVED' AND expires_at < NOW(6) - INTERVAL 5 MINUTE ORDER BY expires_at LIMIT 50;
```
있으면 스캐너가 멈춘 것이다. 로그에서 `예약 만료 스캔에 실패했다`/`예약 만료 처리에 실패했다: reservationId=…`를 확인한다(DB 장애가 가장 흔하다). 스케줄러를 끄는 설정(`INVENTORY_RESERVATION_EXPIRY_SCAN_ENABLED=false`)이 켜져 있는지도 본다. 확정(`CONFIRMED`)된 예약은 만료되지 않는 것이 정상이다.

### 예약/재할당 요청이 409다
| 코드 | 의미 | 조치 |
|------|------|------|
| `INV_INSUFFICIENT_AVAILABLE_QUANTITY` | 가용 재고가 모자란다(품질 상태가 정상이고 보류가 아닌 행의 총 − 예약) | 정상 동작이다. 재고를 확인한다([재고 현황](scenarios.md): `GET /api/v1/inventories`) |
| `INV_ALLOCATION_CONFLICT` | 같은 재고 행에 동시 요청이 몰려 재시도(5회)에도 못 잡았다 | 호출 서비스가 잠시 뒤 다시 시도한다. 계속되면 특정 상품에 요청이 몰리는지 본다(부하 테스트 대상) |
| `INV_RESERVATION_CHANGED_CONCURRENTLY` | 같은 예약을 동시에 바꾸려 해 재시도(3회)에도 밀렸다 | 호출 서비스가 다시 시도한다 |
| `INV_IDEMPOTENCY_KEY_CONFLICT` | 같은 `Idempotency-Key`로 **다른 내용**을 보냈다 | 호출 서비스의 버그다. 키를 요청 단위로 새로 만들도록 고친다. 이력으로 처음 요청을 확인한다: `SELECT * FROM inventory_history WHERE idempotency_key = :key` |
| `INV_DUPLICATE_ORDER_RESERVATION` | 같은 채널의 같은 주문에 살아 있는 예약이 이미 있다 | 호출 서비스가 기존 예약을 쓰거나 해제한 뒤 다시 예약한다 |

### 예약을 수동으로 해제해야 한다
예약 해제 API(`POST /api/v1/reservations/{id}/release`)는 `inventory:service`만 호출할 수 있다. 사람이 직접 호출할 수단은 없다. 🔶 필요하면 서비스 토큰으로 호출하거나(운영 권한 필요), 주문 쪽(OMS)에서 취소하게 한다. 해제 후에는 예약 수량이 가용으로 돌아간다.

### 예약 상태 조회
예약 조회 API는 없다. 상태는 읽기 전용 SQL로 본다.
```sql
SELECT r.reservation_id, r.status, r.channel, r.external_order_id, r.expires_at,
       i.product_id, a.inventory_id, a.quantity, a.fulfilled_quantity
  FROM reservation r
  JOIN reservation_item i ON i.reservation_id = r.reservation_id
  JOIN reservation_allocation a ON a.reservation_item_id = i.reservation_item_id
 WHERE r.reservation_id = :id;

-- 예약 이력
SELECT event_type, detail, created_at, created_by FROM reservation_event WHERE reservation_id = :id ORDER BY reservation_event_id;
```

## 8. 할당 보류 행

WMS가 피킹 불가를 보고하면(`POST /api/v1/reservations/{id}/reallocation`) 부족이 보고된 재고 행은 신규 할당에서 제외(보류)된다. 대체 재고가 모자라 재할당이 실패(409)해도 보류는 남는다.

```
GET /api/v1/inventories?allocationHold=true      # inventory:admin / warehouse_manager (보류 사유·시각이 응답에 있다)
```
- **보류가 풀리는 경로**: 그 행에 실사 조정(WMS) 또는 대사 보정 승인이 반영되면 자동으로 풀린다. 보류를 직접 푸는 API는 없다.
- 보류가 오래간다: 실물을 확인하고 WMS에서 실사 조정을 보내거나, 대사 보정안을 승인한다.
- 🔶 그 행의 총 수량이 이미 맞는 경우(변동량이 0이라 실사 조정을 보낼 수 없다)에 보류만 푸는 경로는 현재 없다. 후속 개선 후보다.

## 9. 유통기한 스캔이 동작하지 않는다

매일 00:10(`Asia/Seoul`)에 Lot 상태(`NORMAL` → `EXPIRING_SOON` → `EXPIRED`)를 바꾸고, 경과한 Lot의 정상 재고를 `DISPOSAL_SCHEDULED`로 옮기며 확정 전 예약을 강제 해제한다.
```sql
-- 유통기한이 지났는데 EXPIRED가 아닌 Lot
SELECT lot_id, product_id, lot_number, expiration_date, lot_status FROM lot
 WHERE expiration_date <= CURRENT_DATE AND lot_status <> 'EXPIRED' LIMIT 50;
```
- 있으면 스캔이 실패했거나 아직 돌지 않은 것이다. 로그 `유통기한 스캔에 실패했다`, `유통기한 스캔에서 Lot 처리에 실패했다: lotId=…`를 본다. 한 Lot의 실패가 다른 Lot을 막지 않고, 실패한 Lot은 다음 실행에서 다시 처리된다(멱등).
- 확정(`CONFIRMED`)된 예약이 걸린 Lot은 가용 수량만 전환하고 나머지는 다음 스캔에서 처리한다. 확정 예약에는 `LOT_EXPIRED` 이벤트로 알린다(OMS/WMS가 사람이 확인한다).
- 스캔은 크론 `INVENTORY_LOT_EXPIRATION_SCAN_CRON`(기본 `0 10 0 * * *`)이다.

## 10. 모든 요청이 401이다

1. 토큰이 `aud=inventory`, 올바른 issuer, 만료 전인지 확인한다(응답 `UNAUTHENTICATED`의 `traceId`로 로그를 찾는다).
2. `AUTH_ISSUER_BASE_URI`가 맞는지 확인한다(허용 issuer는 `{base}/realms/{realm}`, JWKS는 `{base}/.well-known/jwks.json`).
3. `dozy-auth`에 `inventory` audience, role(`inventory:service`/`warehouse_manager`/`admin`), system client가 등록되었는지 확인한다(등록은 사용자 작업).
4. `dozy-auth` 서버 장애: 서명 키를 받아 오지 못하면 토큰 검증이 실패할 수 있다. 🔶 스타터의 키 캐시 동작은 확인이 필요하다. 인증 서버 복구를 기다린다.
5. 403은 role 부족이다. 서비스 간 호출은 `inventory:service`, 관리자 조회는 `admin`/`warehouse_manager`가 필요하다.

## 11. 수량이 다르다는 문의

WMS(물리 사실)와 inventory(약속의 기준)가 다르다는 신고가 오면:
1. **확정 응답의 `quantityAfter`와 WMS 수량을 비교**했는지 묻는다. 입고·출고 확정 응답에 반영 후 수량이 있다.
2. 그 재고 행의 이력을 본다: `GET /api/v1/inventories/{inventoryId}/histories`(유형, 변동량, 처리 후 수량, 원인 문서, 요청 주체, 멱등 키).
3. 이력에 없으면 WMS의 확정 요청이 도착하지 않았거나 실패한 것이다. WMS의 재전송/오류 기록을 확인한다. 같은 멱등 키 재전송은 안전하다.
4. 이력이 맞는데 WMS와 다르면 **대사 보정안**(`GET /api/v1/reconciliations/proposals`)을 확인한다. 대사는 하루 1번 돌므로 반영 전까지 다를 수 있다.
5. 급하게 맞춰야 하면 WMS의 실사 조정(승인 임계치 초과 시 승인자 필요)이나 대사 보정안 승인으로 맞춘다. **SQL로 수량을 고치지 않는다.**

## 12. 배포와 롤백

- **종료**: 종료 신호(SIGTERM)를 받으면 readiness가 먼저 `DOWN`이 되어 새 트래픽이 끊기고, 진행 중인 요청을 최대 30초(`spring.lifecycle.timeout-per-shutdown-phase`) 기다린 뒤 종료한다(종료 코드 143은 정상). 플랫폼의 종료 유예 시간은 이보다 길어야 한다.
- **배포 확인**: 새 인스턴스의 readiness `UP` → 로그에 `Successfully applied … migrations`(스키마 변경이 있는 경우) 확인 → 대표 조회 API 호출 → 일상 점검 항목.
- **롤백**: 이전 이미지로 되돌린다. **마이그레이션은 되돌리지 않는다**(앞으로만 간다). 그래서 스키마 변경은 이전 버전 앱도 동작하는 하위 호환 형태로 먼저 배포해야 한다(컬럼 추가는 안전, 삭제·이름 변경은 두 단계로). 롤백 직후 Outbox 발행과 배치가 정상인지 확인한다.
- **첫 배포**: 한 인스턴스로 시작해 마이그레이션이 끝난 뒤 늘린다. 첫 배포 시점부터 V1~V3 마이그레이션 파일은 수정하지 않는다(ADR-0021).
- 이미지 정보: [ADR-0035](adr/0035-container-image.md). 로컬에서 같은 이미지를 확인하려면 `scripts/image-smoke-test.sh`.

---

## 부록

### 로그 키워드

| 키워드 / 메시지 | 의미 | 심각도(제안) |
|-----------------|------|--------------|
| `WAREHOUSE_ACCESS_COPY_MISSING` | 소속 사본이 없는 창고 관리자의 요청 거부 | 높음(동기화 문제 가능, [4](#4-창고-관리자가-403을-받는다-소속-사본-문제)) |
| `이벤트 발행에 실패했다: outboxEventId=…` | Outbox 발행 실패 | 높음, 반복되면 즉시([3](#3-outbox-이벤트가-밀린다-kafka-장애)) |
| `OUTBOX_EVENT_DEAD` | 이벤트가 발행에 계속 실패해 격리됨 | 높음, 즉시([3](#3-outbox-이벤트가-밀린다-kafka-장애) "격리된 이벤트") |
| `Outbox 발행에 실패했다` / `Outbox 이벤트 정리에 실패했다` | 발행·정리 배치 예외 | 중간 |
| `대사에 실패했다: 창고 …` / `대사 배치에 실패했다` | 대사 실패 | 중간([5](#5-wms-연동이-실패한다)) |
| `WMS에만 있고 inventory에 등록되지 않은 Lot이라 보정안을 만들지 못했다` | WMS에 있는 Lot이 inventory에 없다(입고 확정이 먼저 와야 함) | 낮음~중간, 반복되면 입고 연동 점검 |
| `예약 만료 스캔에 실패했다` / `예약 만료 처리에 실패했다: reservationId=…` | 만료 처리 실패 | 중간([7](#7-예약이-이상하다)) |
| `유통기한 스캔에 실패했다` / `…Lot 처리에 실패했다: lotId=…` / `…강제 해제에 실패했다: reservationId=…` | 유통기한 스캔 실패 | 중간([9](#9-유통기한-스캔이-동작하지-않는다)) |
| `Unhandled exception traceId=…` | 예상하지 못한 오류(500) | 높음 |
| `Business exception: code=…, traceId=…` | 업무 오류(4xx, `code`로 구분) | 정보 |

### 주요 오류 코드

| 코드 | HTTP | 의미 |
|------|------|------|
| `VALIDATION_FAILED` | 400 | 요청 형식 오류(헤더 누락 포함) |
| `INV_INSUFFICIENT_AVAILABLE_QUANTITY` | 409 | 가용 재고 부족 |
| `INV_ALLOCATION_CONFLICT` | 409 | 할당 경합(재시도 소진) |
| `INV_IDEMPOTENCY_KEY_CONFLICT` | 409 | 같은 키에 다른 내용 |
| `INV_INVALID_RESERVATION_STATE` / `INV_RESERVATION_EXPIRED` | 409 | 불가능한 예약 전이 / 만료된 예약 |
| `INV_RECONCILIATION_CHANGED` | 409 | 승인 시점에 수량이 바뀌어 보정안을 고쳤다 |
| `INV_WAREHOUSE_ACCESS_DENIED` | 403 | 배정되지 않은 창고 또는 소속 사본 없음 |
| `INV_RECONCILIATION_ADMIN_REQUIRED` | 403 | 임계치 초과 보정안은 `admin`만 |
| `INV_WMS_UNAVAILABLE` | 502 | WMS 조회 실패 |
| `UNAUTHENTICATED` | 401 | 토큰 없음/무효 |

전체 목록은 각 도메인의 `*ErrorCode`와 API 문서(CI 아티팩트 `openapi-spec`)에 있다.

### 유용한 읽기 전용 SQL
```sql
-- 재고 행의 최근 이력
SELECT history_type, quantity_change, quantity_after, reference_type, reference_id, idempotency_key, requester_service, created_at
  FROM inventory_history WHERE inventory_id = :inventoryId ORDER BY inventory_history_id DESC LIMIT 20;

-- 실사 조정/대사 보정 내역 (창고별 최근)
SELECT stock_adjustment_id, adjustment_type, status, approved_by, approved_at, created_at
  FROM stock_adjustment WHERE warehouse_id = :warehouseId ORDER BY stock_adjustment_id DESC LIMIT 20;

-- 상태별 이벤트 수(PUBLISHED는 7일 보관 후 정리되고 DEAD는 정리되지 않는다)
SELECT status, COUNT(*) FROM outbox_event GROUP BY status;

-- 발행 락을 쥐고 있는 연결 (NULL이면 아무도 쥐고 있지 않다)
SELECT IS_USED_LOCK('dozy_inventory.outbox_publisher');
```

### 연락과 에스컬레이션
🔶 담당 팀, 온콜 체계, 채널은 조직이 정한 뒤 채운다. 연동 상대: WMS 담당(수량 조회 API, 확정 요청, 소속 이벤트), OMS/Store 담당(예약 호출, 이벤트 구독), 인프라 담당(MySQL, Kafka, `dozy-auth`).

## 알려진 한계와 후속 개선

| 한계 | 영향 | 후속 후보 |
|------|------|-----------|
| 한 토픽의 이벤트가 모두 영구 실패하면 격리되지 않는다 | 그 집계의 이벤트 전달이 계속 멈춘다 | 알림 규칙(관측성 작업, 보류) |
| 격리된 이벤트를 지우는 정책이 없다 | `DEAD` 행이 쌓인다 | 보관 정책 결정 후 정리(🔶) |
| 메트릭이 없다 | 적체, 배치 지연을 SQL과 로그로만 본다 | 관측성 작업(보류) |
| 예약·실사 조정을 조회하는 API가 없다 | 상태 확인에 SQL이 필요하다 | 운영용 조회 API |
| 예약 해제는 서비스 토큰만 호출할 수 있다 | 운영자가 직접 해제하기 어렵다 | 관리자 강제 해제 API(감사 기록 포함) |
| 대사를 즉시 실행하거나 실행 기록을 조회하는 API가 없다 | 재실행이 번거롭다 | 수동 실행·조회 API |
| 총 수량이 맞는 보류 행을 풀 경로가 없다 | 오탐 보류가 오래간다 | 관리자 보류 해제 API |
| 장애 알림 체계가 없다 | 사람이 점검해야 알아챈다 | 메트릭 + 알림 규칙 |
