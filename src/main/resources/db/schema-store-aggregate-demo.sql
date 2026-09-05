-- 가게 집계의 시연/실제 분리 — 나머지 두 곳.
--
-- schema-store-size-status-demo.sql 을 먼저 돌린 뒤에 이 파일을 돌린다.
-- 같은 재집계(recompute)가 세 곳에 쓰는데 축이 하나만 갈라져 있으면,
-- demo=true 로 재집계하는 순간 나머지 두 곳이 실제 값을 덮어쓴다.
--
-- 1) store_tags        : PK 에 is_demo 를 더한다 (store_size_status 와 같은 방식)
-- 2) stores            : 집계 컬럼 3개를 지운다 (축을 더하지 않고 없앤다)
--
-- 2번을 왜 늘리지 않고 지우는지는 아래 해당 절에 적어 두었다.
--
-- 여러 번 돌려도 안전하다.

-- ===================================================================
-- 1) store_tags
-- ===================================================================

BEGIN;

-- 기존 집계는 전부 실제 리뷰로 계산된 것이므로 false 가 맞다.
ALTER TABLE store_tags
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

-- 제약 이름이 다르면 아래로 확인해서 바꿔 돌린다.
--   SELECT conname FROM pg_constraint
--    WHERE conrelid = 'store_tags'::regclass AND contype = 'p';
ALTER TABLE store_tags DROP CONSTRAINT IF EXISTS store_tags_pkey;
ALTER TABLE store_tags
    ADD CONSTRAINT store_tags_pkey PRIMARY KEY (store_id, tag, is_demo);

COMMIT;

-- store_size_status 와 달리 시연 쪽 행을 미리 만들지 않는다.
-- 태그 행은 재집계가 필요할 때 그때그때 만들고(없으면 save), 행이 없으면
-- 태그가 안 나갈 뿐 화면이 깨지지 않는다. 반면 크기별 상태는 행이 없으면
-- sizeStatus 가 빈 배열이 되어 상세 화면이 아무것도 못 그린다.

-- ===================================================================
-- 2) stores 집계 컬럼 제거
-- ===================================================================
--
-- possible_count / impossible_count / last_verified_at 은 store_size_status 를
-- 크기별로 합치면 그대로 나오는 값이다. 축을 더하면 같은 수를 네 벌
-- (크기별 2 × 시연여부 2) 맞춰야 하는데, 읽는 곳은 가게 상세 응답 한 군데뿐이고
-- 그 자리엔 이미 크기별 행이 실려 있다. 그래서 늘리지 않고 없앤다.
--
-- 별도 집계 테이블로 빼지 않는 이유도 같다 — store_size_status 가 이미
-- 그 테이블이다. 하나 더 만들면 같은 수를 담은 테이블이 둘이 된다.
--
-- ⚠ 컬럼을 지우므로 되돌리려면 값을 다시 만들어야 한다. 파생값이라 복원은 되지만
--    (아래 되돌리기 참고) 원본이 사라지는 것은 맞으니 백업 후 실행하기를 권한다.
--    확인용:
--      SELECT store_id, possible_count, impossible_count, last_verified_at
--        FROM stores WHERE possible_count > 0 OR impossible_count > 0;

BEGIN;

ALTER TABLE stores DROP COLUMN IF EXISTS possible_count;
ALTER TABLE stores DROP COLUMN IF EXISTS impossible_count;
ALTER TABLE stores DROP COLUMN IF EXISTS last_verified_at;

COMMIT;

-- ===================================================================
-- 재집계
-- ===================================================================
-- 시연 쪽 태그는 아직 비어 있다. 목 리뷰가 있는 가게는 재집계를 한 번 돌려야
-- 채워진다. store_size_status 와 같은 호출이라 한 번에 해결된다.
--   POST /internal/stores/{storeId}/recompute?demo=true

-- 확인
--   SELECT is_demo, count(*) FROM store_tags GROUP BY is_demo;
--   -> 재집계 전에는 is_demo = false 만 있다
--
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'store_tags'::regclass AND contype = 'p';
--   -> PRIMARY KEY (store_id, tag, is_demo)
--
--   SELECT column_name FROM information_schema.columns
--    WHERE table_name = 'stores'
--      AND column_name IN ('possible_count', 'impossible_count', 'last_verified_at');
--   -> 0 행

-- 되돌리기
--   1) store_tags : 시연 행을 먼저 지워야 PK 를 되돌릴 수 있다 —
--      (store_id, tag) 로 좁히면 같은 키가 둘씩 남아 제약 생성이 실패한다.
--
--      BEGIN;
--      DELETE FROM store_tags WHERE is_demo = true;
--      ALTER TABLE store_tags DROP CONSTRAINT IF EXISTS store_tags_pkey;
--      ALTER TABLE store_tags
--          ADD CONSTRAINT store_tags_pkey PRIMARY KEY (store_id, tag);
--      ALTER TABLE store_tags DROP COLUMN IF EXISTS is_demo;
--      COMMIT;
--
--   2) stores : 컬럼을 되살린 뒤 store_size_status 의 실제 쪽 행에서 다시 만든다.
--      파생값이라 이 계산이 곧 원래 값이다.
--
--      BEGIN;
--      ALTER TABLE stores ADD COLUMN IF NOT EXISTS possible_count INTEGER NOT NULL DEFAULT 0;
--      ALTER TABLE stores ADD COLUMN IF NOT EXISTS impossible_count INTEGER NOT NULL DEFAULT 0;
--      ALTER TABLE stores ADD COLUMN IF NOT EXISTS last_verified_at TIMESTAMPTZ;
--
--      UPDATE stores s SET
--          possible_count   = agg.possible_count,
--          impossible_count = agg.impossible_count,
--          last_verified_at = agg.last_verified_at
--      FROM (
--          SELECT store_id,
--                 sum(possible_count)   AS possible_count,
--                 sum(impossible_count) AS impossible_count,
--                 max(last_verified_at) AS last_verified_at
--            FROM store_size_status
--           WHERE is_demo = false
--           GROUP BY store_id
--      ) agg
--      WHERE s.store_id = agg.store_id;
--      COMMIT;
