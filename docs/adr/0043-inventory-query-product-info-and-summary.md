# ADR-0043: 관리자 재고 조회의 상품 정보와 상품별 요약

## 상태

Accepted (2026-10-15). [ADR-0030](0030-admin-inventory-query.md) 결정 2(상품 이름을 응답에 넣지 않는다)를 대체하고, 그 "감수하는 것"에 적은 상품 단위 집계를 더한다. 나머지 결정(행 단위 현황, 조건, 정렬, 페이지, 상세, 이력, 권한)은 그대로이다.

## 배경 (Context)

어드민 콘솔은 재고 행에 상품 이름이 없어 상품 마스터를 처음 500건까지 모두 받아 이름을 채운다. 상품이 500개를 넘으면 뒤의 상품은 이름 없이 ID로만 보이고, 창고와 재고가 늘수록 이 방식은 한계가 온다. 현황의 한 줄도 재고 행(창고 × Lot × 품질 상태)이라 같은 상품이 창고별로 흩어져 상품 단위로 얼마나 있는지 한눈에 보기 어렵다. 콘솔이 재고를 전부 받아 직접 합치는 방식은 규모가 커지면 쓸 수 없으므로 집계와 페이지 나누기는 서버가 해야 한다.

## 결정 (Decision)

1. **재고 행 응답(현황, 상세)에 `productCode`, `productName`, `unit`을 더한다.** 필드 추가라 기존 호출자는 영향이 없다.
2. **상품 정보는 다른 도메인의 테이블을 JOIN하지 않고 상품 도메인의 UseCase로 채운다**(ADR-0010 유지). 재고 조회 서비스가 한 페이지의 상품 ID를 모아 `GetProductsUseCase.getByIds`를 한 번 호출해 ID별로 붙인다(`inventory` → `product` 호출 방향, 아키텍처 문서와 같다). 재고 행의 상품은 외래키로 보장되므로 상품이 없으면 데이터 오류로 본다.
3. **상품별 요약 API `GET /api/v1/inventories/summary`를 추가한다.** 한 줄이 상품 하나이고 조건은 현황과 같다(`warehouseId`, `productId`, `qualityStatus`, `lotStatus`, `allocationHold`, 모두 선택). 조건을 모두 만족하는 재고 행을 상품 단위로 합치고 **`상품 ID` 순서**로 `page`(0부터)·`size`(1~100, 기본 20)로 나눈다. `totalElements`는 조건에 맞는 재고 행이 있는 상품 수다. 잘못된 조건은 현황과 같이 400(`INV_INVALID_INVENTORY_QUERY`)이다.
4. **요약의 값**: `productId`, `productCode`, `productName`, `unit`, `warehouseCount`(조건에 맞는 행이 있는 창고 수), `rowCount`(행 수), `quantity`(총 수량 합), `reservedQuantity`(예약 합), `availableQuantity`(총 − 예약, 행 자체의 값이라 품질 상태·보류를 반영하지 않는다. ADR-0030 결정 1과 같다), `defectiveQuantity`(품질 상태가 불량인 행의 총 수량), `heldQuantity`(할당 보류 중인 행의 총 수량), `expiredQuantity`·`expiringSoonQuantity`(Lot 상태가 만료·임박인 행의 총 수량). 구분별 수량은 서로 겹칠 수 있다. 수량이 0인 행도 합에 포함하고 합계는 `Long`이다(총 수량이 `INT`라 합이 `Int` 범위를 넘을 수 있다). 채널용 가용 재고 조회(ADR-0020)의 가용 수량(정상 품질이고 보류가 아닌 행만 센다)과는 다른 값이다.
5. **집계 쿼리는 `inventory`와 `lot`만 쓴다**(`GROUP BY product_id`, `idx_inventory_product`). 상품 정보는 결정 2의 방식으로 채운다. 그래서 상품 이름·코드 순 정렬은 하지 않는다(상품 테이블 JOIN이 필요하다).
6. **상품별 재고 행은 기존 현황으로 이어서 본다**(`GET /api/v1/inventories?productId=`). 별도의 상품별 상세 API는 두지 않는다.
7. **경로와 권한은 현황과 같다**: `inventory:admin`과 `inventory:warehouse_manager`가 호출하고 창고 관리자는 배정된 창고의 행만 합친다(ADR-0032). 창고를 생략하면 배정된 창고로 제한하고 배정되지 않은 창고를 지정하면 403(`INV_WAREHOUSE_ACCESS_DENIED`)이다. `/summary`는 `/{inventoryId}`보다 구체적인 경로라 재고 행 ID로 해석되지 않는다.
8. **읽기 전용이라 락을 걸지 않고 조회 결과는 약속이 아니다**(원칙 3).

## 결과 (Consequences)

- 얻는 것: 콘솔이 상품 마스터 전체를 받지 않아도 되고(상품이 많아도 이름이 빠지지 않는다), 상품 단위 합계와 문제 수량(불량, 보류, 만료·임박)을 서버 페이지로 바로 볼 수 있다. 창고와 재고가 늘어도 콘솔은 한 페이지만 읽고, 상품 하나를 펼칠 때만 그 상품의 행을 읽는다. API 변경은 필드 추가와 새 엔드포인트라 하위 호환이다.
- 감수하는 것: 현황과 상세의 응답이 상품 정보만큼 커지고 조회마다 상품을 한 번 더 읽는다(페이지의 상품 ID를 모아 한 번). 요약은 조건에 맞는 모든 재고 행을 읽어 합치므로 재고 행이 아주 많아지면 비용이 늘고, 그때는 집계 테이블 같은 사전 계산이 필요하다. 상품 이름 순 정렬은 못 한다. 합계 `availableQuantity`에 불량·보류 행이 섞이므로 호출한 쪽이 `defectiveQuantity`, `heldQuantity`와 함께 읽어야 한다.
