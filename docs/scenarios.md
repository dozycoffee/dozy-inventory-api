# 업무 시나리오

inventory 서비스가 하는 일을 시나리오 단위로 정리한 색인이다. 원본 초안은 팀의 Notion(비공개)에 있고, 구현할 장을 작업할 때 필요한 만큼 이 레포의 `docs/`로 옮긴다.
옮기기 전까지 아래 "확정된 결정"이 구현 기준이다. 결정과 어긋나는 내용을 발견하면 임의로 정하지 말고 사용자에게 묻는다.

## 시나리오 목록

| 장 | 내용 | 이식 상태 |
|----|------|-----------|
| 2 | 재고 모델: 총 수량·예약 수량·가용 수량(= 총 − 예약), 품질 상태 `NORMAL`/`DEFECTIVE`/`DISPOSAL_SCHEDULED` | 아래 요약 + [erd.md](erd.md) |
| 3 | 입고 확정 반영 (3.1) | 아래 "입고 확정 반영" |
| 4 | 재고 조회: 가용 재고(채널용), 현황·상세(관리자용), 이력 | 이식 전 |
| 5 | 재고 예약: 요청, 확정 → WMS 출고 지시, 해제 | 이식 전 |
| 6 | 출고 반영: 출고 확정(6.1), Lot 재할당(6.2) | 이식 전 |
| 7 | 반품 복귀 (7.1) | 이식 전 |
| 8 | 조정: 실사 조정(8.1), 수동 조정 불허(8.2), 대사 보정(8.3) | 8.1은 아래 "실사 조정", 나머지는 이식 전 |
| 9 | 유통기한 스캔(9.1), 폐기 확정(9.2) | 이식 전 |
| 10 | 연동·정합성: 이벤트 발행, 멱등성, 총량 대사, 장애 | 일부 [concurrency-and-idempotency.md](concurrency-and-idempotency.md) |

## 확정된 결정

### 경계

- 재고 공간 단위는 창고(Warehouse) 단위 수량까지이며 Location은 WMS 소관이다.
- 예약은 inventory 서비스가 소유한다. 실사는 WMS가 수행하고 조정 확정만 inventory 서비스가 맡는다.
- WMS는 inventory 서비스 API를 동기 호출하고, inventory 서비스는 재고 변동 이벤트를 발행한다.
- inventory 서비스는 입고 확정된 재고만 다루며, 출고 확정 이후의 재고는 추적하지 않는다.
- Lot 번호·제조일자·유통기한은 공급사가 부여해 입고 예정 정보로 전달한 값이다. inventory 서비스와 WMS는 번호를 생성하지 않는다.
- 입고에는 inventory 서비스 상태 전이가 없다. 입고 확정은 `창고 × 상품 × Lot × 품질 상태` 행의 수량 증가와 이력 기록이다.

### 시나리오별 결정

| # | 항목 | 결정 |
|---|------|------|
| 1 | 예약 시 Lot 할당 | 호출 서비스가 확정한 창고 하나에서(후보 창고 선택은 호출 서비스 소관) inventory 서비스가 유통기한 오름차순으로(유통기한 없는 Lot은 마지막, 유통기한이 지난 Lot 제외, ADR-0022) 할당하고 Lot별 수량을 WMS에 전달한다. 지정된 Lot을 피킹할 수 없으면 WMS가 inventory 서비스에 재할당을 요청한다. |
| 2 | 예약 TTL | 요청자가 만료 시각을 지정하고 채널별 상한을 넘을 수 없다(설정 맵과 `default`로 두며 채널 값은 확정하지 않는다. 예: OMS 1시간, 가맹점 48시간은 가정, ADR-0022). 연장은 생성 시점부터의 총 수명이 상한 이하일 때만 허용한다. 확정된 예약은 만료되지 않는다. |
| 3 | 출고 진행 중 취소 | 확정 예약의 취소는 OMS가 WMS에 요청한다. 피킹 전은 자동 취소, 피킹 이후 출고 완료 전은 관리자 승인 후 회수·취소하며, 상품이 원위치된 뒤 WMS가 inventory 서비스에 예약 해제를 요청한다. 출고 완료 후는 반품 흐름으로 처리한다. |
| 4 | 부분 실사(Zone) 반영 | WMS가 실사 마감 시점의 실시간 수량 기준으로 조정 변동량(+/−)을 계산해 전달하고 inventory 서비스는 그대로 반영한다. 대상 단위는 `상품 × Lot × 품질 상태`이다. |
| 5 | 수동 조정 | 실사 없이 inventory 서비스에서 직접 조정하는 경로는 허용하지 않는다. 급한 오류는 해당 Zone의 소규모 실사로 처리한다. |
| 6 | 예약 중인 만료 재고 | 확정 전(`RESERVED`) 예약은 강제 해제하고 OMS에 이벤트를 발행한다(재예약 시 다른 Lot 할당). 확정된(`CONFIRMED`) 예약은 건너뛰되 이벤트로 알려 사람이 확인한다. |
| 7 | 폐기 자동 연계 | inventory 서비스의 `Lot 경과` 이벤트로 WMS가 폐기 신청(`REQUESTED`)을 자동 생성한다. 승인과 폐기 처리장 이동은 사람이 확인한다. |
| 8 | 이벤트·대사·장애 | Outbox를 도입한다. 확정 응답에 처리 후 수량을 담아 WMS가 즉시 비교하고(불일치 시 알림), inventory 서비스가 하루 1회 대사 배치를 돌린다. inventory 서비스 장애 시 WMS의 입고·출고·반품 확정은 진행하고 재전송하며, 실사 조정·Lot 재할당처럼 inventory 서비스 판단이 필요한 작업은 대기한다. |
| 9 | 이력 | 반품 복귀는 `RETURN`으로 분리해 기록하고, 예약 이벤트 이력은 별도 테이블로 둔다. |
| 10 | 대사 보정 | 대사 배치가 WMS 합계 기준 보정안을 만들고, 담당자가 원인을 확인해 항목별로 승인한다(일괄 승인 불가). 임계치와 무관하게 항상 승인이 필요하며 임계치 초과 건은 상위 승인자가 확인한다. 승인 시점에 WMS 합계를 다시 조회해 보정안 생성 이후 변동이 있으면 재계산한다. |
| 11 | Lot 재할당 | 확정 예약에 한해 다른 Lot으로 재할당하고, 부족이 보고된 Lot 행은 신규 할당에서 제외(보류)한다. 보류는 실사 조정 또는 대사 보정으로 정리되면 자동 해제한다. |
| 12 | 0 수량 행 | 수량이 0이 된 재고 행은 삭제하지 않고 유지한다(같은 키의 재고가 다시 들어오면 그 행에 더한다). |
| 13 | 품질 상태 전환 | `QUALITY_TRANSFER` 이력을 전환 전 행에 −N, 전환 후 행에 +N으로 기록한다. 재고 증감 이벤트는 발행하지 않고 `Lot 경과` 이벤트만 발행한다. |

### 입고 확정 반영 (3.1)

WMS에서 검수·적재가 끝난 입고분을 재고로 반영한다. 입고 확정 전에는 inventory 서비스가 그 수량을 알지 못한다. 결정 배경은 [ADR-0017](adr/0017-inbound-confirmation.md)이다.

- **요청 단위**: 입고 품목(입고 상품 줄) 1건이 요청 1건이다. 창고, 상품, 수량, 품질 상태(WMS 검수 판정, `NORMAL` 또는 `DEFECTIVE`), Lot 번호, 제조일자, 유통기한, 원인 문서 ID(입고 상품 ID), 멱등 키를 담는다. Lot 번호·제조일자·유통기한은 공급사가 부여한 값이며 inventory 서비스는 만들지 않는다.
- **Lot**: `상품 + Lot 번호`로 찾는다. 없으면 처음 수신한 값으로 등록하고, 있으면 재사용한다. 제조일자나 유통기한이 저장된 값과 다르면 거부한다(`INV_LOT_MISMATCH`, 409).
- **재고 반영**: `창고 × Lot × 품질 상태` 행의 총 수량을 더하고(없으면 만든다) `INBOUND` 이력을 같은 트랜잭션으로 저장한다. 입고에는 inventory 서비스 상태 전이가 없다. 불량 판정분은 `DEFECTIVE` 행에 더해져 가용 재고에서 제외된다.
- **응답**: 반영 직후의 총 수량(`quantityAfter`)을 담아 WMS가 자기 수량과 즉시 비교한다.
- **멱등**: 같은 멱등 키의 재요청은 반영하지 않고 저장된 이력으로 처음 응답과 같은 결과(처음 시점의 `quantityAfter`)를 반환한다. 같은 키에 다른 내용이 오면 거부한다(`INV_IDEMPOTENCY_KEY_CONFLICT`, 409).
- **반려**: 수량이 1 미만, 품질 상태가 폐기 예정, 멱등 키 형식 오류는 400이고 상품이 inventory 서비스에 없으면 404이다. 비활성(`INACTIVE`) 상품도 이미 도착한 물건이므로 반영한다.
- **이벤트**: `재고 증가` 이벤트를 발행한다(Outbox 구현 전에는 로그만 남긴다).
- **API**: `POST /api/v1/inbound-receipts`(`inventory:service`만). 멱등 키는 `Idempotency-Key` 헤더이고 본문은 `warehouseId`, `productId`, `quantity`, `qualityStatus`, `lotNumber`, `manufactureDate`, `expirationDate`, `inboundItemId`이다. 처음 반영과 재요청 모두 200이다([ADR-0019](adr/0019-inbound-receipt-api.md)).

### 가용 재고 조회 (4.1, 채널용)

OMS·가맹점 서비스가 주문 가능 여부를 판단하도록 상품별 가용 수량을 준다. 결정 배경은 [ADR-0020](adr/0020-availability-query.md)이다.

- **조회 조건**: `productIds`(필수, 1~100개)와 `warehouseIds`(선택, 지정하면 1~100개)다. 창고를 생략하면 그 상품의 재고 행이 있는 모든 창고를 보여 준다.
- **응답**: 상품별로 묶고 창고별 가용 수량과 합계(`totalAvailableQuantity`)를 준다. 재고가 없는 상품은 합계 0이고, 창고를 지정했는데 재고 행이 없는 창고는 0으로 채운다.
- **가용 수량**: `NORMAL` 품질이고 할당 보류가 아닌 행의 (총 수량 − 예약 수량) 합이다. Lot, 유통기한, 위치는 노출하지 않는다.
- **창고 선택**: 가맹점 기준으로 근처 창고 목록과 최적 창고 선택은 호출 서비스(OMS·Store) 소관이다. inventory는 창고별 수량을 주고 지정한 창고에서 예약한다. 전체 창고×전체 상품 목록은 채널용 조회에 두지 않고 관리자 재고 현황(4.2)이 맡는다.
- **호출 권한**: `inventory:service`와 `inventory:admin`이다(창고 관리자는 창고 접근 제어(F-021) 이후).
- **API**: `GET /api/v1/inventories/availability?productIds=1,2,3&warehouseIds=10,20`. 목록은 쉼표로 구분하고(반복 파라미터도 받는다) 중복 ID는 합친다. 응답의 상품은 ID 오름차순, 창고는 ID 오름차순이다. 조건 위반은 400 `INV_INVALID_AVAILABILITY_QUERY`, 파라미터 누락·형식 오류는 400 `VALIDATION_FAILED`이다.

### 예약 생성 (5.1)

OMS·가맹점 서비스가 주문의 상품을 한 창고에서 예약한다. 결정 배경은 [ADR-0022](adr/0022-reservation-and-allocation.md)이다.

- **요청 단위**: 주문 하나 = 요청 하나. 호출 서비스가 확정한 창고(`warehouseId`) 하나와 채널, 주문 ID, 만료 시각, 상품별 수량(1~100개, 중복 불가)을 담는다. 후보 창고 목록은 받지 않는다.
- **할당**: 유통기한이 이른 Lot부터 잡는다(유통기한 없는 Lot은 마지막, 지난 Lot 제외). 하나라도 모자라면 전체 실패(`INV_INSUFFICIENT_AVAILABLE_QUANTITY`, 409)이다.
- **만료**: 요청이 만료 시각을 정하고 채널 상한(설정 `inventory.reservation.max-ttl`)을 넘을 수 없다(`INV_INVALID_RESERVATION_EXPIRY`, 400).
- **거부**: 같은 채널의 같은 주문에 살아 있는 예약이 있으면 `INV_DUPLICATE_ORDER_RESERVATION`, 비활성 상품은 `INV_PRODUCT_NOT_RESERVABLE`(둘 다 409), 없는 상품은 404이다.
- **멱등**: 같은 멱등 키의 재요청은 새로 잡지 않고 저장된 예약의 현재 상태를 반환한다. 같은 키에 다른 내용이면 `INV_IDEMPOTENCY_KEY_CONFLICT`(409)이다.
- **API**: `POST /api/v1/reservations`(`inventory:service`만). 멱등 키는 `Idempotency-Key` 헤더이고 본문은 `warehouseId`, `channel`, `externalOrderId`, `expiresAt`(시간대 오프셋을 포함한 ISO-8601, 예: `2026-10-07T21:30:00+09:00`), `items[{productId, quantity}]`이다. 처음과 재요청 모두 200이고 응답은 예약 ID, 상태, 만료 시각, 항목별 할당(재고 행 ID, 수량)이다.

### 예약 확정·해제·연장·만료 (5.2~5.3)

예약의 이후 상태 전이다. 결정 배경은 [ADR-0023](adr/0023-reservation-state-transitions.md)이다.

- **확정**: `POST /api/v1/reservations/{id}/confirm`. 확정 전(`RESERVED`) 예약을 `CONFIRMED`로 바꾸고 만료 시각이 사라진다. 만료 시각이 지난 예약은 확정할 수 없다(`INV_RESERVATION_EXPIRED`, 409).
- **해제**: `POST /api/v1/reservations/{id}/release`. 확정 전·확정된 예약을 `RELEASED`로 바꾸고 남은 예약 수량을 가용 수량으로 되돌린다. 출고 완료된 예약은 해제할 수 없다(`INV_INVALID_RESERVATION_STATE`, 409).
- **연장**: `POST /api/v1/reservations/{id}/extend`(본문 `expiresAt`). 확정 전 예약의 만료 시각을 현재 만료 시각 이상, 최대 만료 시각(생성 시각 + 채널 상한) 이하로 늘린다. 범위를 벗어나면 400(`INV_INVALID_RESERVATION_EXPIRY`)이다.
- **만료**: 스케줄러가 만료 시각이 지난 확정 전 예약을 `EXPIRED`로 바꾸고 수량을 되돌린다. 확정된 예약은 만료되지 않는다.
- **멱등**: 확정·해제·연장은 `Idempotency-Key` 없이 상태로 멱등하다. 이미 목표 상태이면 아무것도 바꾸지 않고 200으로 현재 상태를 반환하고, 불가능한 전이는 409이다.
- **호출 권한**: `inventory:service`만이다.

### 출고 확정 (6.1)

WMS가 피킹을 마친 출고를 예약에 반영한다. 결정 배경은 [ADR-0024](adr/0024-outbound-confirmation.md)이다.

- **요청 단위**: 예약 하나. 예약의 모든 할당 재고 행에 대한 실제 출고 수량을 한 번에 보낸다(부분 출고 없음). 확정(`CONFIRMED`)된 예약만 대상이다.
- **반영**: 출고한 수량만큼 총 수량과 예약 수량을 함께 줄이고 `OUTBOUND` 이력을 남기며, 예약은 `FULFILLED`가 된다.
- **결품**: 출고 수량이 할당보다 적으면 모자란 만큼은 예약 수량만 가용 수량으로 되돌린다. 총 수량은 줄이지 않고 원인은 실사 조정·대사로 정리한다.
- **응답**: 재고 행별 할당·출고·결품 수량과 처리 후 총 수량을 담아 WMS가 자기 수량과 즉시 비교한다.
- **멱등**: `Idempotency-Key`가 필수이다. 같은 키·같은 수량의 재요청은 새로 반영하지 않고 처음과 같은 결과를 200으로 반환한다. 다른 키는 `INV_IDEMPOTENCY_KEY_CONFLICT`, 다른 수량은 `INV_INVALID_RESERVATION_STATE`(409)이다.
- **API**: `POST /api/v1/reservations/{id}/fulfillment`(`inventory:service`만). 본문은 `allocations[{inventoryId, shippedQuantity}]`이다.

### 실사 조정 (8.1)

WMS가 실사 마감 시점의 실시간 수량으로 계산한 조정 변동량(+/−)을 `상품 × Lot × 품질 상태` 단위로 그대로 반영한다. 결정 배경은 [ADR-0026](adr/0026-stock-adjustment.md)이다.

- **요청 단위**: 실사 건 하나(`auditId`)에 항목 여러 개(1~500개)이며 모두 한 트랜잭션이다. 하나라도 실패하면 전체를 되돌린다. 멱등 키는 요청 하나에 하나다.
- **반영**: 증가는 재고 행이 없으면 만들고, 감소는 행이 있어야 하며 예약 수량을 뺀 가용 수량 안에서만 가능하다(넘으면 409 `INV_INSUFFICIENT_AVAILABLE_QUANTITY`). Lot은 이미 등록되어 있어야 한다(404 `INV_LOT_NOT_FOUND`). 품질 상태는 `NORMAL`, `DEFECTIVE`, `DISPOSAL_SCHEDULED` 모두 허용한다. 반영한 행의 할당 보류는 풀린다.
- **승인**: 항목 하나의 변동량 절댓값이 상품 카테고리의 임계치(`inventory.adjustment.approval-threshold`, 기본 100은 가정한 값)를 넘으면 `approvedBy`가 있어야 한다(없으면 400 `INV_APPROVAL_REQUIRED`). 승인은 WMS가 자체 절차로 거치고 승인자 ID만 보내며 inventory 서비스는 검증하지 않고 기록한다.
- **기록**: 조정은 요청 즉시 `APPLIED`이고 이력은 `ADJUSTMENT`(원인 문서는 조정 항목), 이벤트는 재고 증가·감소다.
- **응답**: 항목별 반영 후 총 수량(`quantityAfter`)을 담는다. 같은 멱등 키의 재요청은 반영하지 않고 처음과 같은 결과를 200으로 반환하며 다른 내용이면 409(`INV_IDEMPOTENCY_KEY_CONFLICT`)다.
- **API**: `POST /api/v1/stock-adjustments`(`inventory:service`만). 멱등 키는 `Idempotency-Key` 헤더이고 본문은 `warehouseId`, `auditId`, `approvedBy`(선택), `items[{productId, lotNumber, qualityStatus, quantityChange}]`이다.

### 상태 흐름

- 예약: `RESERVED` → `CONFIRMED` → `FULFILLED`, `RESERVED` → `RELEASED`/`EXPIRED`, `CONFIRMED` → `RELEASED`(WMS 취소 확정 후)
- Lot: `NORMAL` → `EXPIRING_SOON`(유통기한 30일 이내) → `EXPIRED`(유통기한 당일 이하), 배치 스캔으로 자동 전환
- 조정: `AUDIT`는 요청 즉시 `APPLIED`, `RECONCILIATION`은 `PENDING` → `APPROVED`/`REJECTED` → `APPLIED`
