-- 리뷰의 영수증 인증 여부.
--
-- 시연 모드에서는 OCR 인증을 건너뛰고 리뷰를 쓸 수 있다. 그렇게 들어온 리뷰는
-- 가보지 않은 가게에도 쓸 수 있으므로 실제 데이터에 섞이면 안 된다.
-- verified = false 는 "아직 아무도 확인하지 않은 리뷰"라는 뜻이고,
-- 읽는 쪽은 이 값을 blind_status 와 같은 자리에서 존중해야 한다:
--   - 가게 리뷰 목록·썸네일: 작성자 본인에게만 보인다
--   - 점수·태그 재집계: 아예 세지 않는다
--
-- is_demo 와는 축이 다르다. 목 리뷰 43건은 is_demo = true 이면서 verified = true 다 —
-- AI팀이 넣은 시연 데이터라 시연 모드에서 모두에게 보여야 한다.
-- 사용자가 시연 모드에서 직접 쓴 리뷰만 is_demo = true, verified = false 가 된다.
--
-- ddl-auto: update 로도 컬럼은 생기지만 여기서 직접 돌리는 편이 낫다:
--   - verified 는 NOT NULL 인데 기존 행에 값이 없다. Hibernate 는 DEFAULT 를 붙이지 않아
--     NOT NULL 추가가 실패한다. DEFAULT true 와 함께 넣어야 기존 행이 채워진다.
--   - 인덱스는 update 가 만들지 않는다.
--
-- 여러 번 돌려도 안전하다.

-- 1) 컬럼 ------------------------------------------------------------
-- 기존 리뷰는 전부 영수증 인증을 거쳤거나(실제) 시연용으로 넣은 것이므로 true 가 맞다.
ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS verified BOOLEAN NOT NULL DEFAULT true;

-- 2) 인덱스 ----------------------------------------------------------
-- 가게 리뷰 목록과 재집계가 (store_id, is_demo, verified) 로 읽는다.
-- 기존 idx_reviews_store_visible 을 대신한다 — 같은 축에 verified 가 하나 더 붙었다.
CREATE INDEX IF NOT EXISTS idx_reviews_store_verified
    ON reviews (store_id, is_demo, verified, created_at DESC)
    WHERE blind_status = 'NORMAL';

-- 내 리뷰 목록도 이번에 demo 축으로 갈라졌다.
CREATE INDEX IF NOT EXISTS idx_reviews_user_demo_created
    ON reviews (user_id, is_demo, created_at DESC)
    WHERE blind_status = 'NORMAL';

-- 확인
--   SELECT is_demo, verified, count(*) FROM reviews GROUP BY is_demo, verified;
--   -> 기존 리뷰는 전부 verified = true 여야 한다
--      (목 리뷰 43건은 is_demo = true / verified = true 로 남는다)
--
-- 되돌리기
--   DROP INDEX IF EXISTS idx_reviews_user_demo_created;
--   DROP INDEX IF EXISTS idx_reviews_store_verified;
--   ALTER TABLE reviews DROP COLUMN IF EXISTS verified;
