-- 여권 도장의 시연/실제 분리.
--
-- reviews 는 is_demo 로 갈리는데 passports 에는 그 축이 없었다.
-- 그래서 시연 모드를 꺼도 시연 중에 찍힌 도장이 그대로 보였다.
-- 이 파일은 passports 에 같은 축을 만들어 조회를 갈라준다.
--
-- ddl-auto: update 로도 컬럼은 생기지만 여기서 직접 돌리는 편이 낫다:
--   - is_demo 는 NOT NULL 인데 기존 행에 값이 없다. Hibernate 는 DEFAULT 를 붙이지 않아
--     NOT NULL 추가가 실패한다. DEFAULT false 와 함께 넣어야 기존 행이 채워진다.
--   - 인덱스는 update 가 만들지 않는다.
--
-- 여러 번 돌려도 안전하다.

-- 1) 컬럼 ------------------------------------------------------------
-- 기존 도장은 전부 실제 리뷰에서 나온 것이므로 false 가 맞다.
ALTER TABLE passports
    ADD COLUMN IF NOT EXISTS is_demo BOOLEAN NOT NULL DEFAULT false;

-- 2) 인덱스 ----------------------------------------------------------
-- 여권 목록과 "모은 도장 수" 가 둘 다 (user_id, is_demo) 로 읽는다.
-- 목록은 최신순이라 created_at 까지 인덱스에 넣어 정렬을 없앤다.
CREATE INDEX IF NOT EXISTS idx_passports_user_demo_created
    ON passports (user_id, is_demo, created_at DESC);

-- 확인
--   SELECT is_demo, count(*) FROM passports GROUP BY is_demo;
--   -> 기존 도장은 전부 is_demo = false 여야 한다
--
--   목 리뷰(is_demo = true)에는 여권이 없다. 의도된 것이고 백필하지 않는다.
--   아래는 0 이 나오는 게 정상이다.
--   SELECT count(*) FROM passports p
--     JOIN reviews r ON r.review_id = p.review_id
--    WHERE r.is_demo = true;
--
-- 되돌리기
--   DROP INDEX IF EXISTS idx_passports_user_demo_created;
--   ALTER TABLE passports DROP COLUMN IF EXISTS is_demo;
