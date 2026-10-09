# 이벤트 계약 (구독자용)

inventory 서비스가 Kafka로 발행하는 이벤트의 계약이다. 구독하는 서비스(OMS, WMS, Store)는 이 문서를 기준으로 한다.
발행 방식의 배경은 [ADR-0025](adr/0025-outbox-event-publishing.md)에 있다. payload 필드 이름과 이벤트 유형은 계약이라 함부로 바꾸지 않는다.

## 전달 방식

- 상태를 바꾸는 트랜잭션 안에서 `outbox_event`에 저장되고, 별도 발행기가 순서대로 Kafka로 내보낸다. 수량·예약이 바뀌었는데 이벤트가 빠지거나, 롤백되었는데 이벤트만 나가는 일은 없다.
- **최소 한 번(at-least-once) 전달**이다. 발행 직후 서비스가 죽으면 같은 이벤트가 다시 나갈 수 있으므로 **구독자는 `event-id` 헤더로 중복을 걸러야 한다.**
- 같은 파티션 키의 이벤트는 저장된 순서대로 발행된다. 다른 키 사이의 순서는 보장하지 않는다.
- 발행이 끝난 이벤트는 보관 기간(기본 7일) 뒤 서비스의 DB에서 지워진다. Kafka의 보관은 브로커 설정을 따른다.
- 값(value)은 JSON 문자열이다. 시각은 오프셋을 포함한 ISO-8601이다.

## 토픽, 키, 헤더

| 집계 | 토픽 | 파티션 키(메시지 키) |
|------|------|-----------------------|
| 재고 | `dozy.inventory.inventory` | `창고ID:상품ID` |
| 예약 | `dozy.inventory.reservation` | `창고ID:예약ID` |
| 상품 | `dozy.inventory.product` | `상품ID` |

토픽 접두사 `dozy.inventory`는 설정(`inventory.outbox.publisher.topic-prefix`)이다.

| 헤더 | 내용 |
|------|------|
| `event-id` | 이벤트의 고유 ID(`outbox_event_id`). 중복 제거에 쓴다 |
| `event-type` | 이벤트 유형 |
| `aggregate-type` | `INVENTORY`, `RESERVATION`, `PRODUCT` |
| `aggregate-id` | 집계의 ID(재고 행, 예약, 상품) |

## 재고 이벤트

유형: `INVENTORY_INCREASED`, `INVENTORY_DECREASED`

| 필드 | 설명 |
|------|------|
| eventType, occurredAt | 이벤트 유형, 발생 시각 |
| inventoryId, warehouseId, productId, lotId | 재고 행과 그 키 |
| qualityStatus | `NORMAL` 또는 `DEFECTIVE` |
| quantityChange | 총 수량의 변화량(양수) |
| quantityAfter | 변경 후 총 수량. 가용 수량이 아니다 |
| referenceType, referenceId | 수량을 바꾼 원인 문서의 유형과 ID |
| idempotencyKey | 수량을 바꾼 요청의 멱등 키 |

예약 수량만 바뀌는 변경은 재고 이벤트가 아니라 예약 이벤트로 알린다. 가용 수량은 이벤트로 약속하지 않으며, 필요하면 가용 재고 조회 API로 확인한다.

## 예약 이벤트

유형: `RESERVATION_CREATED`, `RESERVATION_CONFIRMED`, `RESERVATION_EXTENDED`, `RESERVATION_RELEASED`, `RESERVATION_EXPIRED`, `RESERVATION_FULFILLED`

| 필드 | 설명 |
|------|------|
| eventType, occurredAt | 이벤트 유형, 발생 시각 |
| reservationId, warehouseId, channel, externalOrderId | 예약과 호출 서비스가 보낸 주문 정보 |
| idempotencyKey | 예약을 만든 요청의 멱등 키 |
| items[].productId, items[].quantity | 상품별 요청 수량 |
| items[].allocations[].inventoryId, quantity | 수량이 할당된 재고 행과 수량 |
| items[].allocations[].lotId, lotNumber, expirationDate | 그 재고 행의 Lot 정보. 이벤트를 만든 시점의 값이며 유통기한이 없는 Lot이면 `expirationDate`가 null이다 |

WMS는 `allocations[]`로 어느 Lot에서 몇 개를 꺼낼지 추가 조회 없이 알 수 있다. 한 예약이 여러 상품에 걸치므로 키는 `창고ID:예약ID`이고, 같은 예약의 이벤트(생성 → 확정 → 출고 확정 등)는 순서가 지켜진다.

## 상품 이벤트

유형: `PRODUCT_REGISTERED`, `PRODUCT_STATUS_CHANGED`

| 필드 | 설명 |
|------|------|
| eventType, occurredAt | 이벤트 유형, 발생 시각 |
| productId, productCode, productName, category, unit, shelfLifeDays, productStatus | 변경 후 상품 스냅샷. 구독자는 이것으로 상품 사본을 갱신한다 |
