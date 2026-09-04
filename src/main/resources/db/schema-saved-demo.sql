-- 저장한 가게·코스의 시연/실제 분리.
--
-- passports 와 같은 축을 saved_stores, saved_courses 에도 만든다.
-- 여권만 갈라두면 마이 화면 안에서 축이 섞인다 — 시연 중에 저장한 가게가
-- 시연 모드를 꺼도 목록에 남는다.
--
-- ⚠ saved_stores 는 PK 가 바뀐다. 아래 2번을 반드시 읽고 돌릴 것.
--
-- 여러 번 돌려도 안전하다.

-- 1) 컬럼 ------------------------------------------------------------
-- 기존에 저장한 것은 전부 실제 쪽이므로 false 가 맞다.
ALTER TABLE saved_stores
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE saved_courses
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

-- 2) saved_stores 의 PK 교체 -----------------------------------------
--
-- saved_stores 의 PK 는 (user_id, store_id) 였다. 같은 가게를 두 번 저장하지
-- 못하게 하려고 그렇게 뒀는데, 이제 한 사람이 같은 가게를 시연 쪽과 실제 쪽에
-- 각각 저장할 수 있어야 한다. is_demo 를 PK 에 넣지 않으면 시연 모드에서 저장한
-- 가게를 평소 모드에서 다시 저장할 수 없다 — 서버가 "이미 저장됨"으로 보고
-- 조용히 넘어가서, 그 가게는 평소 목록에 영영 나타나지 않는다.
--
-- place_embedding 이 (place_id, is_demo) 를 PK 로 둔 것과 같은 이유다.
-- "같은 대상이 시연용과 실제용으로 두 벌 필요하다".
--
-- saved_courses 는 course_id 하나가 PK 라 손댈 것이 없다.
--
-- PK 이름이 다를 수 있으니 먼저 확인할 것:
--   SELECT conname FROM pg_constraint
--    WHERE conrelid = 'saved_stores'::regclass AND contype = 'p';
ALTER TABLE saved_stores DROP CONSTRAINT IF EXISTS saved_stores_pkey;
ALTER TABLE saved_stores
    ADD CONSTRAINT saved_stores_pkey PRIMARY KEY (user_id, store_id, is_demo);

-- 3) 인덱스 ----------------------------------------------------------
-- 목록과 개수가 둘 다 (user_id, is_demo) 로 읽는다. 최신순이라 created_at 까지 넣어
-- 정렬을 없앤다. idx_passports_user_demo_created 와 같은 모양이다.
CREATE INDEX IF NOT EXISTS idx_saved_stores_user_demo_created
    ON saved_stores (user_id, is_demo, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_saved_courses_user_demo_created
    ON saved_courses (user_id, is_demo, created_at DESC);

-- 확인
--   SELECT is_demo, count(*) FROM saved_stores  GROUP BY is_demo;
--   SELECT is_demo, count(*) FROM saved_courses GROUP BY is_demo;
--   -> 기존 행은 전부 is_demo = false 여야 한다
--
--   PK 가 세 컬럼인지:
--   SELECT conname, pg_get_constraintdef(oid) FROM pg_constraint
--    WHERE conrelid = 'saved_stores'::regclass AND contype = 'p';
--
-- 되돌리기
--   DROP INDEX IF EXISTS idx_saved_courses_user_demo_created;
--   DROP INDEX IF EXISTS idx_saved_stores_user_demo_created;
--   ALTER TABLE saved_stores DROP CONSTRAINT IF EXISTS saved_stores_pkey;
--   ALTER TABLE saved_stores
--       ADD CONSTRAINT saved_stores_pkey PRIMARY KEY (user_id, store_id);
--   -- ↑ 시연 쪽 행이 남아 있으면 중복으로 실패한다. 먼저 지울 것:
--   --   DELETE FROM saved_stores WHERE is_demo = true;
--   ALTER TABLE saved_courses DROP COLUMN IF EXISTS is_demo;
--   ALTER TABLE saved_stores  DROP COLUMN IF EXISTS is_demo;
