# 운영 런북 — 리뷰 블라인드 처리

약관 11조(정보통신망법 44조의2)에 따른 임시조치 절차다.
신고가 들어왔다고 서버가 자동으로 가리지 않는다 — 관리자가 내용을 보고 직접 판단한다.

자동 블라인드를 넣지 않은 이유는 경쟁 가게의 조직적 신고 때문이다.
신고 수만으로 가리면 멀쩡한 리뷰가 사라진다.

## 관리자 페이지에서 한다

`/admin` → **신고**. 신고가 많은 리뷰부터 나온다.

1. 목록에서 건수·사유·노출 상태를 본다.
2. **상세**를 열어 본문과 사진을 보고 판단한다. 이 열람은 작성자와 함께 접속기록에 남는다.
3. 처리 사유를 적고 **블라인드**를 누른다. 해제도 같은 화면에서 한다.

한 번의 처리로 세 가지가 함께 끝난다 — 상태 변경, 접속기록(사유 포함), **동반 가능 점수 재집계.**
셋은 한 트랜잭션이라 일부만 반영되는 일이 없다. 처리 후 안내에 나오는 `집계에 쓰인 리뷰 N건` 이
집계에 실제로 들어간 리뷰 수이니, 가린 만큼 줄었는지 확인한다.

임시조치 기간은 30일이다. 목록의 `N일 경과` 가 30일을 넘기면 빨갛게 표시된다(아래 "30일이 지난 임시조치").

> 삭제 확정(`DELETED`)은 관리자 페이지에 없다. 아래 SQL 절차를 쓴다.

## 30일이 지난 임시조치

정보통신망법 44조의2의 임시조치는 최장 30일이고, 그 뒤 처리는 사업자가 정한다.
서버는 상태를 자동으로 바꾸지 않는다 — 매일 05:00(KST)에 넘긴 건을 서버 로그에 WARN 으로 남기고,
신고 목록에서 강조하기만 한다.

넘긴 건은 셋 중 하나로 끝낸다.

| 판단 | 무엇을 하는가 |
|---|---|
| 위반이 확인됐다 | 아래 "삭제 확정" SQL. 행은 남기고 노출만 영구히 끊는다 |
| 이의제기가 받아들여졌다 | 관리자 페이지에서 **블라인드 해제** |
| 아직 판단 중이다 | 그대로 두고, 왜 미뤘는지를 작업 대장에 남긴다 |

어느 쪽이든 판단과 근거를 작업 대장에 남긴다. 이의제기에 답하려면 그 기록이 필요하다.

## 관리자 페이지를 쓸 수 없을 때 (대시보드 SQL)

아래는 대체 수단이다. **이 경로로 하면 접속기록이 남지 않고 재집계도 돌지 않는다** —
`docs/admin-access-log.md` 운영 규칙 2번(대시보드 작업은 작업 대장에 남긴다)이 그대로 적용되고,
3번 재집계를 반드시 손으로 불러야 한다.

### ⚠️ SQL 로 할 때는 두 단계다. 2번을 잊으면 집계가 어긋난다

리뷰를 가려도 `store_size_status`는 저절로 다시 계산되지 않는다.
재집계는 리뷰 **등록**과 **삭제**에서만 돌고, DB를 직접 고치는 건 어느 쪽도 아니다.

1번만 하고 끝내면 **목록에서는 사라진 리뷰가 동반 가능 점수에는 그대로 남는다.**
가린 의미가 없어지고, 그 상태는 다음에 누군가 그 가게에 리뷰를 쓸 때까지 이어진다.
(관리자 페이지로 하면 재집계가 같은 트랜잭션에서 돌아 이 문제가 없다.)

### 1. 신고 내용 확인

```sql
SELECT r.review_id, r.content, r.blind_status, count(rr.report_id) AS reports
  FROM reviews r
  LEFT JOIN review_report rr ON rr.review_id = r.review_id
 GROUP BY r.review_id
 HAVING count(rr.report_id) > 0
 ORDER BY reports DESC;
```

사유까지 보려면:

```sql
SELECT reason, created_at FROM review_report
 WHERE review_id = '<review_id>' ORDER BY created_at;
```

### 2. 상태 변경

가게 id를 먼저 챙겨둔다. 3번에서 필요하다.

```sql
SELECT store_id FROM reviews WHERE review_id = '<review_id>';
```

**임시조치(판단 보류, 되돌릴 수 있음)**

```sql
UPDATE reviews
   SET blind_status = 'BLINDED', blinded_at = now()
 WHERE review_id = '<review_id>';
```

**삭제 확정(행은 남긴다 — 이의제기에 답하려면 이력이 필요하다)**

```sql
UPDATE reviews
   SET blind_status = 'DELETED', blinded_at = now()
 WHERE review_id = '<review_id>';
```

**해제(이의제기가 받아들여진 경우)**

```sql
UPDATE reviews
   SET blind_status = 'NORMAL', blinded_at = NULL
 WHERE review_id = '<review_id>';
```

### 3. 재집계 — 빼먹지 말 것

```
POST /internal/stores/{storeId}/recompute
Authorization: Bearer <accessToken>
```

응답에 크기별 점수·건수가 들어 있다. `countedReviews`가 집계에 실제로 쓰인
리뷰 수이니, 가린 만큼 줄었는지 확인한다.

```json
{
  "storeId": "...",
  "storeName": "...",
  "countedReviews": 3,
  "sizes": [
    { "size": "SMALL_MEDIUM", "status": "POSSIBLE",
      "possibleScore": 2.7, "impossibleScore": 0.0,
      "possibleCount": 3, "impossibleCount": 0, "lastVerifiedAt": "..." }
  ]
}
```

해제(NORMAL 복귀)할 때도 똑같이 불러야 점수가 돌아온다.

재집계는 AI 임베딩 갱신(`POST /internal/embedding/refresh`)도 함께 알린다 —
가려진 리뷰의 문장이 임베딩에 남아 있으면 화면에서 지운 표현으로 그 가게가 검색된다.
비동기라 응답을 기다리지 않고, 실패해도 재집계는 성공으로 끝난다(서버 로그에만 남는다).
`demo=true`로 부를 때는 알리지 않는다 — 목 데이터의 임베딩은 AI팀이 직접 채운다.

## 목 리뷰를 DB에 직접 넣었을 때

리뷰를 SQL로 넣으면 등록 경로를 타지 않아 재집계가 돌지 않는다.
가게마다 부르지 말고 한 번에 끝낸다.

```
POST /internal/stores/recompute-all?demo=true
Authorization: Bearer <accessToken>
```

`demo`는 어느 쪽 리뷰로 집계할지 정한다. 생략하면 `false`(실제 리뷰)다.
목 리뷰는 `is_demo = true`로 들어가므로 **`demo=true`를 빼먹으면 점수가 0으로 덮인다.**

응답에 `total` / `succeeded` / `failed` / `elapsedMs` / `failures[]`가 담긴다.
한 가게가 실패해도 나머지는 끝까지 돌고, 실패한 가게 id가 `failures`에 남는다.

### ⚠️ 시연용과 실제용은 같은 자리를 쓴다

`store_size_status`는 (가게, 크기)당 한 행뿐이라 두 벌을 담지 못한다.
`demo=true`로 돌리면 목 리뷰로 계산한 값이 실제 리뷰 기준 점수를 **덮어쓴다.**

실제 리뷰가 0건인 동안은 문제가 없다. 실제 리뷰가 쌓이기 시작하면
`store_size_status`에 `is_demo`를 두고 행을 나눠야 한다.

## 알아둘 것

- **`sizes`가 빈 배열이면** 그 가게에 `store_size_status` 행이 없다는 뜻이다.
  집계 대상 자체가 없으니 `db/seed-store-size-status.sql`을 먼저 돌린다.
- **사진은 남는다.** 블라인드는 노출만 끊고 Supabase 파일은 지우지 않는다.
  사진 자체가 문제라면 스토리지에서 따로 지운다. 관리자 페이지의 신고 상세에서 그 사진을 볼 수 있다.
- **본인 삭제는 다르다.** 사용자가 `DELETE /reviews/{reviewId}`로 지우면
  행과 사진이 실제로 사라지고 재집계도, 임베딩 갱신도 그 자리에서 돈다. 이 런북은 신고 처리 전용이다.
- **탈퇴도 실삭제다.** 개인정보 처리방침에 탈퇴 시 삭제로 명시했으므로
  블라인드가 아니라 행을 지운다.
- **회원을 내보내는 것은 이 런북이 아니다.** 약관 12조를 위반해 이용계약을 해지할 때는
  관리자 페이지의 `회원 상세 → 이용계약 해지`를 쓴다(`docs/admin-access-log.md` "10. 이용계약 해지").
  계정과 리뷰·저장·도장이 함께 지워지고, 같은 이메일은 30일간 재가입이 막힌다(약관 14조 5항).
  대시보드에서 `users` 행을 직접 지우지 않는다 — 기록이 남지 않고 FK 에 걸려 실패한다.
  리뷰 하나만 문제라면 계정을 건드리지 말고 관리자 페이지의 신고 화면에서 블라인드한다.
- **신고자는 화면에 나오지 않는다.** 사유와 일시만 보여준다. 판단에 필요하지 않고, 꺼내면 그 화면이
  처리하는 정보주체가 여러 명으로 늘어난다(`docs/admin-access-log.md` "11. 리뷰 신고와 임시조치").

## 권한

현재 `/internal/**`은 **로그인만 하면 누구나** 부를 수 있다.
권한이 `ROLE_USER` 하나뿐이라 관리자 구분이 없다.

재집계는 저장된 리뷰를 다시 세는 것이라 값을 조작하지는 못하지만,
`ROLE_ADMIN`이 생기면 `/admin` 아래로 옮기고 조여야 한다.
`InternalController`에 TODO로 남겨뒀다.
