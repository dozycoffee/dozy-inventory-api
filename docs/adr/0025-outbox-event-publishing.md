# ADR-0025: Outbox 이벤트 저장과 Kafka 발행 규칙

## 상태

Accepted (2026-10-08). ADR-0002(Kafka + Outbox)의 구체화다. 구현은 계층별 PR로 나눈다: (1) `outbox` 도메인과 Outbox 저장, 각 도메인의 이벤트 발행기를 Outbox 저장 구현으로 교체, (2) Kafka 발행기(스케줄러, 어드바이저리 락, 로컬 compose·테스트 컨테이너), (3) 예약 이벤트의 Lot 정보 보강과 이벤트 계약 문서, (4) 발행 완료 이벤트 정리 배치.

## 배경 (Context)

입고·출고·예약·상품 이벤트가 모두 로그만 남기는 구현이라 실제로 나가는 이벤트가 없다. 다른 서비스(OMS, WMS, Store)가 재고 변동을 알려면 이벤트를 신뢰할 수 있게 발행해야 한다. 수량·이력과 이벤트는 한 트랜잭션으로 저장해야 한다(원칙 4). 토픽 구성, 발행 주체, 이벤트 계약, 미뤄 둔 Lot 정보 보강(ADR-0023 결정 10)을 정해야 했다.

## 결정 (Decision)

1. **이벤트는 수량 변경과 같은 트랜잭션으로 `outbox_event`에 저장한다.** 각 도메인의 이벤트 발행기(`InventoryEventPublisher`, `ReservationChangedEventPublisher`, `ProductEventPublisher`) 구현을 로그에서 Outbox 저장으로 바꾼다. 서비스 코드와 포트는 그대로다. 다른 도메인은 `outbox`의 `RecordOutboxEventUseCase`로만 저장한다(ADR-0010).
2. **토픽은 집계(aggregate)별로 나눈다.** `outbox_event.aggregate_type`마다 하나(`{접두사}.{집계}`, 예: `dozy.inventory.inventory`, `dozy.inventory.reservation`). 같은 예약의 이벤트(생성 → 확정 → 출고) 순서가 한 토픽 안에서 보장되고, 구독자는 관심 있는 집계만 구독한다.
3. **파티션 키는 집계가 정한다.** 재고 이벤트는 `창고ID:상품ID`(ERD), 예약 이벤트는 한 예약이 여러 상품에 걸치므로 `창고ID:예약ID`, 상품 이벤트는 상품 ID다. 같은 키는 `outbox_event_id` 순서로 발행한다. 예약 이벤트는 상품별 가용 수량의 절대값을 담지 않으므로 구독자는 가용 수량을 가용 재고 조회로 다시 확인한다(조회는 약속이 아니다, 원칙 3).
4. **메시지 형식은 JSON이다.** 값(value)은 이벤트별 `payload`이고, 이벤트 ID(`outbox_event_id`), 이벤트 유형, 집계 유형, 발생 시각은 Kafka 헤더로 싣는다. 구독자는 이벤트 ID로 중복 전달을 걸러낸다(최소 한 번 전달). 스키마 레지스트리는 쓰지 않는다.
5. **발행기는 앱 안의 스케줄러이고 DB 어드바이저리 락으로 한 번에 한 인스턴스만 발행한다.** `outbox_event_id` 순서로 대기 이벤트를 가져와 발행하고 `PUBLISHED`로 바꾼다. 인스턴스가 여러 개여도 MySQL `GET_LOCK`으로 순서가 섞이지 않는다. 발행 후 상태를 바꾸기 전에 죽으면 같은 이벤트가 다시 나간다(최소 한 번). 발행에 실패한 이벤트는 시도 횟수를 늘리고 그 뒤 이벤트는 발행하지 않는다(순서 보장을 위해 같은 파티션 키가 건너뛰이면 안 되고, 단순하게 그 묶음의 발행을 멈춘다).
   구현 값: 어드바이저리 락 이름은 `dozy_inventory.outbox_publisher`(MySQL `GET_LOCK`, 대기 0)이고 락·조회·발행·상태 변경을 한 트랜잭션(한 DB 연결)에서 실행한다. 스케줄러는 1초 간격(고정 지연)으로 한 묶음 최대 100건을 발행하고, 묶음이 가득 차면 이어서 최대 10묶음까지 발행한다. 프로듀서는 `acks=all`, 멱등 프로듀서이며 브로커가 응답하지 않으면 5초 안에 실패로 처리해 발행기가 오래 막히지 않는다. 설정은 `inventory.outbox.publisher`(`enabled`, `interval`, `batch-size`, `topic-prefix`)와 `spring.kafka`이고 테스트에서는 발행기를 기본으로 끈다. 같은 파티션 키의 이벤트는 같은 키를 다루는 트랜잭션이 행 잠금으로 직렬화되어 `outbox_event_id`가 커밋 순서와 같아지므로 id 순서 발행이 곧 순서 보장이다.
6. **예약 이벤트는 이벤트를 만드는 시점에 Lot 정보(Lot 번호, 유통기한)를 담는다.** 같은 트랜잭션에서 inventory의 조회 UseCase로 할당 재고 행의 Lot 정보를 읽어 payload에 저장한다. 이벤트는 그 시점의 불변 스냅샷이고 WMS는 추가 조회 없이 출고를 지시할 수 있다.
   구현: inventory에 `GetInventoryLotsUseCase`(재고 행 ID 집합 → Lot ID·번호·유통기한)를 두고, 예약 이벤트를 Outbox에 저장하는 `OutboxReservationChangedEventPublisher`가 호출한 서비스의 트랜잭션 안에서 이를 호출해 payload의 `items[].allocations[]`에 `lotId`, `lotNumber`, `expirationDate`(없으면 null)를 담는다. 생성·확정·연장·해제·만료·출고 확정 이벤트가 모두 같은 한 곳을 지나므로 예약 서비스 코드는 바뀌지 않는다. 할당된 재고 행의 Lot을 찾지 못하면 이벤트를 저장하지 않고 실패해 트랜잭션이 롤백된다(재고 행은 Lot을 외래키로 가져 정상 흐름에서는 일어나지 않는다).
7. **발행이 끝난 이벤트는 정리 배치가 일정 기간 뒤 삭제한다.** `idx_outbox_pending (status, outbox_event_id)`로 대기 조회를 빠르게 한다.
   구현: `OutboxCleanUpScheduler`가 1시간 간격(기동 1분 뒤 시작)으로 `CleanUpOutboxEventsUseCase`를 호출해 발행 완료 시각이 보관 기간(기본 7일)보다 오래된 `PUBLISHED` 이벤트를 `outbox_event_id` 순서로 한 번에 1000건까지, 묶음이 가득 차면 최대 10묶음 삭제한다. 발행 대기 이벤트는 오래되어도 지우지 않는다(Kafka 장애로 밀려도 이벤트를 잃지 않는다). 삭제는 조건에 맞는 행만 지우는 SQL 한 문장이라 여러 인스턴스가 동시에 돌려도 안전해서 락을 쓰지 않는다. 설정은 `inventory.outbox.cleanup`(`enabled`, `interval`, `initial-delay`, `retention`, `batch-size`)이고 테스트에서는 기본으로 끈다. 구독자용 계약은 [events.md](../events.md)에 정리했다.

## 결과 (Consequences)

- 얻는 것: 수량과 이벤트가 어긋나지 않고(같은 트랜잭션), 브로커 장애 중에도 업무가 진행되며, 발행이 재개되면 순서대로 따라잡는다. 서비스 코드는 포트만 알아 Kafka와 분리된다.
- 감수하는 것: 구독자가 중복 전달을 멱등하게 처리해야 한다. 한 이벤트가 계속 실패하면 그 뒤 발행이 멈춘다(알림 대상). 발행 지연이 폴링 간격만큼 생긴다. 예약 이벤트를 만들 때 Lot 조회가 한 번 늘어난다.
