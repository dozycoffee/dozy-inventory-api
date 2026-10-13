# 이벤트 계약 (구독자용)

inventory 서비스가 Kafka로 발행하는 이벤트의 계약이다. 구독하는 서비스(OMS, WMS, Store)는 이 문서를 기준으로 한다.
발행 방식의 배경은 [ADR-0025](adr/0025-outbox-event-publishing.md), 공통 형식(envelope)의 배경은 [ADR-0038](adr/0038-common-event-format.md)과 [ADR-0039](adr/0039-event-metadata-in-payload.md)에 있다. `data`의 필드 이름과 이벤트 유형은 계약이라 함부로 바꾸지 않는다.

> **전환 중**: 아래는 ADR-0039에서 확정한 형식이다. 메타데이터는 값(JSON)에만 두고 Kafka 헤더는 쓰지 않는다. 코드 구현은 진행 중이며(`feature_list.json` F-024) 구현 전까지 실제 발행은 이전 형식(값의 `traceparent` 없음, Kafka 헤더 7종이 함께 나감)이다. 구독자는 헤더를 쓰지 말고 값의 필드만 읽어야 한다.

## 전달 방식

- 상태를 바꾸는 트랜잭션 안에서 `outbox_event`에 저장되고, 별도 발행기가 순서대로 Kafka로 내보낸다. 수량·예약이 바뀌었는데 이벤트가 빠지거나, 롤백되었는데 이벤트만 나가는 일은 없다.
- **최소 한 번(at-least-once) 전달**이다. 발행 직후 서비스가 죽으면 같은 이벤트가 다시 나갈 수 있으므로 **구독자는 값의 `eventId`(UUID)로 중복을 걸러야 한다.** 격리된 이벤트를 재발행해도 `eventId`는 같다.
- 같은 파티션 키의 이벤트는 저장된 순서대로 발행된다. 다른 키 사이의 순서는 보장하지 않는다.
- 발행에 영구히 실패하는 이벤트는 격리(`DEAD`)되어 나가지 않을 수 있고(운영자가 확인해 재발행한다), 이때 같은 키의 뒤 이벤트는 그대로 발행된다. 구독자는 빠진 이벤트를 만날 수 있고, 재발행된 이벤트는 그 사이 나간 이벤트보다 늦게 도착한다. 상태를 다시 확인해야 하면 조회 API(가용 재고 조회 등)로 맞춘다(ADR-0037).
- 발행이 끝난 이벤트는 보관 기간(기본 7일) 뒤 서비스의 DB에서 지워진다. Kafka의 보관은 브로커 설정을 따른다.
- 값(value)은 JSON 문자열이다. 필드 이름은 camelCase, 시각은 오프셋을 포함한 ISO-8601, 날짜는 `yyyy-MM-dd`이다. **값이 `null`인 필드는 내보내지 않는다.** 구독자는 필드가 없는 것과 `null`을 같게 처리한다.

## 공통 형식(envelope)

모든 이벤트의 값은 공통 메타를 최상위에 두고 도메인 본문을 `data` 안에 둔다. **메타데이터는 값에만 있고 Kafka 헤더는 쓰지 않는다.** 아래 각 이벤트 절의 표는 `data`의 필드다.

```json
{
  "eventId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
  "eventType": "RESERVATION_CREATED",
  "eventVersion": 1,
  "occurredAt": "2026-10-12T10:00:00+09:00",
  "source": "inventory",
  "traceparent": "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
  "data": { "reservationId": 1 }
}
```

| 필드 | 설명 |
|------|------|
| eventId | 이벤트 고유 ID(UUID, 36자). 서비스 전체에서 유일하고 구독자의 중복 제거 키다 |
| eventType | 이벤트 유형. `{집계}_{과거형 동사}` 대문자 스네이크. 접두사가 집계(토픽)다 |
| eventVersion | 스키마 버전(정수, 1부터). 필드 삭제·이름 변경·의미 변경처럼 호환되지 않는 변경에만 올린다. 필드 추가는 올리지 않는다 |
| occurredAt | 이벤트를 트랜잭션 안에서 Outbox에 기록한 시각. Kafka 발행 시각이 아니다 |
| source | 발행 서비스 이름(`inventory`) |
| traceparent | (선택) W3C Trace Context(`00-{trace-id 32자}-{span-id 16자}-{flags 2자}`). 요청 안에서 저장한 이벤트에만 있고 없으면 필드를 보내지 않는다 |
| data | 이벤트별 본문 |

구독자는 알 수 없는 필드를 무시해야 한다(필드 추가는 `eventVersion`을 올리지 않는다).

## 토픽과 키

파티션 키는 **문자열**이다. 구독자는 숫자로 파싱하지 않는다. 집계와 집계 ID는 헤더나 별도 필드가 아니라 토픽 이름, `eventType`의 접두사, `data`의 필드(`reservationId`, `inventoryId` 등)로 알 수 있다.

| 집계 | 토픽 | 파티션 키(메시지 키) |
|------|------|-----------------------|
| 재고 | `dozy.inventory.inventory` | `창고ID:상품ID` |
| 예약 | `dozy.inventory.reservation` | `창고ID:예약ID` |
| Lot | `dozy.inventory.lot` | `Lot ID` |
| 상품 | `dozy.inventory.product` | `상품ID` |

토픽 접두사 `dozy.inventory`는 설정(`inventory.outbox.publisher.topic-prefix`)이다.

**Kafka 헤더는 쓰지 않는다**(ADR-0039). 구독자는 헤더가 아니라 값만 읽는다.

## 재고 이벤트

유형: `INVENTORY_INCREASED`, `INVENTORY_DECREASED`

| 필드 | 설명 |
|------|------|
| inventoryId, warehouseId, productId, lotId | 재고 행과 그 키 |
| qualityStatus | `NORMAL` 또는 `DEFECTIVE` |
| quantityChange | 총 수량의 변화량(양수) |
| quantityAfter | 변경 후 총 수량. 가용 수량이 아니다 |
| referenceType, referenceId | 수량을 바꾼 원인 문서의 유형과 ID |
| idempotencyKey | 수량을 바꾼 요청의 멱등 키 |

예약 수량만 바뀌는 변경은 재고 이벤트가 아니라 예약 이벤트로 알린다. 가용 수량은 이벤트로 약속하지 않으며, 필요하면 가용 재고 조회 API로 확인한다.

## 예약 이벤트

유형: `RESERVATION_CREATED`, `RESERVATION_CONFIRMED`, `RESERVATION_EXTENDED`, `RESERVATION_RELEASED`, `RESERVATION_EXPIRED`, `RESERVATION_FORCE_RELEASED`, `RESERVATION_REALLOCATED`, `RESERVATION_FULFILLED`

| 필드 | 설명 |
|------|------|
| reservationId, warehouseId, channel, externalOrderId | 예약과 호출 서비스가 보낸 주문 정보 |
| idempotencyKey | 예약을 만든 요청의 멱등 키 |
| reason | `RESERVATION_FORCE_RELEASED`에만 있는 사유 코드(예: `LOT_EXPIRED` 유통기한 경과). 다른 유형에는 필드가 없다 |
| items[].productId, items[].quantity | 상품별 요청 수량 |
| items[].allocations[].inventoryId, quantity | 수량이 할당된 재고 행과 수량 |
| items[].allocations[].lotId, lotNumber, expirationDate | 그 재고 행의 Lot 정보. 이벤트를 만든 시점의 값이며 유통기한이 없는 Lot이면 `expirationDate` 필드가 없다 |

`RESERVATION_FORCE_RELEASED`는 inventory 서비스가 확정 전 예약을 강제로 해제했다는 뜻이며 예약 수량은 이미 돌아갔다. OMS는 주문을 다시 예약해 다른 Lot을 할당받을 수 있다.

`RESERVATION_REALLOCATED`는 확정된 예약에서 피킹할 수 없는 Lot의 수량을 다른 Lot으로 옮겼다는 뜻이다. `allocations[]`는 재할당 뒤의 전체 할당이며 수량이 0이 된 재고 행은 빠진다. `idempotencyKey`는 예약을 만든 요청의 키이고 재할당을 요청한 키가 아니다.

WMS는 `allocations[]`로 어느 Lot에서 몇 개를 꺼낼지 추가 조회 없이 알 수 있다. 한 예약이 여러 상품에 걸치므로 키는 `창고ID:예약ID`이고, 같은 예약의 이벤트(생성 → 확정 → 출고 확정 등)는 순서가 지켜진다.

## Lot 경과 이벤트

유형: `LOT_EXPIRED`. 유통기한 스캔(하루 한 번)이 유통기한이 지난 Lot을 처리할 때 발행한다(ADR-0027). WMS는 이 이벤트로 폐기 신청(`REQUESTED`)을 만들고, 건너뛴 확정 예약은 사람이 확인한다. 이번 스캔에서 Lot이 경과로 바뀌었거나 수량을 폐기 예정으로 옮겼을 때 Lot마다 한 건 발행한다(같은 Lot에 남은 수량을 나중에 더 옮기면 다시 발행한다). 임박(`EXPIRING_SOON`)으로만 바뀐 Lot은 발행하지 않는다.

| 필드 | 설명 |
|------|------|
| lotId, productId, lotNumber, expirationDate | Lot 정보 |
| transfers[].warehouseId | 창고 |
| transfers[].sourceInventoryId | 정상 품질이던 재고 행 |
| transfers[].disposalInventoryId | 폐기 예정으로 옮긴 재고 행 |
| transfers[].quantity | 이번에 옮긴 수량(가용 수량만. 확정 예약이 잡은 수량은 옮기지 않는다) |
| confirmedReservationIds | 수량을 잡고 있어 건너뛴 확정 예약 ID. 사람이 확인한다 |

품질 전환은 재고 증감 이벤트(`INVENTORY_INCREASED`·`INVENTORY_DECREASED`)를 발행하지 않는다. 같은 스캔에서 강제 해제한 확정 전 예약은 예약 토픽의 `RESERVATION_FORCE_RELEASED`(`reason`: `LOT_EXPIRED`)로 알린다.

## 수신 이벤트: 직원 창고 소속 (WMS → inventory)

inventory 서비스가 구독하는 이벤트의 계약이다. WMS가 발행한다(ADR-0032). **이 이벤트도 공통 형식(envelope, ADR-0038)이다.** WMS가 이 형식으로 발행하며(WMS ADR-0020) inventory는 envelope만 받는다. 최상위에 `principalId`가 있는 이전 평면 형식은 받지 않고 DLT로 보낸다. 창고 관리자(`inventory:warehouse_manager`)가 접근할 수 있는 창고를 판단하는 데 쓴다.

| 항목 | 내용 |
|------|------|
| 토픽 | `dozy.wms.warehouse-access` (inventory 설정 `inventory.warehouse-access.listener.topic`) |
| 메시지 키 | `principalId` (같은 직원의 이벤트는 순서가 지켜진다) |
| 값 | JSON |

```json
{
  "eventId": "0199a3c2-8e5a-7f30-8c4b-9d1e2f6a0b73",
  "eventType": "WAREHOUSE_ACCESS_CHANGED",
  "eventVersion": 1,
  "occurredAt": "2026-10-12T10:00:00+09:00",
  "source": "wms",
  "data": {
    "principalId": "0199a3c2-1111-7f30-8c4b-9d1e2f6a0b73",
    "warehouseIds": [10, 20]
  }
}
```

| 필드 | 설명 |
|------|------|
| eventId | 이벤트 고유 ID(UUID). 추적용이다 |
| eventType | `WAREHOUSE_ACCESS_CHANGED`만 받는다 |
| eventVersion | `1`만 받는다. 다른 버전은 DLT로 보낸다 |
| occurredAt | 발생 시각(오프셋 포함 ISO-8601). 처리에는 쓰지 않는다 |
| source | `wms`만 받는다 |
| data.principalId | `dozy-auth`의 직원 principalId(UUID, 36자) |
| data.warehouseIds | 그 직원이 **지금** 소속된 창고 ID 전체. 중복 없는 양수이고 최대 1000개. 소속이 모두 없어지면 빈 배열 |

- **교체 의미**: 받으면 그 직원의 소속을 `warehouseIds`로 통째로 바꾼다. 같은 이벤트를 다시 받아도 결과가 같다(중복 전달에 안전).
- **WMS가 보내는 시점**: 직원의 창고 소속이 추가·삭제될 때마다 그 직원의 전체 목록을 보낸다. 처음 연동할 때는 모든 직원에게 한 번씩 보내 사본을 채운다. 사본이 어긋났다고 의심되면 같은 이벤트를 다시 보내면 복구된다.
- **실패 처리**: 처리에 실패하면 세 번 다시 시도하고 `dozy.wms.warehouse-access.dlt`로 보낸다. JSON이 아니거나 필드 규칙을 어기면 다시 시도하지 않고 바로 보낸다. 계약을 어긴 메시지는 DLT에서 확인한다.
- **사본이 없을 때**: 이 이벤트를 받기 전의 창고 관리자는 모든 요청이 403(`INV_WAREHOUSE_ACCESS_DENIED`)이다.

## 상품 이벤트

유형: `PRODUCT_REGISTERED`, `PRODUCT_STATUS_CHANGED`

| 필드 | 설명 |
|------|------|
| productId, productCode, productName, category, unit, shelfLifeDays, productStatus | 변경 후 상품 스냅샷. 구독자는 이것으로 상품 사본을 갱신한다 |
