# ADR-0042: 원자재 담당 role(`material_manager`) 폐지

## 상태

Accepted (2026-10-15). [ADR-0041](0041-material-manager-role.md)을 대체한다. ADR-0011 결정 1의 role 구성은 다시 3개(`service`, `warehouse_manager`, `admin`)다.

## 배경 (Context)

ADR-0041은 원자재 마스터만 관리하는 담당자에게 `admin`의 넓은 권한을 주지 않으려고 `material_manager`를 더했다. 그런데 어드민 콘솔에서 "재고"가 곧 원자재 재고라서 원자재 마스터와 재고를 한 모드에서 다루기로 했고, 마스터만 맡고 재고는 보지 못하는 담당자가 실제로 없다. 직무를 나눌 근거가 없는 role이 남아 있으면 상품 API의 권한 식이 둘로 갈리고 콘솔의 role 매핑·직원 초대 프리셋도 늘어난다.

## 결정 (Decision)

1. **`material_manager` role을 폐지한다.** `InventoryRole.MATERIAL_MANAGER`를 없앤다. `dozy-auth`에는 등록한 적이 없거나 등록했다면 정리한다(사용자 작업).
2. **상품 쓰기는 `admin`만, 상품 조회는 `ANY`(`service`, `warehouse_manager`, `admin`)로 되돌린다.** `InventoryAuthorize.PRODUCT_WRITE`·`PRODUCT_READ`는 없애고 컨트롤러가 `ADMIN`·`ANY`를 직접 쓴다.
3. 원자재 마스터 담당을 따로 두어야 할 업무가 생기면 그때 role을 다시 정한다.

## 결과 (Consequences)

- 얻는 것: role이 3개로 줄고 상품 API의 권한 식이 기존 상수로 돌아간다. 콘솔은 `inventory:admin` 하나로 원자재와 재고를 함께 다룬다.
- 감수하는 것: 원자재 마스터만 맡기는 최소 권한 계정을 만들 수 없다. 해당 담당자에게도 `admin`을 주어야 한다.
