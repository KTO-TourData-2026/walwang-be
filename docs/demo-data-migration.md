# 목 리뷰(is_demo=true) 운영 → 개발 이관

운영에서 탈퇴 API로 지우기 전에, 시연 데이터를 개발 프로젝트에 백업 겸 옮기는 절차다.

실행 순서는 **조사 → 사진 복사 → INSERT 생성/적용 → 검증 → (그 다음에) 운영 탈퇴** 다.
사진 복사가 탈퇴보다 앞이어야 하는 이유는 아래 4번에 적었다.

---

## 0. 먼저 확인할 것

### 삭제 금지 계정

> **실제 회원 `user_id` 는 이 저장소에 적지 않는다. 작업 시점에 운영 DB 에서 조회해서 쓴다.**
>
> 문서에 박아 두면 계정이 지워진 뒤에도 남아 틀린 판단 근거가 되고,
> 저장소를 볼 수 있는 사람 누구에게나 운영 회원 식별자가 노출된다.
> 조회한 id 는 SQL Editor 에서만 쓰고, 이 파일이나 커밋에 남기지 않는다.

보존할 계정이 있으면 작업 시점에 id 를 확인해 1-B 쿼리의 `<보존할 user_id>` 자리에 넣는다.
목 리뷰 작성자가 보존 대상에 포함되면 **탈퇴시키지 말고 먼저 상의할 것.**

예전에 이 자리에 적혀 있던 보존 계정 3건은 목 리뷰용 테스트 계정이었고, 운영에서 이미 삭제됐다.

```sql
SELECT user_id, email, nickname
  FROM users
 WHERE email IN ('<보존할 계정 이메일>');
```

### 탈퇴 API가 실제로 지우는 것

`UserWithdrawalService.withdraw()` 기준이다.

| 지워지는 것 | 코드 |
|---|---|
| 그 사람의 **모든** 리뷰 (is_demo 구분 없음) | `:92` |
| passports 전체 | `:84` |
| 낸 신고 + 자기 리뷰를 향한 신고 | `:87-91` |
| saved_stores / saved_courses / user_agreements | `:93-95` |
| **Storage 의 사진·도장 파일** | `:119-120` |
| users 행 | `:108` |

추가로 두 가지가 더 일어난다.

- **가게 점수 재집계**(`:101-104`) — 목 리뷰가 빠지면서 시연 축(`demo=true`) 등급이 UNKNOWN 으로 되돌아간다.
  운영에서 시연을 더 안 할 거면 의도한 결과지만, 모르고 있으면 "왜 시연 화면이 비었지"가 된다.
- **`withdrawn_email` 행 생성**(`:111`) — 이메일 해시가 30일 남는다. 사유가 `SELF` 라 재가입은 막지 않는다.

### 개발 DB 전제

`stores` 가 같은 `store_id` 로 이미 있어야 FK 가 걸린다. 개발 DB 에서:

```sql
SELECT count(*) FROM stores;
```

0 이 아니면 된다. 이관한 리뷰의 가게가 다 있는지는 6번 검증에서 다시 확인한다.

---

## 1. 조사 — 운영 SQL Editor 에서

### 1-A. 건수와 작성자 수

```sql
SELECT count(*)                  AS 리뷰수,
       count(DISTINCT user_id)   AS 작성자수
  FROM reviews
 WHERE is_demo;
```

### 1-B. 작성자가 누구인지 (더미인지 팀원인지 판단)

```sql
SELECT u.user_id,
       u.email,
       u.nickname,
       count(*) FILTER (WHERE r.is_demo)     AS 시연리뷰,
       count(*) FILTER (WHERE NOT r.is_demo) AS 실제리뷰,
       -- 0번에서 작업 시점에 조회한 id 를 넣는다. 보존할 계정이 없으면 이 줄을 false AS 보존대상 으로 바꾼다.
       -- 자리표시자를 그대로 두면 uuid 형식 오류로 멈춘다 — 의도다.
       u.user_id IN ('<보존할 user_id>') AS 보존대상
  FROM users u
  JOIN reviews r ON r.user_id = u.user_id
 WHERE u.user_id IN (SELECT user_id FROM reviews WHERE is_demo)
 GROUP BY u.user_id, u.email, u.nickname
 ORDER BY 시연리뷰 DESC;
```

읽는 법:

- `보존대상` 이 `true` → 그 계정은 탈퇴 금지.
- `실제리뷰` 가 0 보다 큼 → 탈퇴시키면 그 실제 리뷰도 함께 사라진다. 백업 범위를 넓히든지, 탈퇴 대신 목 리뷰만 SQL 로 지우든지 정해야 한다.
- `실제리뷰` 가 전부 0 이고 `보존대상` 이 전부 false → 순수 더미 계정이다. 그대로 진행해도 된다.

### 1-C. 사진 유무와 Storage 경로

```sql
-- 먼저 개수만
SELECT count(*) FILTER (WHERE photo_url IS NOT NULL) AS 사진있음,
       count(*)                                      AS 전체
  FROM reviews
 WHERE is_demo;
```

`사진있음` 이 0 이면 4번(사진 복사)을 통째로 건너뛴다.

0 이 아니면 경로 목록을 뽑는다. 공개 URL 에서 버킷 뒤 경로만 남긴다 —
`SupabaseProperties.publicUrl()` 이 `{url}/storage/v1/object/public/{bucket}/{path}` 로 만들기 때문이다.

```sql
SELECT regexp_replace(photo_url, '^.*/object/public/[^/]+/', '') AS path
  FROM reviews WHERE is_demo AND photo_url IS NOT NULL
UNION
SELECT regexp_replace(thumbnail_url, '^.*/object/public/[^/]+/', '')
  FROM reviews WHERE is_demo AND thumbnail_url IS NOT NULL
UNION
SELECT regexp_replace(p.stamp_url, '^.*/object/public/[^/]+/', '')
  FROM passports p JOIN reviews r USING (review_id)
 WHERE r.is_demo
   AND p.stamp_url IS NOT NULL
   AND p.stamp_url LIKE 'http%'   -- '/static/stamp/default-paw.png' 는 앱 내부 파일이라 제외
 ORDER BY 1;
```

### 1-D. 도장 행

```sql
SELECT p.status, count(*), count(p.stamp_url) AS url있음
  FROM passports p
  JOIN reviews r USING (review_id)
 WHERE r.is_demo
 GROUP BY p.status;
```

`backfill-passport-demo.sql` 로 채운 행이라면 전부 `FALLBACK` / `url있음 = 0` 으로 나온다.

---

## 2. 사진을 옮길지 정한다

`reviews.photo_url` 에는 **운영 프로젝트의 URL** 이 그대로 들어 있다. 그래서 선택지는 둘이다.

### 안 A — 파일도 옮긴다 (권장)

43건 규모면 파일 100개 안팎이라 몇 분이면 끝난다. 이관 후 개발에서 사진까지 보인다.
INSERT 를 만들 때 URL 의 프로젝트 ref 를 개발 것으로 **치환해야 한다**. 안 하면 운영 탈퇴 시점에
파일이 지워져 깨진 링크만 남는다.

### 안 B — 사진은 포기한다

`photo_url` / `thumbnail_url` 을 NULL 로 넣는다. 리뷰 사진은 원래 선택이라 스키마상 문제없다
(`Review.java:59-62`). 도장은 기본 발도장으로 맞춘다.

3번 SQL 에 두 안을 모두 실어 두었다. **하나만 골라서** 쓴다.

---

## 3. INSERT 생성 — 운영에서 돌려 결과를 복사

각 쿼리는 결과가 **한 칸(cell)** 에 나온다. 그 칸을 복사해 개발 SQL Editor 에 붙여넣는다.
순서대로 하나씩 한다 — FK 때문에 순서가 바뀌면 실패한다.

### 3-1. users

```sql
SELECT string_agg(
    format('INSERT INTO users (user_id, email, password, nickname) VALUES (%L,%L,%L,%L) ON CONFLICT (user_id) DO NOTHING;',
           u.user_id, u.email, u.password, u.nickname),
    E'\n' ORDER BY u.user_id)
  FROM users u
 WHERE u.user_id IN (SELECT DISTINCT user_id FROM reviews WHERE is_demo);
```

`password` 는 BCrypt 해시 그대로 옮긴다. 개발에서 그 계정으로 로그인해 볼 수 있어야 하기 때문이다.
로그인할 일이 없으면 해시 자리에 아무 값이나 넣어도 되지만, 그러면 그 계정은 못 쓴다.

`email` 과 `nickname` 에 unique 제약이 있다. 개발 DB 에 같은 주소로 가입해 둔 계정이 있으면
`ON CONFLICT (user_id)` 로는 안 걸러지고 unique 위반이 난다. 그때는 개발 쪽 계정을 지우고 다시 넣는다.

### 3-2. user_agreements

```sql
SELECT string_agg(
    format('INSERT INTO user_agreements (id, user_id, term_code, term_version, agreed, agreed_at) VALUES (%L,%L,%L,%L,%L,%L) ON CONFLICT (id) DO NOTHING;',
           a.id, a.user_id, a.term_code, a.term_version, a.agreed, a.agreed_at),
    E'\n' ORDER BY a.agreed_at)
  FROM user_agreements a
 WHERE a.user_id IN (SELECT DISTINCT user_id FROM reviews WHERE is_demo);
```

목 리뷰 작성자가 SQL 로 직접 만든 더미 계정이면 이 테이블에 행이 없을 수 있다.
결과가 비면(NULL) 그냥 넘어간다 — `user_agreements` 는 리뷰 FK 와 무관하다.

### 3-3. reviews

**안 A (사진도 옮김)** — `<PROD_REF>` / `<DEV_REF>` 를 실제 프로젝트 ref 로 바꾼 뒤 돌린다.

```sql
SELECT string_agg(
    format('INSERT INTO reviews (review_id, user_id, store_id, dog_allowed, dog_size, photo_url, thumbnail_url, content, created_at, is_demo, verified, blind_status, blinded_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (review_id) DO NOTHING;',
           r.review_id, r.user_id, r.store_id, r.dog_allowed, r.dog_size,
           replace(r.photo_url,     'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co'),
           replace(r.thumbnail_url, 'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co'),
           r.content, r.created_at, r.is_demo, r.verified, r.blind_status, r.blinded_at),
    E'\n' ORDER BY r.created_at)
  FROM reviews r
 WHERE r.is_demo;
```

**안 B (사진 포기)**

```sql
SELECT string_agg(
    format('INSERT INTO reviews (review_id, user_id, store_id, dog_allowed, dog_size, photo_url, thumbnail_url, content, created_at, is_demo, verified, blind_status, blinded_at) VALUES (%L,%L,%L,%L,%L,NULL,NULL,%L,%L,%L,%L,%L,%L) ON CONFLICT (review_id) DO NOTHING;',
           r.review_id, r.user_id, r.store_id, r.dog_allowed, r.dog_size,
           r.content, r.created_at, r.is_demo, r.verified, r.blind_status, r.blinded_at),
    E'\n' ORDER BY r.created_at)
  FROM reviews r
 WHERE r.is_demo;
```

`verified` 는 손대지 않는다. 목 리뷰는 `is_demo = true` 이면서 `verified = true` 라
시연 모드에서 모두에게 보여야 한다(`schema-review-verified.sql:10-12`).
이 값을 false 로 바꾸면 작성자 본인에게만 보이고 집계에서도 빠진다.

### 3-4. review_tags

```sql
SELECT string_agg(
    format('INSERT INTO review_tags (review_id, tag) VALUES (%L,%L);', t.review_id, t.tag),
    E'\n')
  FROM review_tags t
  JOIN reviews r USING (review_id)
 WHERE r.is_demo;
```

⚠️ 이 테이블은 `@ElementCollection` 이라 PK 가 없다. `ON CONFLICT` 를 쓸 수 없으므로
**두 번 돌리면 태그가 중복으로 쌓인다.** 한 번만 실행하고, 실수했으면 아래로 정리한다.

```sql
-- 중복 확인
SELECT review_id, tag, count(*) FROM review_tags GROUP BY 1,2 HAVING count(*) > 1;

-- 중복 제거 (ctid 로 한 벌만 남긴다)
DELETE FROM review_tags a
 USING review_tags b
 WHERE a.ctid > b.ctid AND a.review_id = b.review_id AND a.tag = b.tag;
```

### 3-5. passports

**안 A (사진도 옮김)**

```sql
SELECT string_agg(
    format('INSERT INTO passports (passport_id, user_id, review_id, store_id, stamp_url, status, is_demo, created_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (passport_id) DO NOTHING;',
           p.passport_id, p.user_id, p.review_id, p.store_id,
           replace(p.stamp_url, 'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co'),
           p.status, p.is_demo, p.created_at),
    E'\n' ORDER BY p.created_at)
  FROM passports p
  JOIN reviews r USING (review_id)
 WHERE r.is_demo;
```

**안 B (사진 포기)** — 도장을 기본 발도장으로 확정시킨다.

```sql
SELECT string_agg(
    format('INSERT INTO passports (passport_id, user_id, review_id, store_id, stamp_url, status, is_demo, created_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (passport_id) DO NOTHING;',
           p.passport_id, p.user_id, p.review_id, p.store_id,
           '/static/stamp/default-paw.png', 'FALLBACK', p.is_demo, p.created_at),
    E'\n' ORDER BY p.created_at)
  FROM passports p
  JOIN reviews r USING (review_id)
 WHERE r.is_demo;
```

`'/static/stamp/default-paw.png'` 는 `PawStampClient.DEFAULT_STAMP_URL` 값이다.
상태를 `PENDING` 으로 두면 안 된다 — 재시도 스케줄러가 오래된 PENDING 을 다시 집어
AI 세그멘테이션을 호출한다(`backfill-passport-demo.sql:16-21` 에 같은 이야기가 있다).

---

## 4. Storage 사진 복사 (안 A 를 골랐을 때만)

**반드시 운영 탈퇴 전에 한다.** 탈퇴하면 `UserWithdrawalService:119-120` 이 파일을 지운다.

버킷이 public 이라 다운로드는 인증이 필요 없고, 업로드만 개발 프로젝트의 `service_role` 키가 필요하다.
앱이 쓰는 것과 같은 엔드포인트다(`SupabaseStorageClient:36-45`).

1-C 의 경로 목록을 `paths.txt` 로 저장한 뒤(한 줄에 하나), 프로젝트 루트에서:

```powershell
$ErrorActionPreference = 'Stop'

$prodUrl = 'https://<PROD_REF>.supabase.co'
$devUrl  = 'https://<DEV_REF>.supabase.co'
$devKey  = '<개발 프로젝트 service_role 키>'
$bucket  = 'review-photos'

$ok = 0; $fail = @()
foreach ($p in Get-Content .\paths.txt) {
    if ([string]::IsNullOrWhiteSpace($p)) { continue }
    $path = $p.Trim()
    $tmp  = [System.IO.Path]::GetTempFileName()
    try {
        Invoke-WebRequest "$prodUrl/storage/v1/object/public/$bucket/$path" -OutFile $tmp
        $ct = if ($path -like '*.png') { 'image/png' } else { 'image/jpeg' }
        Invoke-WebRequest -Method Post "$devUrl/storage/v1/object/$bucket/$path" `
            -Headers @{ Authorization = "Bearer $devKey"; 'x-upsert' = 'true' } `
            -ContentType $ct -InFile $tmp | Out-Null
        $ok++
        Write-Host "OK   $path"
    } catch {
        $fail += $path
        Write-Host "FAIL $path  $($_.Exception.Message)" -ForegroundColor Red
    } finally {
        Remove-Item $tmp -ErrorAction SilentlyContinue
    }
}
Write-Host "`n성공 $ok / 실패 $($fail.Count)"
$fail | ForEach-Object { Write-Host "  $_" }
```

개발 프로젝트에 `review-photos` 버킷이 **Public 으로** 만들어져 있어야 한다.
아니면 업로드는 되지만 공개 URL 로 안 열린다.

---

## 5. 개발 DB 에 적용

3번에서 뽑은 것을 **이 순서로** 붙여넣어 실행한다.

```
1) users
2) user_agreements      (비어 있으면 건너뜀)
3) reviews
4) review_tags          ← 한 번만!
5) passports
```

`reviews` 가 `users` 와 `stores` 를, `passports` 가 `reviews` 를 참조하므로 순서가 곧 FK 순서다.

---

## 6. 검증 — 개발 DB 에서

```sql
-- 건수가 운영의 1-A 결과와 같아야 한다
SELECT count(*) AS 리뷰, count(DISTINCT user_id) AS 작성자
  FROM reviews WHERE is_demo;

-- 도장이 리뷰마다 하나씩
SELECT count(*) FROM passports p JOIN reviews r USING (review_id) WHERE r.is_demo;

-- 태그 중복이 없는지 (있으면 3-4 의 정리 쿼리)
SELECT count(*) FROM (
  SELECT review_id, tag FROM review_tags GROUP BY 1,2 HAVING count(*) > 1
) x;

-- 가게가 다 있는지. 0 이어야 한다
SELECT count(*) FROM reviews r
 WHERE r.is_demo AND NOT EXISTS (SELECT 1 FROM stores s WHERE s.store_id = r.store_id);

-- 사진 URL 이 개발 프로젝트를 가리키는지 (안 A). 0 이어야 한다
SELECT count(*) FROM reviews
 WHERE is_demo AND photo_url LIKE '%<PROD_REF>%';
```

마지막으로 시연 축 점수를 채운다. SQL 로 넣은 리뷰는 등록 경로를 타지 않아 집계가 비어 있다.

```
관리자 페이지 → 재집계 → 전체 재집계   (실제·시연 두 축을 함께 센다)
```

화면을 새로 고쳐 **마지막 실행**의 시연 축 실패가 0인지 본다(`docs/admin-runbook.md` "목 리뷰를 DB에 직접 넣었을 때").
그 다음 앱에서 `demo=true` 로 가게 상세와 여권을 열어 눈으로 확인한다.

---

## 7. 그 다음에 운영 탈퇴

6번까지 끝나고 **눈으로 확인한 뒤에** 운영에서 지운다.

- 0번에서 작업 시점에 조회한 보존 대상이 아닌지 다시 확인
- 1-B 의 `실제리뷰` 가 0 인지 다시 확인 — 0 이 아니면 그 리뷰도 함께 사라진다
- 탈퇴는 그 계정의 access token 으로 `DELETE /user/me` 를 호출한다
- 탈퇴 후 운영에서 시연 축 등급이 UNKNOWN 으로 돌아간다. 정상이다

목 리뷰만 지우고 계정은 남기고 싶다면 탈퇴 API 대신 SQL 로 지운다.
이때도 참조 순서를 지켜야 한다.

```sql
BEGIN;
DELETE FROM passports  WHERE review_id IN (SELECT review_id FROM reviews WHERE is_demo);
DELETE FROM review_report WHERE review_id IN (SELECT review_id FROM reviews WHERE is_demo);
DELETE FROM review_tags WHERE review_id IN (SELECT review_id FROM reviews WHERE is_demo);
DELETE FROM reviews    WHERE is_demo;
COMMIT;
```

이 경우 Storage 파일은 남는다. 앱이 지우는 경로를 타지 않기 때문이다 — 1-C 의 경로 목록으로 직접 지워야 한다.
지운 뒤에는 관리자 페이지 `재집계 → 전체 재집계`로 집계를 맞춘다.
목 리뷰가 모두 지워진 가게도 대상에 들어간다 — 전체 재집계는 리뷰가 있는 가게와 함께
**시연 축 점수·태그가 0이 아닌 가게**도 다시 세어, 리뷰가 없으면 행을 남긴 채 0으로 되돌린다.
끝난 뒤 남은 값이 없는지 확인한다.

```sql
SELECT count(*) FROM store_size_status
 WHERE is_demo AND (possible_count <> 0 OR impossible_count <> 0 OR last_verified_at IS NOT NULL);  -- 0
SELECT count(*) FROM store_tags WHERE is_demo AND count <> 0;                                        -- 0
```
