# ADR-0041: 원자재 담당 role(`material_manager`)

## 상태

Superseded by [ADR-0042](0042-remove-material-manager-role.md) (2026-10-15). 원래 상태: Accepted (2026-10-14). ADR-0011 결정 1의 role 구성(3개)에 `material_manager`를 더한다. 어드민 콘솔에 원자재 관리 모드를 따로 두기로 하면서 정했다.

## 배경 (Context)

상품 마스터는 inventory가 소유한다(ADR-0004). 이 상품은 원두·시럽 같은 **원자재**이고, 판매 상품(아메리카노 등)은 catalog의 일이다. 지금 상품 등록·상태 변경은 `inventory:admin`만 호출할 수 있는데, `admin`은 전체 창고 재고 조회, 대사 승인, Outbox 격리까지 다루는 본사 총괄 role이다. 원자재 마스터만 관리하는 담당자에게 이 모두를 주면 과하다.

## 결정 (Decision)

1. **`material_manager` role을 추가한다.** 원자재(상품 마스터) 등록과 상태 변경, 그리고 상품 조회만 할 수 있다. 창고 범위 개념이 없다(마스터는 창고와 무관하다).
2. **`admin`은 `material_manager`의 권한을 모두 포함한다.** 상품 쓰기는 `admin`과 `material_manager` 둘 다 허용하고(`InventoryAuthorize.PRODUCT_WRITE`), 상품 조회는 기존 호출자에 `material_manager`를 더한다(`PRODUCT_READ`). 총괄이 원자재를 못 보는 상태를 만들지 않는다.
3. **`material_manager`는 재고를 보지 못한다.** 재고 현황·상세·이력, 가용 재고 조회, 대사 보정안, Outbox 격리 API는 그대로 막힌다. 가용 재고 조회가 쓰는 `ANY`에는 더하지 않았다. 원자재 담당이 재고 수량까지 보려면 `warehouse_manager`나 `admin`을 따로 받는다.
4. **`dozy-auth`에 `inventory:material_manager`를 등록한다.** 등록 후 code는 바꿀 수 없다.

## 결과 (Consequences)

- 얻는 것: 마스터만 맡는 담당자에게 최소 권한을 줄 수 있고, 콘솔의 원자재 관리 모드가 이 role 하나로 구분된다.
- 감수하는 것: role이 4개가 되어 상품 API의 권한 식이 둘로 갈린다(`PRODUCT_WRITE`, `PRODUCT_READ`). 새 API가 `ANY`를 쓸 때 `material_manager`가 빠지는 것이 의도임을 코드 주석으로 남겼다.
