# 새 Supabase 프로젝트에 스키마 올리기

개발용/운영용 프로젝트를 나눌 때, 빈 프로젝트를 지금 운영과 같은 모양으로 만드는 절차다.

## ⚠️ 스키마의 원본은 SQL 파일이 아니라 엔티티다

`db/*.sql` 은 전체 스키마를 담고 있지 않다. `users` 나 `reviews` 의 `CREATE TABLE` 은
어느 파일에도 없다 — 테이블과 컬럼은 `ddl-auto: update` 가 엔티티를 보고 만든다.
(`schema-review-verified.sql:14` 등 여러 파일이 이 전제를 명시한다.)

SQL 파일에는 `update` 가 못 하는 것만 들어 있다:

- 확장(postgis, pgvector) — Hibernate 가 만들지 못한다
- 인덱스 — `update` 는 컬럼만 만들고 인덱스는 만들지 않는다
- CHECK 제약 — 관리자가 손으로 값을 바꾸는 자리를 DB 가 막아 준다
- PK 교체와 값 채우기 — 기존 DB 를 옮길 때만 필요한 것들

그래서 순서의 뼈대는 **확장 → 앱 한 번 기동 → 나머지 SQL** 이다.
이 순서를 뒤집으면 0번에서 테이블 생성이 통째로 실패한다.

---

## 0. 확장 설치 — 앱을 띄우기 **전에**

```sql
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
```

`stores.geog` 가 `geography(Point,4326)` 라(`entity/Store.java:76`) postgis 가 없으면
Hibernate 의 `stores` 생성이 실패하고, 그 뒤 FK 로 엮인 테이블이 줄줄이 안 만들어진다.

Supabase 대시보드 > Database > Extensions 에서 켜도 결과는 같다.

## 1. 앱을 한 번 띄운다 (`ddl-auto: update`)

새 DB 를 가리키게 한 뒤 개발 프로파일로 띄운다.

```bash
DEV_DB_URL=... DEV_DB_USERNAME=... DEV_DB_PASSWORD=... \
DEV_SUPABASE_URL=... DEV_SUPABASE_SERVICE_KEY=... \
./gradlew bootRun
```

여기서 테이블 14개가 만들어진다:

```
users              user_agreements    withdrawn_email
stores             store_size_status  store_tags
reviews            review_tags        review_report
passports          saved_stores       saved_courses
saved_course_stores                   saved_course_purposes
```

`place_embedding` 만 엔티티가 없어 여기서 안 생긴다. 다음 단계에서 만든다.

기동이 끝나면 앱을 내려도 된다.

## 2. SQL 실행 순서

위에서부터 차례로 돌린다. 전부 여러 번 돌려도 안전하다.

| # | 파일 | 새 프로젝트에서 하는 일 |
|---|---|---|
| 1 | `schema-demo.sql` | `place_embedding` 테이블 + 인덱스 6개. `stores` 가 있어야 FK 가 걸리므로 1단계 뒤여야 한다 |
| 2 | `schema-review-report.sql` | `ck_reviews_blind_status` CHECK 제약, `review_report` unique 제약, 인덱스 3개 |
| 3 | `schema-review-verified.sql` | 인덱스 2개 |
| 4 | `schema-passport-demo.sql` | 인덱스 1개 |
| 5 | `schema-saved-demo.sql` | 인덱스 2개 |
| 6 | `schema-withdrawn-email.sql` | `ck_withdrawn_email_reason` CHECK 제약 + 인덱스 1개 |
| 7 | **(데이터) `stores` 이관** | 아래 3번 |
| 8 | `seed-store-size-status.sql` | 가게 × 크기 2 × 시연여부 2 = 가게당 4행. `stores` 가 채워진 뒤여야 한다 |
| 9 | `schema-store-display-address.sql` | `display_address` 값 채우기(수동). 아래 4번 |

1~6번의 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 는 새 DB 에서 전부 헛돈다 —
그 컬럼은 이미 1단계에서 엔티티를 보고 만들어졌다. CHECK 제약과 인덱스 때문에 돌리는 것이다.

### 안 돌려도 되는 파일

| 파일 | 이유 |
|---|---|
| `drop-saved-course-route.sql` | 지우려는 `walk_path` 등이 새 DB 에는 애초에 없다 |
| `schema-store-size-status-demo.sql` | PK 교체는 필요 없고(아래), 시연 행 INSERT 는 8번이 양쪽 축을 다 만들어 준다 |
| `schema-store-aggregate-demo.sql` | PK 교체는 필요 없고, `stores` 집계 컬럼 3개는 새 DB 에 없다 |

PK 교체가 필요 없는 이유는 엔티티가 이미 `is_demo` 를 `@Id` 로 갖고 있어서다 —
`StoreSizeStatusId.java:20-23`, `StoreTagCountId.java:17-20`, `SavedStoreId.java:20-23`.
`update` 가 처음부터 올바른 복합 PK 로 만든다. 옮겨 온 DB 에서만 필요한 파일들이다.

### 중복 인덱스 하나

2번이 만드는 `idx_reviews_store_visible` 은 3번이 만드는 `idx_reviews_store_verified` 가
대신하는 것이다(`schema-review-verified.sql:30` 의 설명). 새 DB 에서는 둘 다 생기므로
쓰지 않는 쪽을 지워도 된다.

```sql
DROP INDEX IF EXISTS idx_reviews_store_visible;
```

## 3. `stores` 데이터 이관

**가게를 만드는 코드 경로가 없다.** `storeRepository.save(...)` 호출이 애플리케이션
전체에 한 건도 없다 — 가게는 외부에서 넣은 데이터다. 새 프로젝트에 그냥 두면
가게가 0건이라 지도·검색·코스 추천이 전부 빈 응답을 준다.

기존 프로젝트에서 옮겨 온다. Supabase 대시보드의 테이블 CSV 내보내기/가져오기로도 되고,
psql 이 있으면 아래가 간단하다.

```bash
# 기존(운영) 프로젝트에서
pg_dump "<기존 연결문자열>" --data-only --table=stores > stores.sql

# 새 프로젝트로
psql "<새 연결문자열>" -f stores.sql
```

`geog` 컬럼이 섞여 있으므로 0번의 postgis 설치가 끝난 뒤여야 한다.

개발용 프로젝트라면 전부 옮길 필요는 없다. 서울 일부만 잘라 넣어도 개발에는 충분하다.

## 4. `display_address` 채우기

`schema-store-display-address.sql` 에는 값을 채우는 UPDATE 가 없다. 주소 형태가
제각각이라 일괄 변환을 일부러 넣지 않았다(파일의 설명 참고). 시연 모드에서
이 값이 비어 있으면 실제 주소가 아니라 `"주소 비공개"` 가 나가므로
(`entity/Store.java:151-158`), 급하지 않으면 비워 둔 채로 시작해도 된다.

## 5. (선택) 시연 데이터

목 리뷰를 옮겨 왔다면 두 가지가 더 필요하다. 리뷰를 SQL 로 직접 넣으면
등록 경로(`ReviewService.create`)를 타지 않아 도장도 집계도 생기지 않기 때문이다.

```
1) backfill-passport-demo.sql          도장(passports) 행을 채운다
2) 관리자 페이지 → 재집계 → 전체 재집계   점수·태그를 두 축 모두 다시 센다
```

2번은 `schema-access-log.sql` 이 이 프로젝트에 적용돼 있어야 한다 — 재집계가 접속기록을 남기고,
CHECK 제약에 `RECOMPUTE_ALL` 이 없으면 시작하지 않는다.

## 검증

```sql
-- 테이블 15개 (엔티티 14 + place_embedding).
-- postgis 가 만드는 spatial_ref_sys 는 세지 않는다.
SELECT count(*) FROM information_schema.tables
 WHERE table_schema = 'public' AND table_name <> 'spatial_ref_sys';

-- 확장
SELECT extname FROM pg_extension WHERE extname IN ('postgis', 'vector');

-- 복합 PK 가 is_demo 를 포함하는지
SELECT conrelid::regclass AS tbl, pg_get_constraintdef(oid)
  FROM pg_constraint
 WHERE contype = 'p'
   AND conrelid::regclass::text IN ('store_size_status', 'store_tags', 'saved_stores');

-- 가게당 4행인지
SELECT (SELECT count(*) FROM stores) * 4 AS expected,
       (SELECT count(*) FROM store_size_status) AS actual;
```

## 덧: `ddl-auto` 를 어느 쪽에 둘지

지금은 개발·운영이 같은 값(`update`)을 쓴다. 프로젝트를 나눈 뒤에는
운영만 `validate` 로 내려 두는 선택지가 생긴다 — 엔티티를 고친 채 배포했을 때
운영 스키마가 말없이 바뀌는 대신 배포가 실패한다.

다만 그렇게 하면 컬럼 추가를 사람이 먼저 SQL 로 해야 하므로, 팀이 그 절차를
감당할 수 있을 때 바꾸는 편이 낫다. 이번 분리 작업에서는 건드리지 않았다.
