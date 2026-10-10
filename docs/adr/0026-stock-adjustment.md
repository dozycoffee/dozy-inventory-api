# ADR-0026: 실사 조정 반영 규칙

## 상태

Accepted (2026-10-10). 구현은 계층별 PR로 나눈다: (1) inventory의 수량 조정 반영 UseCase와 Lot 조회 UseCase, (2) `adjustment` 도메인 모델과 영속성, (3) 조정 요청 application(승인 임계치, 멱등), (4) 웹 어댑터.

## 배경 (Context)

실사는 WMS가 수행하고 조정 확정만 inventory 서비스가 맡는다(시나리오 결정 4). WMS가 실사 마감 시점의 실시간 수량 기준으로 계산한 조정 변동량(+/−)을 전달하면 inventory 서비스는 그대로 반영한다. 수동 조정은 허용하지 않는다(결정 5). 시나리오 8.1 원문은 Notion에만 있어, ERD(`stock_adjustment`, `stock_adjustment_item`)와 위 결정에서 정해진 것 외에는 사용자와 함께 이 ADR에서 정한다.

## 결정 (Decision)

1. **요청 한 건은 실사 건 하나이고 여러 항목을 담는다.** 항목은 `상품 × Lot × 품질 상태`별 변동량(0이 아닌 정수)이다. 같은 요청의 항목은 모두 한 트랜잭션에서 반영하며 하나라도 실패하면 전체를 롤백한다. 같은 요청에 같은 (Lot, 품질 상태)가 두 번 나오면 400이다(`uq_adjustment_item`). 멱등 키는 요청 하나에 하나(`Idempotency-Key` 헤더, `stock_adjustment.idempotency_key`)이고 원인 문서는 WMS 실사 건 ID(`external_reference_id`)다.
2. **항목은 `productId`, `lotNumber`, `qualityStatus`, `quantityChange`로 지정한다.** 품질 상태는 `NORMAL`, `DEFECTIVE`, `DISPOSAL_SCHEDULED` 모두 허용한다(실사는 폐기 예정 재고도 센다). Lot은 이미 등록되어 있어야 하며 조정이 Lot을 만들지 않는다(없으면 404 `INV_LOT_NOT_FOUND`).
3. **증가는 행이 없으면 만들고, 감소는 행이 있어야 한다.** 감소할 행이 없으면 404(`INV_INVENTORY_NOT_FOUND`).
4. **감소는 가용 수량(총 수량 − 예약 수량) 안에서만 반영한다.** 넘으면 조정 전체를 롤백하고 409(`INV_INSUFFICIENT_AVAILABLE_QUANTITY`)로 거부한다. 재고 행의 `총 수량 ≥ 예약 수량` 불변식을 지키고 예약이 조용히 깨지지 않게 하려는 것이며, 예약을 먼저 해제하거나 재할당(F-016)한 뒤 다시 요청한다.
5. **승인이 필요한 변동량은 카테고리별 임계치로 판단한다.** 항목 하나의 `|변동량|`이 그 상품 카테고리의 임계치(설정 `inventory.adjustment.approval-threshold`의 카테고리 맵, 없으면 `default`)를 넘으면 요청에 승인자(`approvedBy`)가 있어야 한다. 하나라도 넘는데 승인자가 없으면 아무것도 반영하지 않고 400으로 거부한다(`INV_APPROVAL_REQUIRED`). 승인은 WMS가 자체 절차로 거친 뒤 승인자 ID를 보낸다. 임계치 이하여도 승인자가 오면 기록한다. 승인 시각은 반영 시각이다. 기본 임계치 100은 가정한 값이라 운영 전에 정한다(🔶).
6. **`AUDIT` 조정은 요청 즉시 `APPLIED`다.** `PENDING`·`APPROVED`·`REJECTED`는 대사 보정(F-019)의 상태다.
7. **반영한 행의 할당 보류는 해제한다.** 보류는 실사 조정 또는 대사 보정으로 정리되면 자동 해제한다(시나리오 결정 11).
8. **이력은 `ADJUSTMENT`, 원인 문서는 `STOCK_ADJUSTMENT_ITEM`(항목 ID)이다.** 이력의 멱등 키는 요청의 멱등 키이고 `(idempotency_key, inventory_id)`가 유니크라 한 요청 안에서 행마다 한 건이다. 변동은 재고 증가·감소 이벤트(`INVENTORY_INCREASED`, `INVENTORY_DECREASED`)로 같은 트랜잭션의 Outbox에 저장한다.
9. **같은 멱등 키의 재요청은 새로 반영하지 않고 처음과 같은 결과를 준다.** 저장된 조정과 이력의 `quantity_after`로 결과를 다시 만들고, 내용이 다르면 409(`INV_IDEMPOTENCY_KEY_CONFLICT`)다.
10. **API는 `POST /api/v1/stock-adjustments`이고 호출은 `inventory:service`만 허용한다.** 수량은 WMS 확정으로만 바뀌며(원칙 6) 본사 관리자도 직접 조정할 수 없다.
11. **inventory 도메인은 조정 문서를 모른다.** inventory는 `AdjustInventoryUseCase`로 재고 행 수량 반영·이력·이벤트·보류 해제만 하고, 조정 요청의 문서(`stock_adjustment`)와 승인 규칙은 `adjustment` 도메인이 맡는다. `adjustment`는 항목 ID를 먼저 만든 뒤(원인 문서 ID가 필요하다) 그 ID를 inventory에 넘긴다.
   `adjustment` 도메인의 항목도 품질 상태를 가지므로 `QualityStatus`를 도메인 간 공유 값으로 보고 `global/domain`으로 옮겼다(`IdempotencyKey`, `RequesterService`와 같은 이유로, 다른 도메인의 `domain` 패키지는 import할 수 없다).
12. **`adjustment` 도메인 모델은 조정과 항목이다.** `StockAdjustment`(실사 조정은 `audit(...)`으로 `APPLIED` 상태로 만들고, 승인자가 있으면 승인 시각은 생성 시각)와 `StockAdjustmentItem`(`Lot × 품질 상태`의 0이 아닌 변동량, 대상 재고 행은 재고에 반영한 뒤 기록). 항목의 `inventory_id`는 반영 뒤에 `assignInventoryIds`로 채운다. 대사 보정(`RECONCILIATION`)용 필드(대사 실행, 대사 시점 수량)는 F-019에서 추가한다.
13. **조정 요청 흐름은 `StockAdjustmentService`가 오케스트레이션한다.** 트랜잭션 밖에서 항목의 Lot(`GetLotUseCase`)을 찾고, 같은 멱등 키의 이전 결과가 있으면 그것을 반환하고(내용이 다르면 `INV_IDEMPOTENCY_KEY_CONFLICT`, 이때는 승인 확인도 다시 하지 않는다), 승인 필요 여부를 확인한 뒤, 한 트랜잭션에서 조정 저장 → 재고 반영(`AdjustInventoryUseCase`, 항목 ID가 원인 문서) → 항목의 재고 행 기록을 처리한다. 같은 멱등 키가 동시에 들어오면 늦은 쪽의 조정 저장이 중복 키로 실패해 롤백되므로(재고 반영도 되돌아간다) 트랜잭션 밖에서 저장된 조정으로 이전 결과를 반환한다. 이전 결과의 반영 후 수량은 이력(`GetAdjustedQuantitiesUseCase`)에서 복원해 재요청 사이에 재고가 바뀌어도 처음과 같다.
14. **승인 임계치는 `AdjustmentPolicy` 포트와 설정으로 둔다.** 설정 `inventory.adjustment.approval-threshold`의 `default`(기본 100, 가정한 값)와 `categories`(카테고리명 → 수량)이고 항목 하나의 `|변동량|`이 임계치를 **넘을 때**(같으면 아님) 승인자가 필요하다. 승인자 문자열이 공백뿐이면 없는 것으로 본다. 승인 필요 오류는 `INV_APPROVAL_REQUIRED`(400)이다.

## 결과 (Consequences)

- 얻는 것: 실사 반영이 재전송에 안전하고(멱등), 재고 불변식과 예약을 깨지 않으며, 변동은 이력과 이벤트로 남는다. 승인 규칙이 설정으로 조정된다.
- 감수하는 것: 예약 수량 때문에 실제로 모자란 수량을 바로 반영하지 못하면 WMS가 예약 정리 후 다시 요청해야 한다. 승인자는 WMS가 보낸 값을 믿고 기록한다(inventory 서비스가 승인자를 검증하지 않는다). 항목 수가 많으면 한 트랜잭션이 길어진다.
