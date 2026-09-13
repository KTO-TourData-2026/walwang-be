# 출시 전 운영 데이터 정리 (개발로 이관 후 삭제)

`stores` 를 제외한 운영 데이터를 개발 프로젝트로 옮긴 뒤, 운영을 빈 상태로 되돌린다.

**순서: 이관 → 사진 복사 → 검증 → 운영 삭제.** 되돌릴 수 없는 단계가 마지막에 오도록 짰다.
앞 단계를 건너뛰면 복구할 방법이 없다.

---

## 0. 시작 전 체크포인트

### ⛔ 보존할 계정이 있는지 확인

> **실제 회원 `user_id` 는 이 저장소에 적지 않는다. 작업 시점에 운영 DB 에서 조회해서 쓴다.**
>
> 문서에 박아 두면 계정이 지워진 뒤에도 남아 틀린 판단 근거가 되고,
> 저장소를 볼 수 있는 사람 누구에게나 운영 회원 식별자가 노출된다.
> 조회한 id 는 SQL Editor 에서만 쓰고, 이 파일이나 커밋에 남기지 않는다.

예전에 이 자리에 적혀 있던 보존 계정 3건은 목 리뷰용 테스트 계정이었고, 운영에서 이미 삭제됐다.

보존할 계정이 있으면 이메일 등으로 찾아 id 를 확인한다.

```sql
SELECT user_id, email, nickname
  FROM users
 WHERE email IN ('<보존할 계정 이메일>');
```

- 보존할 계정이 **있다** → 4-3(보존 계정을 남겨야 할 때의 변형)에 위에서 조회한 id 를 넣어 쓴다.
- **없다** → 그대로 진행한다.

### 대상 테이블과 의존 순서

요청에 `saved_course_purposes` 가 빠져 있었다. `SavedCourse.java:62-67` 의
`@ElementCollection` 테이블로, 코스의 목적이 들어간다. 빼면 코스 목적이 비고
운영 삭제 때 FK 에 걸려 `saved_courses` 삭제가 실패한다. 아래 순서에 넣었다.

| # | 테이블 | 참조하는 곳 |
|---|---|---|
| 1 | `users` | — |
| 2 | `user_agreements` | users |
| 3 | `reviews` | users, **stores** |
| 4 | `review_tags` | reviews |
| 5 | `passports` | users, reviews, **stores** |
| 6 | `review_report` | reviews, users |
| 7 | `saved_stores` | users, **stores** |
| 8 | `saved_courses` | users |
| 9 | `saved_course_purposes` | saved_courses |
| 10 | `saved_course_stores` | saved_courses, **stores** |
| 11 | `withdrawn_email` | — (FK 없음) |
| 12 | `store_size_status` | **stores** |
| 13 | `store_tags` | **stores** |
| 14 | `place_embedding` | **stores** (ON DELETE CASCADE) |

INSERT 는 이 순서, DELETE 는 정확히 역순이다. 굵게 표시한 `stores` 는 양쪽에 이미 있고 건드리지 않는다.

### 집계 3종은 복사하지 말고 다시 계산하는 쪽이 낫다

12~14번은 리뷰에서 파생된 값이라 복사보다 재계산이 확실하다. 다만 하나는 예외다.

| 테이블 | 권장 |
|---|---|
| `store_size_status` | 개발엔 이미 seed 로 28,608 행이 있다. **값이 0 이 아닌 행만** UPSERT 하거나, 리뷰 이관 후 재집계 |
| `store_tags` | 같은 재집계가 채운다. 복사 불필요 |
| `place_embedding` | **재생성 불가.** 개발엔 `ai.base-url` 이 없어 임베딩 스텁이 서고, 그 스텁은 갱신 시 아무것도 하지 않는다(`AiClientConfig.java:71-84`). 복사하거나 포기하거나 둘 중 하나 |

`place_embedding` 규모를 먼저 재 본다. `vector(1536)` 한 행이 텍스트로 약 20KB 라,
SQL Editor 한 칸에 붙여넣는 방식은 수십 행이 한계다.

```sql
SELECT count(*) AS 행수,
       pg_size_pretty(sum(length(embedding::text))) AS 텍스트크기
  FROM place_embedding;
```

수백 행 이상이면 SQL Editor 대신 `pg_dump` 를 쓴다(1-14 참고).
임베딩 없이도 코스 추천은 거리순 폴백으로 동작하므로, 개발에서 추천 품질을 볼 일이 없으면 포기해도 된다.

---

## 1. 이관 SQL 생성 — 운영 SQL Editor 에서

각 쿼리는 결과가 **한 칸(cell)** 에 나온다. 그 칸을 복사해 개발 SQL Editor 에 붙여 실행한다.
**위에서부터 순서대로** 한다 — FK 순서가 곧 이 순서다.

> 결과가 잘려 나오면 `LIMIT`/`OFFSET` 으로 나눠 뽑는다. 예:
> `... FROM reviews r ORDER BY r.created_at LIMIT 200 OFFSET 0` 을 바깥 서브쿼리로 감싼다.

### 1-1. users

```sql
SELECT string_agg(
    format('INSERT INTO users (user_id, email, password, nickname) VALUES (%L,%L,%L,%L) ON CONFLICT (user_id) DO NOTHING;',
           u.user_id, u.email, u.password, u.nickname),
    E'\n' ORDER BY u.user_id)
  FROM users u;
```

`email` 과 `nickname` 에 unique 제약이 있다. 개발에서 테스트로 가입해 둔 계정과 겹치면
`ON CONFLICT (user_id)` 로는 안 걸러지고 unique 위반이 난다. 그때는 개발 쪽 계정을 먼저 지운다.

```sql
-- 개발에서: 겹치는 계정 확인
SELECT user_id, email, nickname FROM users ORDER BY email;
```

### 1-2. user_agreements

```sql
SELECT string_agg(
    format('INSERT INTO user_agreements (id, user_id, term_code, term_version, agreed, agreed_at) VALUES (%L,%L,%L,%L,%L,%L) ON CONFLICT (id) DO NOTHING;',
           a.id, a.user_id, a.term_code, a.term_version, a.agreed, a.agreed_at),
    E'\n' ORDER BY a.agreed_at)
  FROM user_agreements a;
```

### 1-3. reviews

사진 URL 치환은 2번(사진 복사)에서 따로 한다. 여기서는 운영 URL 그대로 넣는다.

```sql
SELECT string_agg(
    format('INSERT INTO reviews (review_id, user_id, store_id, dog_allowed, dog_size, photo_url, thumbnail_url, content, created_at, is_demo, verified, blind_status, blinded_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (review_id) DO NOTHING;',
           r.review_id, r.user_id, r.store_id, r.dog_allowed, r.dog_size,
           r.photo_url, r.thumbnail_url, r.content, r.created_at,
           r.is_demo, r.verified, r.blind_status, r.blinded_at),
    E'\n' ORDER BY r.created_at)
  FROM reviews r;
```

`verified` 와 `blind_status` 는 손대지 않는다. 둘 다 조회·집계가 존중하는 값이라
바꾸면 개발에서 보이는 리뷰 집합이 운영과 달라진다.

### 1-4. review_tags

⚠️ **이 테이블만 PK 가 없다.** `@ElementCollection` 이라 Hibernate 가 PK 를 만들지 않는다.
`ON CONFLICT` 를 쓸 수 없으므로 **두 번 돌리면 태그가 그대로 중복된다.** 한 번만 실행할 것.

```sql
SELECT string_agg(
    format('INSERT INTO review_tags (review_id, tag) VALUES (%L,%L);', t.review_id, t.tag),
    E'\n')
  FROM review_tags t;
```

실수로 두 번 돌렸다면 개발에서 정리한다.

```sql
-- 중복 확인
SELECT review_id, tag, count(*) FROM review_tags GROUP BY 1,2 HAVING count(*) > 1;

-- 한 벌만 남기고 제거
DELETE FROM review_tags a
 USING review_tags b
 WHERE a.ctid > b.ctid AND a.review_id = b.review_id AND a.tag = b.tag;
```

### 1-5. passports

```sql
SELECT string_agg(
    format('INSERT INTO passports (passport_id, user_id, review_id, store_id, stamp_url, status, is_demo, created_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (passport_id) DO NOTHING;',
           p.passport_id, p.user_id, p.review_id, p.store_id,
           p.stamp_url, p.status, p.is_demo, p.created_at),
    E'\n' ORDER BY p.created_at)
  FROM passports p;
```

`status` 를 `PENDING` 인 채로 옮기면, 개발에서 앱을 띄웠을 때 재시도 스케줄러가
`created_at` 이 오래된 PENDING 을 집어 AI 세그멘테이션을 호출한다
(`backfill-passport-demo.sql:16-21` 에 같은 사정이 적혀 있다).
개발에 AI 서버가 없으면 스텁이 즉시 빈 값을 주고 FALLBACK 으로 확정되므로 실질 피해는 없지만,
신경 쓰이면 옮길 때 `'FALLBACK'` 으로 바꿔 넣는다.

```sql
-- PENDING 이 몇 건인지 (운영에서)
SELECT status, count(*) FROM passports GROUP BY status;
```

### 1-6. review_report

```sql
SELECT string_agg(
    format('INSERT INTO review_report (report_id, review_id, reporter_id, reason, created_at) VALUES (%L,%L,%L,%L,%L) ON CONFLICT (report_id) DO NOTHING;',
           rr.report_id, rr.review_id, rr.reporter_id, rr.reason, rr.created_at),
    E'\n' ORDER BY rr.created_at)
  FROM review_report rr;
```

### 1-7. saved_stores

```sql
SELECT string_agg(
    format('INSERT INTO saved_stores (user_id, store_id, is_demo, created_at) VALUES (%L,%L,%L,%L) ON CONFLICT (user_id, store_id, is_demo) DO NOTHING;',
           s.user_id, s.store_id, s.is_demo, s.created_at),
    E'\n' ORDER BY s.created_at)
  FROM saved_stores s;
```

### 1-8. saved_courses

```sql
SELECT string_agg(
    format('INSERT INTO saved_courses (course_id, user_id, title, dog_size, duration, description, is_demo, created_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (course_id) DO NOTHING;',
           c.course_id, c.user_id, c.title, c.dog_size, c.duration,
           c.description, c.is_demo, c.created_at),
    E'\n' ORDER BY c.created_at)
  FROM saved_courses c;
```

`walk_path` / `total_distance` / `total_duration` 는 없는 것이 맞다.
티맵 약관상 24시간 넘겨 보관할 수 없어 컬럼째 지웠다(`drop-saved-course-route.sql`).
운영 DB 에 아직 남아 있다면 그 파일을 먼저 돌린 뒤 이관한다.

### 1-9. saved_course_purposes

⚠️ 여기도 `@ElementCollection` 이라 **PK 가 없다.** 1-4 와 같은 주의가 적용된다.

```sql
SELECT string_agg(
    format('INSERT INTO saved_course_purposes (course_id, purpose) VALUES (%L,%L);',
           p.course_id, p.purpose),
    E'\n')
  FROM saved_course_purposes p;
```

### 1-10. saved_course_stores

```sql
SELECT string_agg(
    format('INSERT INTO saved_course_stores (course_id, arrival_order, store_id) VALUES (%L,%L,%L) ON CONFLICT (course_id, arrival_order) DO NOTHING;',
           cs.course_id, cs.arrival_order, cs.store_id),
    E'\n' ORDER BY cs.course_id, cs.arrival_order)
  FROM saved_course_stores cs;
```

### 1-11. withdrawn_email

FK 가 없어 순서와 무관하다.

```sql
SELECT string_agg(
    format('INSERT INTO withdrawn_email (email_hash, reason, expires_at, created_at) VALUES (%L,%L,%L,%L) ON CONFLICT (email_hash) DO NOTHING;',
           w.email_hash, w.reason, w.expires_at, w.created_at),
    E'\n' ORDER BY w.created_at)
  FROM withdrawn_email w;
```

이 값은 페퍼를 섞은 해시다(`WithdrawnEmailService:51`). 개발과 운영이 **같은 `EMAIL_PEPPER`** 를
쓰지 않으면 옮겨도 맞아떨어지지 않는다. 재가입 차단을 개발에서 시험할 게 아니면 건너뛰어도 된다.

### 1-12. store_size_status (값이 있는 행만 UPSERT)

개발엔 이미 seed 로 전 행이 0 으로 들어가 있다. 전부 복사하면 28,608 행이라 붙여넣기가 불가능하다.
집계가 실제로 쌓인 행만 덮어쓴다.

```sql
SELECT string_agg(
    format('INSERT INTO store_size_status (store_id, size, is_demo, possible_score, impossible_score, possible_count, impossible_count, last_verified_at, updated_at) VALUES (%L,%L,%L,%L,%L,%L,%L,%L,%L) ON CONFLICT (store_id, size, is_demo) DO UPDATE SET possible_score = EXCLUDED.possible_score, impossible_score = EXCLUDED.impossible_score, possible_count = EXCLUDED.possible_count, impossible_count = EXCLUDED.impossible_count, last_verified_at = EXCLUDED.last_verified_at, updated_at = EXCLUDED.updated_at;',
           s.store_id, s.size, s.is_demo, s.possible_score, s.impossible_score,
           s.possible_count, s.impossible_count, s.last_verified_at, s.updated_at),
    E'\n' ORDER BY s.store_id)
  FROM store_size_status s
 WHERE s.possible_count > 0 OR s.impossible_count > 0
    OR s.possible_score <> 0 OR s.impossible_score <> 0;
```

**더 간단한 대안:** 1-5 까지 끝낸 뒤 개발에서 재집계를 돌리면 이 테이블과 `store_tags` 가
리뷰로부터 다시 계산된다. 복사보다 어긋날 여지가 없다.

```
POST /internal/stores/recompute-all
POST /internal/stores/recompute-all?demo=true
```

### 1-13. store_tags

재집계를 돌릴 거면 건너뛴다.

```sql
SELECT string_agg(
    format('INSERT INTO store_tags (store_id, tag, is_demo, count) VALUES (%L,%L,%L,%L) ON CONFLICT (store_id, tag, is_demo) DO UPDATE SET count = EXCLUDED.count;',
           t.store_id, t.tag, t.is_demo, t.count),
    E'\n' ORDER BY t.store_id)
  FROM store_tags t;
```

`count` 는 예약어라 인용이 필요할 수 있다. 오류가 나면 컬럼 목록의 `count` 를 `"count"` 로 바꾼다.

### 1-14. place_embedding

0번에서 잰 크기가 수십 행 수준일 때만 이 방식을 쓴다.

```sql
SELECT string_agg(
    format('INSERT INTO place_embedding (place_id, is_demo, embedding, updated_at) VALUES (%L,%L,%L::vector,%L) ON CONFLICT (place_id, is_demo) DO UPDATE SET embedding = EXCLUDED.embedding, updated_at = EXCLUDED.updated_at;',
           e.place_id, e.is_demo, e.embedding::text, e.updated_at),
    E'\n' ORDER BY e.place_id)
  FROM place_embedding e;
```

**행이 많으면 `pg_dump` 를 쓴다.** 이쪽이 훨씬 빠르고 안전하다.

```bash
pg_dump "<운영 연결문자열>" --data-only --table=place_embedding > pe.sql
psql "<개발 연결문자열>" -f pe.sql
```

---

## 2. Storage 파일 복사

**반드시 3번(검증)과 4번(삭제)보다 먼저 한다.** 운영 행을 지우면 어떤 파일이 있었는지
알 방법이 사라지고, 파일 자체도 나중에 지울 근거를 잃는다.

### 2-1. 경로 목록 뽑기 (운영에서)

이 목록은 **복사에도 쓰고 4-3 의 파일 삭제에도 그대로 재사용한다.** 파일로 저장해 둘 것.

```sql
SELECT regexp_replace(photo_url, '^.*/object/public/[^/]+/', '') AS path
  FROM reviews WHERE photo_url IS NOT NULL
UNION
SELECT regexp_replace(thumbnail_url, '^.*/object/public/[^/]+/', '')
  FROM reviews WHERE thumbnail_url IS NOT NULL
UNION
SELECT regexp_replace(stamp_url, '^.*/object/public/[^/]+/', '')
  FROM passports
 WHERE stamp_url IS NOT NULL
   AND stamp_url LIKE 'http%'    -- '/static/stamp/default-paw.png' 는 앱 내부 파일
 ORDER BY 1;
```

경로 모양은 `SupabaseProperties.publicUrl()` 이 만드는
`{url}/storage/v1/object/public/{bucket}/{path}` 에서 버킷 뒤쪽이다
(`reviews/{storeId}/{uuid}-original.jpg`, `stamps/{userId}/{uuid}.png`).

결과를 프로젝트 루트에 `paths.txt` 로 저장한다(한 줄에 하나, 헤더 제거).

### 2-2. 복사 스크립트

버킷이 public 이라 다운로드는 인증이 필요 없고, 업로드만 개발 `service_role` 키가 필요하다.
앱이 쓰는 것과 같은 엔드포인트다(`SupabaseStorageClient:36-45`).

개발 프로젝트에 `review-photos` 버킷이 **Public 으로** 있어야 한다.

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
        $ok++; Write-Host "OK   $path"
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

실패가 0 이 아니면 4번으로 넘어가지 않는다. 원본이 아직 운영에 있으니 다시 돌리면 된다.

### 2-3. 개발 DB 의 URL 치환

복사가 끝난 뒤 **개발 SQL Editor 에서** 돌린다. 이걸 안 하면 운영 삭제 시점에
개발 쪽 사진이 전부 깨진다.

```sql
BEGIN;

UPDATE reviews
   SET photo_url     = replace(photo_url,     'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co'),
       thumbnail_url = replace(thumbnail_url, 'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co')
 WHERE photo_url LIKE '%<PROD_REF>%' OR thumbnail_url LIKE '%<PROD_REF>%';

UPDATE passports
   SET stamp_url = replace(stamp_url, 'https://<PROD_REF>.supabase.co', 'https://<DEV_REF>.supabase.co')
 WHERE stamp_url LIKE '%<PROD_REF>%';

COMMIT;
```

---

## 3. 검증 — 운영을 지우기 전에

### 3-1. 건수 대조

양쪽에서 각각 돌려 표를 비교한다. `stores` 는 비교 대상에서 뺀다(원래 양쪽이 다를 수 있다).

```sql
SELECT 'users' t, count(*) FROM users
UNION ALL SELECT 'user_agreements',      count(*) FROM user_agreements
UNION ALL SELECT 'reviews',              count(*) FROM reviews
UNION ALL SELECT 'review_tags',          count(*) FROM review_tags
UNION ALL SELECT 'passports',            count(*) FROM passports
UNION ALL SELECT 'review_report',        count(*) FROM review_report
UNION ALL SELECT 'saved_stores',         count(*) FROM saved_stores
UNION ALL SELECT 'saved_courses',        count(*) FROM saved_courses
UNION ALL SELECT 'saved_course_purposes',count(*) FROM saved_course_purposes
UNION ALL SELECT 'saved_course_stores',  count(*) FROM saved_course_stores
UNION ALL SELECT 'withdrawn_email',      count(*) FROM withdrawn_email
UNION ALL SELECT 'store_tags',           count(*) FROM store_tags
UNION ALL SELECT 'place_embedding',      count(*) FROM place_embedding
ORDER BY 1;
```

개발 쪽이 운영보다 **크면** 정상일 수 있다(개발에서 테스트로 만든 데이터가 섞임).
**작으면** 빠진 것이니 멈춘다.

`store_size_status` 는 양쪽 다 `가게수 × 4` 라 건수 비교가 무의미하다. 값으로 본다.

```sql
SELECT count(*) FROM store_size_status
 WHERE possible_count > 0 OR impossible_count > 0;
```

### 3-2. 무결성 (개발에서)

전부 0 이어야 한다.

```sql
SELECT 'review→user 끊김', count(*) FROM reviews r
  WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.user_id = r.user_id)
UNION ALL
SELECT 'review→store 끊김', count(*) FROM reviews r
  WHERE NOT EXISTS (SELECT 1 FROM stores s WHERE s.store_id = r.store_id)
UNION ALL
SELECT 'passport→review 끊김', count(*) FROM passports p
  WHERE NOT EXISTS (SELECT 1 FROM reviews r WHERE r.review_id = p.review_id)
UNION ALL
SELECT 'tag 중복', count(*) FROM (
  SELECT review_id, tag FROM review_tags GROUP BY 1,2 HAVING count(*) > 1) x
UNION ALL
SELECT 'purpose 중복', count(*) FROM (
  SELECT course_id, purpose FROM saved_course_purposes GROUP BY 1,2 HAVING count(*) > 1) y
UNION ALL
SELECT 'URL 이 아직 운영', count(*) FROM reviews
  WHERE photo_url LIKE '%<PROD_REF>%' OR thumbnail_url LIKE '%<PROD_REF>%';
```

### 3-3. 눈으로 확인

개발 DB 를 보는 앱을 띄워서:

- 옮긴 계정으로 로그인 (비밀번호 해시를 그대로 옮겼으므로 운영 비밀번호가 그대로 통한다)
- 마이 화면에서 리뷰 수·도장 수·저장 수가 맞는지
- 가게 상세에서 리뷰와 사진이 보이는지
- 여권에서 도장 이미지가 열리는지 (2-3 의 치환이 됐는지 여기서 드러난다)

여기까지 통과해야 4번으로 간다. **4번부터는 되돌릴 수 없다.**

---

## 4. 운영 데이터 삭제

### 4-1. store_size_status 는 지우지 말고 0 으로 되돌린다

**판단: DELETE 하면 안 된다.**

`seed-store-size-status.sql` 헤더에 이유가 적혀 있다 — 행이 없으면 조회 응답의
`sizeStatus` 가 빈 배열이 되어 화면이 아무것도 그리지 못한다. 행이 있어야 UNKNOWN 이 나온다.
그리고 점수가 0 이면 `AvailabilityStatus.of()` 가 UNKNOWN 을 돌려주므로,
**0 으로 되돌리는 것이 곧 "리뷰가 하나도 없는 새 가게" 상태**다.

지웠다가 다시 seed 를 돌려도 결과는 같지만, UPDATE 한 번이 더 간단하고 중간에
빈 구간이 생기지 않는다.

`store_tags` 와 `place_embedding` 은 반대다. 둘 다 "있으면 쓰고 없으면 없는 대로" 동작하는
파생 데이터라 DELETE 가 맞다. 태그는 집계가 없으면 안 보이고, 임베딩이 없으면 추천이
거리순 폴백으로 내려간다.

### 4-2. 삭제 SQL (FK 역순)

한 트랜잭션으로 묶는다. 중간에 실패하면 통째로 되돌아간다.

```sql
BEGIN;

-- 14~12: 가게에 붙은 파생 데이터
DELETE FROM place_embedding;
DELETE FROM store_tags;

UPDATE store_size_status
   SET possible_score   = 0,
       impossible_score = 0,
       possible_count   = 0,
       impossible_count = 0,
       last_verified_at = NULL,
       updated_at       = now();

-- 11: 독립
DELETE FROM withdrawn_email;

-- 10~7: 저장한 것들
DELETE FROM saved_course_stores;
DELETE FROM saved_course_purposes;
DELETE FROM saved_courses;
DELETE FROM saved_stores;

-- 6~3: 리뷰 계열
DELETE FROM review_report;
DELETE FROM passports;
DELETE FROM review_tags;
DELETE FROM reviews;

-- 2~1: 계정
DELETE FROM user_agreements;
DELETE FROM users;

COMMIT;
```

커밋 전에 지워질 양을 확인하고 싶으면 `COMMIT;` 대신 `ROLLBACK;` 으로 한 번 돌려 본다.

### 4-3. 보존 계정을 남겨야 할 때의 변형

0번에서 남길 계정이 있다고 정했다면 마지막 두 줄을 아래로 바꾼다.
그 계정들의 리뷰·도장·저장도 함께 남겨야 하므로 앞쪽 DELETE 에도 조건이 붙는다.

```sql
BEGIN;

DELETE FROM place_embedding;
DELETE FROM store_tags;
UPDATE store_size_status
   SET possible_score = 0, impossible_score = 0,
       possible_count = 0, impossible_count = 0,
       last_verified_at = NULL, updated_at = now();
DELETE FROM withdrawn_email;

-- 남길 계정. 0번에서 작업 시점에 조회한 id 를 넣는다(이 파일에는 적어 두지 않는다).
-- 여러 건이면 ('<보존할 user_id>'), ('<보존할 user_id>') 처럼 잇는다.
-- 자리표시자를 그대로 두면 uuid 형식 오류로 멈춘다 — 의도다.
CREATE TEMP TABLE keep (user_id uuid PRIMARY KEY);
INSERT INTO keep VALUES
  ('<보존할 user_id>');

DELETE FROM saved_course_stores
 WHERE course_id IN (SELECT course_id FROM saved_courses
                      WHERE user_id NOT IN (SELECT user_id FROM keep));
DELETE FROM saved_course_purposes
 WHERE course_id IN (SELECT course_id FROM saved_courses
                      WHERE user_id NOT IN (SELECT user_id FROM keep));
DELETE FROM saved_courses WHERE user_id NOT IN (SELECT user_id FROM keep);
DELETE FROM saved_stores  WHERE user_id NOT IN (SELECT user_id FROM keep);

-- 신고는 신고자와 대상 리뷰 양쪽을 봐야 한다
DELETE FROM review_report
 WHERE reporter_id NOT IN (SELECT user_id FROM keep)
    OR review_id IN (SELECT review_id FROM reviews
                      WHERE user_id NOT IN (SELECT user_id FROM keep));
DELETE FROM passports  WHERE user_id NOT IN (SELECT user_id FROM keep);
DELETE FROM review_tags
 WHERE review_id IN (SELECT review_id FROM reviews
                      WHERE user_id NOT IN (SELECT user_id FROM keep));
DELETE FROM reviews    WHERE user_id NOT IN (SELECT user_id FROM keep);

DELETE FROM user_agreements WHERE user_id NOT IN (SELECT user_id FROM keep);
DELETE FROM users           WHERE user_id NOT IN (SELECT user_id FROM keep);

COMMIT;
```

남긴 계정에 리뷰가 있으면 그 가게 집계도 남아야 맞다. 4-2 의 `store_size_status` 초기화 대신
삭제 후 재집계를 돌린다.

```
POST /internal/stores/recompute-all
POST /internal/stores/recompute-all?demo=true
```

### 4-4. Storage 파일 삭제

SQL 로 행을 지우면 앱의 삭제 경로(`UserWithdrawalService:119-120`)를 타지 않아
**파일은 그대로 남는다.** 2-1 에서 저장해 둔 `paths.txt` 로 지운다.

```powershell
$ErrorActionPreference = 'Stop'

$prodUrl  = 'https://<PROD_REF>.supabase.co'
$prodKey  = '<운영 service_role 키>'
$bucket   = 'review-photos'

# 한 번에 100개씩 묶어 보낸다
$all = Get-Content .\paths.txt | Where-Object { $_.Trim() } | ForEach-Object { $_.Trim() }
for ($i = 0; $i -lt $all.Count; $i += 100) {
    $chunk = $all[$i..([Math]::Min($i + 99, $all.Count - 1))]
    $body  = @{ prefixes = $chunk } | ConvertTo-Json
    Invoke-WebRequest -Method Delete "$prodUrl/storage/v1/object/$bucket" `
        -Headers @{ Authorization = "Bearer $prodKey" } `
        -ContentType 'application/json' -Body $body | Out-Null
    Write-Host "삭제 $($i + $chunk.Count) / $($all.Count)"
}
```

경로 목록이 확실치 않거나 통째로 비우는 게 편하면, 대시보드에서
Storage > `review-photos` > `reviews/` 와 `stamps/` 폴더를 선택해 지워도 된다.
버킷 자체를 지웠다면 **같은 이름으로 Public 하게 다시 만들어야** 한다 —
앱이 `supabase.bucket` 이름으로 업로드한다.

---

## 5. 삭제 후 검증 — 운영에서

```sql
SELECT 'users' t, count(*) FROM users
UNION ALL SELECT 'user_agreements',      count(*) FROM user_agreements
UNION ALL SELECT 'reviews',              count(*) FROM reviews
UNION ALL SELECT 'review_tags',          count(*) FROM review_tags
UNION ALL SELECT 'passports',            count(*) FROM passports
UNION ALL SELECT 'review_report',        count(*) FROM review_report
UNION ALL SELECT 'saved_stores',         count(*) FROM saved_stores
UNION ALL SELECT 'saved_courses',        count(*) FROM saved_courses
UNION ALL SELECT 'saved_course_purposes',count(*) FROM saved_course_purposes
UNION ALL SELECT 'saved_course_stores',  count(*) FROM saved_course_stores
UNION ALL SELECT 'withdrawn_email',      count(*) FROM withdrawn_email
UNION ALL SELECT 'store_tags',           count(*) FROM store_tags
UNION ALL SELECT 'place_embedding',      count(*) FROM place_embedding
ORDER BY 1;
```

전부 0 (보존 계정을 남겼다면 그만큼)이어야 한다.

```sql
-- stores 는 그대로여야 한다
SELECT count(*) FROM stores;

-- store_size_status 는 행이 남고 값만 0
SELECT count(*) AS 전체,
       count(*) FILTER (WHERE possible_count > 0 OR impossible_count > 0) AS 값있음
  FROM store_size_status;
-- 전체 = 가게수 × 4, 값있음 = 0
```

마지막으로 앱에서 회원가입 → 리뷰 등록까지 한 번 돌려, 빈 운영 DB 가 정상 동작하는지 본다.
가게 상세의 동반 가능 등급이 UNKNOWN 으로 나오면 4-1 이 의도대로 된 것이다.
