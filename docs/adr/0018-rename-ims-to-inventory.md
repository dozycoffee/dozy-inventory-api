# ADR-0018: 서비스 이름을 IMS에서 inventory로 변경

## 상태

Accepted (2026-10-07)

## 배경 (Context)

다른 서비스는 `wms`, `catalog`, `store`, `auth`처럼 풀어 쓴 이름을 audience와 패키지에 쓰는데 이 서비스만 약어 `IMS`를 썼다. 저장소 이름과 DB는 이미 `inventory`다. `dozy-auth`에 audience를 아직 등록하지 않았고 배포 전이라 지금 바꾸는 비용이 가장 작다.

## 결정 (Decision)

1. **서비스 이름은 `inventory`다.** 패키지 루트는 `com.dozycoffee.inventory`(도메인 패키지 `inventory`는 그 아래 `com.dozycoffee.inventory.inventory`), 클래스는 `InventoryApplication`·`InventoryRole`·`InventoryAuthorize`·`InventoryIntegrationTest`, 설정 키는 `inventory.*`(WMS의 `wms.*`처럼), OpenAPI 제목은 "DOZY COFFEE Inventory API"다.
2. **audience와 role은 `inventory`, `inventory:service`, `inventory:warehouse_manager`, `inventory:admin`이다.** `dozy-auth`에는 이 이름으로 등록한다(ADR-0011의 `ims`를 대체).
3. **오류 코드 접두사는 `IMS_` 대신 `INV_`이다**(ADR-0009의 결정 2를 대체). `INVENTORY_`로 바꾸면 WMS가 이미 쓰는 `INVENTORY_INSUFFICIENT_AVAILABLE_QUANTITY` 같은 코드와 똑같아져 클라이언트가 `code`로 분기할 때 오분기할 수 있다.
4. **DB 컬럼 `stock_adjustment_item.ims_quantity`는 마이그레이션 `V3`로 `inventory_quantity`로 바꾼다.** 적용된 V1은 수정하지 않는다.
5. **문서의 "IMS"는 "inventory 서비스"로 바꾼다.** 이미 채택한 ADR(0001~0017)의 본문과 머지된 커밋·PR·이슈 제목은 이력이라 고치지 않는다. 그 안의 "IMS"는 이 서비스의 옛 이름이다.

## 결과 (Consequences)

- 얻는 것: 다른 서비스와 이름 체계가 맞고 코드와 문서에서 약어가 사라진다. 오류 코드가 WMS와 겹치지 않는다.
- 감수하는 것: 과거 ADR과 이력에 "IMS"가 남아 새로 읽는 사람이 옛 이름을 만난다(이 ADR이 대응을 알려 준다). Notion 문서의 "IMS" 이름은 이 저장소 밖이라 따로 정리해야 한다. 패키지가 `com.dozycoffee.inventory.inventory`로 이름이 반복된다.
