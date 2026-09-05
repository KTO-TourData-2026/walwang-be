-- 동반 가능 집계의 시연/실제 분리.
--
-- store_size_status 는 (store_id, size) 당 한 행뿐이라 시연 집계와 실제 집계를
-- 함께 담지 못했다. demo=true 로 재집계하면 목 리뷰로 계산한 값이 실제 리뷰로 계산한
-- 값을 덮어써서, 시연 모드를 끈 화면에 목 데이터 기반 등급이 그대로 남았다.
-- place_embedding, saved_stores, passports 와 같은 축을 여기에도 만든다.
--
-- 이 파일은 컬럼 추가에서 끝나지 않는다. PK 를 바꿔야 한다:
--   (store_id, size) -> (store_id, size, is_demo)
-- 그래야 같은 가게·크기에 시연 행과 실제 행이 각각 하나씩 설 수 있다.
--
-- ddl-auto: update 로는 안 된다:
--   - is_demo 는 NOT NULL 인데 기존 행에 값이 없다. Hibernate 는 DEFAULT 를 붙이지 않아
--     NOT NULL 추가가 실패한다. DEFAULT false 와 함께 넣어야 기존 행이 채워진다.
--   - PK 교체(DROP CONSTRAINT 후 ADD)는 update 가 하지 않는다.
--   - 시연 쪽 행(is_demo = true)을 새로 만들어 주지도 않는다.
--
-- 여러 번 돌려도 안전하다.

BEGIN;

-- 1) 컬럼 ------------------------------------------------------------
-- 기존 집계는 전부 실제 리뷰로 계산된 것이므로 false 가 맞다.
ALTER TABLE store_size_status
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

-- 2) PK 교체 ---------------------------------------------------------
-- 제약 이름은 기본 규칙(<table>_pkey)을 따른다. 다르게 잡혀 있다면
--   SELECT conname FROM pg_constraint
--    WHERE conrelid = 'store_size_status'::regclass AND contype = 'p';
-- 로 확인해 아래 이름을 바꿔서 돌린다.
ALTER TABLE store_size_status DROP CONSTRAINT IF EXISTS store_size_status_pkey;
ALTER TABLE store_size_status
    ADD CONSTRAINT store_size_status_pkey PRIMARY KEY (store_id, size, is_demo);

-- 3) 시연 쪽 행 만들기 -------------------------------------------------
-- 실제 쪽 행을 그대로 복제하지 않고 0 으로 새로 만든다. 시연 집계는
-- 목 리뷰로 다시 세어 채울 값이라, 실제 값을 베껴 두면 재집계 전까지
-- 실제 등급이 시연 화면에 나온다.
INSERT INTO store_size_status (
    store_id, size, is_demo,
    possible_score, impossible_score,
    possible_count, impossible_count,
    last_verified_at, updated_at
)
SELECT
    s.store_id, d.size, true,
    0, 0,
    0, 0,
    NULL,   -- last_verified_at : 아직 확인된 적 없음
    now()
FROM stores s
CROSS JOIN (VALUES ('SMALL_MEDIUM'), ('LARGE')) AS d(size)
ON CONFLICT (store_id, size, is_demo) DO NOTHING;

COMMIT;

-- 4) 재집계 ----------------------------------------------------------
-- 시연 쪽 행은 여기까지 전부 0(UNKNOWN)이다. 목 리뷰가 있는 가게는
-- 관리자 재집계를 demo=true 로 한 번 돌려야 등급이 채워진다.
--   POST /internal/stores/{storeId}/recompute?demo=true
-- 실제 쪽 행은 값이 그대로 남아 있으므로 다시 돌릴 필요가 없다.

-- 확인
--   SELECT is_demo, count(*) FROM store_size_status GROUP BY is_demo;
--   -> 가게 수 × 2 가 양쪽에 하나씩. 두 값이 같아야 한다.
--
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'store_size_status'::regclass AND contype = 'p';
--   -> PRIMARY KEY (store_id, size, is_demo)
--
--   기존 집계가 실제 쪽에 그대로 남았는지:
--   SELECT count(*) FROM store_size_status
--    WHERE is_demo = false AND (possible_count > 0 OR impossible_count > 0);

-- 되돌리기
--   시연 쪽 행을 먼저 지워야 PK 를 되돌릴 수 있다 —
--   (store_id, size) 로 좁히면 같은 키가 둘씩 남아 제약 생성이 실패한다.
--
--   BEGIN;
--   DELETE FROM store_size_status WHERE is_demo = true;
--   ALTER TABLE store_size_status DROP CONSTRAINT IF EXISTS store_size_status_pkey;
--   ALTER TABLE store_size_status
--       ADD CONSTRAINT store_size_status_pkey PRIMARY KEY (store_id, size);
--   ALTER TABLE store_size_status DROP COLUMN IF EXISTS is_demo;
--   COMMIT;
--
--   실제 쪽 집계는 손대지 않으므로 되돌린 뒤에도 그대로 남는다.
