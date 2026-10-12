# ADR-0033: 대사 보정 규칙

## 상태

Accepted (2026-10-12). F-019. WMS 합계를 받는 방식, 비교 단위, 승인 권한, 승인 시점 재계산 동작은 사용자 확인을 거쳤고, 나머지는 시나리오 결정 8·10(대사 보정)과 ADR-0026의 선례를 따랐다. WMS 쪽 구현이 아직 없어 이 레포가 WMS 조회 API 계약을 정하고 WMS가 맞춘다. 구현은 계층별 PR로 나눈다: (1) inventory의 대사용 조회와 `RECONCILIATION` 반영, (2) 보정안 도메인·영속성, (3) 대사 실행 application과 스케줄러, (4) 승인·거부 application, (5) 웹 어댑터.

## 배경 (Context)

수량의 기준은 inventory 서비스의 총 수량이지만 물리 사실은 WMS가 기준이다(원칙 7). 입고·출고·실사가 모두 맞게 전달되어도 결품, 유실, 오전송 같은 이유로 두 서비스의 수량이 어긋날 수 있다. 확정 응답에 처리 후 수량을 담아 WMS가 즉시 비교하는 것이 첫 번째 방어선이고(결정 8), 하루 1회 대사 배치가 두 번째다. 대사는 수량을 스스로 고치지 않고 보정안을 만들며, 사람이 원인을 확인해 항목별로 승인해야 반영한다(원칙 9).

## 결정 (Decision)

1. **WMS 합계는 inventory가 WMS 조회 API를 호출해 받는다**(사용자 확인). 출력 포트(`WmsStockPort`)로 추상화하고 HTTP 어댑터가 system 토큰(`dozy-auth` system client)으로 호출한다. 계약: `GET {WMS}/api/v1/stock-quantities?warehouseId={id}&productId={선택}&lotNumber={선택}` → `200 {"items":[{"productId","lotNumber","qualityStatus","quantity"}]}`. `quantity`는 창고에 실제로 있는 총 수량(예약과 무관)이고, 수량이 0인 항목은 생략해도 된다. `productId`·`lotNumber`는 승인 시점에 한 행만 다시 조회할 때 쓴다. 주소는 설정 `inventory.reconciliation.wms.base-url`(환경변수 `WMS_BASE_URL`)이고 비어 있으면 대사 실행이 실패로 기록된다.
2. **비교 단위는 창고 × 상품 × Lot × 품질 상태이다**(사용자 확인). inventory 재고 행의 총 수량(예약 수량 제외)과 WMS 수량을 비교한다. 양쪽의 합집합을 보고, 한쪽에 없는 행의 수량은 0으로 본다(양쪽 모두 0이면 같다). 실행 대상 창고는 재고 행이 있는 모든 창고이고 창고마다 `reconciliation_run` 한 건을 만든다.
3. **보정안 한 건은 불일치한 행 하나이다.** `stock_adjustment`(`RECONCILIATION`, `PENDING`)와 항목 하나(`inventory_quantity`, `wms_quantity`, 변동량 = WMS 수량 − inventory 수량)이고 요청자는 `system`이다. 일괄 승인은 없고 항목마다 승인한다. WMS에 있는데 inventory에 Lot이 등록되어 있지 않은 행은 보정안을 만들 수 없어 경고 로그만 남기고 불일치 수에 포함하지 않는다(입고 확정이 먼저 와야 한다).
4. **멱등 키는 `reconciliation-{창고}-{yyyyMMdd}-{lotId}-{품질 상태}`이다.** 같은 날 대사를 다시 실행하거나 여러 인스턴스가 동시에 돌아도 같은 보정안이 중복되지 않는다(`uq_adjustment_idempotency`). 같은 (창고, Lot, 품질 상태)에 처리 대기 중(`PENDING`)인 보정안이 이미 있으면 새로 만들지 않는다(승인할 때 최신 수량으로 다시 계산한다). 이 키가 반영할 때 이력(`inventory_history`)의 멱등 키도 된다.
5. **승인 권한은 창고 관리자와 `admin`이고, 임계치를 넘는 건은 `admin`만이다**(사용자 확인). 창고 관리자는 배정된 창고의 보정안만 다룬다(ADR-0032). 항목의 `|변동량|`이 상품 카테고리의 임계치(실사 조정과 같은 `AdjustmentPolicy`)를 **넘으면** 상위 승인자(`admin`)만 승인할 수 있고, 창고 관리자는 403(`INV_RECONCILIATION_ADMIN_REQUIRED`)이다. 승인자는 토큰의 principalId이다. 거부는 두 역할 모두 임계치와 무관하게 할 수 있다.
6. **승인 시점에 WMS 수량을 다시 조회해 달라졌으면 재계산만 하고 반영하지 않는다**(사용자 확인). 다시 조회한 WMS 수량이나 지금의 inventory 수량이 보정안의 값과 다르면 보정안의 두 수량과 변동량을 새 값으로 갱신하고 아무것도 반영하지 않은 채 409(`INV_RECONCILIATION_CHANGED`)로 알린다. 담당자가 새 숫자를 확인하고 다시 승인해야 한다(사람이 확인한 숫자만 반영한다). 새 변동량이 0이면 불일치가 사라진 것이라 보정안을 `REJECTED`로 닫고 같은 409로 알린다.
7. **승인하면 같은 트랜잭션에서 반영한다.** `PENDING` → `APPLIED` 조건부 UPDATE(승인자·승인 시각 기록) 후 `AdjustInventoryUseCase`로 재고에 반영한다(이력은 `RECONCILIATION`, 원인 문서는 항목 ID, 증가·감소 이벤트, 반영한 행의 할당 보류 해제). 감소는 가용 수량 안에서만 가능해, 예약 때문에 모자라면 409(`INV_INSUFFICIENT_AVAILABLE_QUANTITY`)이고 보정안은 `PENDING`으로 남는다. `APPROVED` 상태는 쓰지 않는다(승인과 반영이 한 단계다).
8. **승인·거부는 상태로 멱등하다.** 승인은 이미 `APPLIED`이면 바꾸지 않고 현재 보정안을 200으로 돌려준다(이력의 `(멱등 키, 재고 행)` 유니크가 이중 반영을 막는다). 거부는 이미 `REJECTED`이면 같다. 다른 상태 조합(`REJECTED`를 승인, `APPLIED`를 거부)은 409(`INV_INVALID_ADJUSTMENT_STATE`)이다. 동시에 승인하면 조건부 UPDATE에서 밀린 쪽이 다시 읽어 판정한다. 변경 요청이지만 보정안 ID가 식별자이고 보정안의 멱등 키가 이력의 키가 되므로 `Idempotency-Key` 헤더는 받지 않는다(ADR-0023과 같은 이유).
9. **대사 실행은 하루 1회 스케줄러가 돌린다**: 크론 `0 30 1 * * *`(`Asia/Seoul`, 설정 `inventory.reconciliation.cron`). 창고별로 실행을 기록하고(`RUNNING` → `COMPLETED`/`FAILED`, 비교한 항목 수, 불일치 수) 한 창고의 실패가 다른 창고를 막지 않는다. WMS 호출이 실패하면 그 실행이 `FAILED`이고 다음 실행에서 다시 한다. 요청 밖 작업이라 `SystemActor`로 실행한다. 테스트에서는 끈다.
10. **API**(모두 `inventory:admin`과 `inventory:warehouse_manager`, 창고 관리자는 배정된 창고만):
    - `GET /api/v1/reconciliations/proposals?warehouseId&status&page&size` 보정안 목록(최신순, 창고 생략 시 범위의 창고, 상태 생략 시 전체), `GET /api/v1/reconciliations/proposals/{id}` 상세. 항목의 inventory·WMS 수량, 변동량, 상태, 승인 정보를 준다.
    - `POST /api/v1/reconciliations/proposals/{id}/approval`, `POST /api/v1/reconciliations/proposals/{id}/rejection`.
    - 대사 실행 기록 조회와 수동 실행 API는 두지 않는다(스케줄러와 로그, 필요해지면 추가).
11. **오류**: 보정안 없음(실사 조정 포함 다른 유형이면 없는 것으로 본다) 404(`INV_ADJUSTMENT_NOT_FOUND`), 상태 충돌 409(`INV_INVALID_ADJUSTMENT_STATE`), 변동 있음 409(`INV_RECONCILIATION_CHANGED`), 상위 승인자 필요 403(`INV_RECONCILIATION_ADMIN_REQUIRED`), 창고 범위 밖 403(`INV_WAREHOUSE_ACCESS_DENIED`), WMS 조회 실패 502(`INV_WMS_UNAVAILABLE`).

## 결과 (Consequences)

- 얻는 것: 두 서비스의 수량 어긋남이 하루 안에 발견되고, 사람이 승인한 숫자만 반영되며, 승인 전후의 변동으로 엉뚱한 수량이 반영되지 않는다. WMS 연동 방식은 포트 한 곳만 바꾸면 달라질 수 있다.
- 감수하는 것: WMS가 Lot별 품질 상태별 합계 조회 API를 만들어야 한다. 창고의 모든 행을 한 번에 가져와 비교하므로 규모가 커지면 페이지를 나누는 계약 변경이 필요하다. 입고·출고가 진행 중인 시간대에는 일시적 불일치가 보정안으로 올라올 수 있어(그래서 새벽에 돌리고 승인 때 다시 확인한다) 담당자가 걸러 낸다. 승인할 때 숫자가 바뀌면 두 번 승인해야 한다.
