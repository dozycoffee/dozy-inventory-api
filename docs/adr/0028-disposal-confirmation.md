# ADR-0028: 폐기 확정 반영 규칙

## 상태

Accepted (2026-10-13). 입고 확정(ADR-0017, ADR-0019)과 같은 방식을 따르고, 시나리오 9.2 원문이 Notion에만 있어 이 ADR에서 정하지 않은 값은 입고·실사 조정의 선례를 따랐다.

## 배경 (Context)

유통기한 스캔(ADR-0027)이 경과한 재고를 폐기 예정(`DISPOSAL_SCHEDULED`) 행으로 옮기고 `Lot 경과` 이벤트로 WMS가 폐기 신청을 만든다. 승인과 폐기 처리장 이동은 사람이 WMS에서 하고(시나리오 결정 7), 폐기가 확정되면 WMS가 inventory 서비스에 알려 재고에서 차감한다. 불량 판정(`DEFECTIVE`) 재고의 폐기도 같다.

## 결정 (Decision)

1. **요청 한 건은 폐기 품목 한 줄이다.** 멱등 키는 품목마다 하나이고 원인 문서는 WMS의 폐기 상품 ID(`disposalItemId`)라 이력 한 행과 1:1이다. 부분 실패는 품목 단위로 재시도한다(입고 확정과 같은 방식).
2. **불량(`DEFECTIVE`)·폐기 예정(`DISPOSAL_SCHEDULED`) 재고만 폐기한다.** 정상(`NORMAL`) 재고의 폐기 요청은 400(`INV_INVALID_DISPOSAL_QUALITY_STATUS`)으로 반려한다. 정상 재고는 먼저 불량 판정이나 유통기한 스캔으로 옮겨져야 한다.
3. **재고 행은 `상품 + Lot 번호 + 품질 상태 + 창고`로 찾는다.** Lot이 등록되어 있지 않으면 404(`INV_LOT_NOT_FOUND`), 그 품질 상태의 재고 행이 없으면 404(`INV_INVENTORY_NOT_FOUND`)이고 폐기가 Lot이나 행을 만들지 않는다.
4. **폐기 수량은 그 행의 가용 수량 안에서 총 수량을 줄이는 조건부 UPDATE다.** 넘으면 409(`INV_INSUFFICIENT_AVAILABLE_QUANTITY`)로 거부하고 아무것도 바꾸지 않는다. 수량이 0이 되어도 행은 유지한다(시나리오 결정 12).
5. **이력은 `DISPOSAL`, 원인 문서는 `DISPOSAL_ITEM`이다.** 수량·이력·이벤트는 한 트랜잭션이고 이벤트는 재고 감소(`INVENTORY_DECREASED`)다.
6. **재요청은 처음 응답과 같은 결과를 준다.** 저장된 이력으로 결과를 다시 만들고(처음 시점의 `quantityAfter`), 내용이 다르면 409(`INV_IDEMPOTENCY_KEY_CONFLICT`)다. 같은 키가 동시에 들어오면 늦은 쪽의 이력 저장이 중복 키로 실패해 롤백되고(차감도 되돌아간다) 먼저 처리된 결과를 받는다.
7. **API는 `POST /api/v1/disposals`이고 호출은 `inventory:service`만 허용한다.** 수량은 WMS 확정으로만 바뀐다(원칙 6). 멱등 키는 `Idempotency-Key` 헤더이고 본문은 `warehouseId`, `productId`, `lotNumber`, `qualityStatus`, `quantity`, `disposalItemId`이다. 처음 반영과 재요청 모두 200이고 응답은 입고와 같은 모양(`inventoryId`, `warehouseId`, `productId`, `lotId`, `qualityStatus`, `quantityChange`(음수), `quantityAfter`, `inventoryHistoryId`)이다.

## 결과 (Consequences)

- 얻는 것: 유통기한 경과 재고의 전체 흐름(스캔 → 폐기 신청 → 폐기 확정)이 끝난다. WMS 재전송에 안전하고 가용 수량을 넘겨 차감하지 않는다.
- 감수하는 것: 폐기가 품목 단위 호출이라 폐기 건에 품목이 많으면 호출 수가 늘고 건 전체가 원자적이지 않다. 확정 예약이 잡은 수량은 폐기 예정 행으로 옮겨지기 전이라 폐기할 수 없다.
