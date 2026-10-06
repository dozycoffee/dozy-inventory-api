# ADR-0006: 서비스 간 인증과 창고 접근

## 상태

Accepted (2026-10-05). role 구성(`ims:service`·`ims:admin` 2개)은 [ADR-0011](0011-auth-integration.md)에서 3개로 대체했다.

## 배경 (Context)

`dozy-auth`는 JWT(RS256)와 audience·role 모델을 제공하고, 서비스 간 호출은 system client(client credentials, `svc-{서비스명}`)가 맡는다. 점주(파트너) 토큰은 `aud=["store"]` 고정이라 IMS를 직접 호출할 수 없다.
IMS를 호출하는 주체는 서비스(WMS, OMS, Store, 스케줄러)와 사람(재고 담당자)으로 나뉜다. 창고는 복수를 전제한다.

## 결정 (Decision)

- 서비스는 `dozy-auth`의 system client로 인증하고 `ims:service` role을 부여한다. 사람은 `aud`에 `ims`가 포함된 직원 토큰으로 어드민 콘솔을 통해 호출하며 `ims:admin` role을 부여한다.
- 점주 요청은 Store가 권한을 확인한 뒤 `svc-store`로 대신 호출한다. 요청자는 요청에 담아 감사 기록용으로 남긴다.
- 직원의 창고 소속은 WMS가 소유하고, IMS는 WMS의 소속 변경 이벤트로 사본(`warehouse_access`)을 유지해 창고별 접근을 검사한다. 사본이 없으면 접근을 거부하고 소속 사본 없음을 로그·알림으로 남긴다.
- role은 소수의 큰 단위로 나눈다.

## 결과 (Consequences)

- 얻는 것: `dozy-auth` 모델과 일치하고 스케줄러·이벤트 처리·점주 요청이 구조적으로 막히지 않는다.
- 감수하는 것: WMS가 소속 변경 이벤트를 발행해야 하고, IMS는 동기화 지연을 처리해야 한다. role이 둘뿐이라 서비스 간 권한 분리(예: OMS는 예약만)를 role로 하지 못하고 OMS도 확정·조정 API를 호출할 수 있다. 필요해지면 role을 세분화한다. (검토한 대안: 모든 호출에 사용자 토큰 전파, IMS 자체 소속 데이터 보유, 어드민 콘솔에서 창고 필터링 — 마지막은 `dozy-auth`의 리소스 인가 규칙에 어긋나 제외했다.)
- 후속: `dozy-auth` 프로젝트에서 `ims` audience, `ims:service`·`ims:admin` role, system client를 등록해야 한다.
