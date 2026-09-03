-- 저장 코스에서 티맵 유래 값을 지운다.
--
-- 티맵 약관상 얻은 데이터는 24시간을 넘겨 보관할 수 없다.
-- walk_path 는 티맵이 준 경로선이고,
-- total_distance / total_duration 도 추천 응답에서 온 티맵 수치가 그대로 저장된 값이다.
-- (추천 시 티맵이 실패했다면 직선 추정치가 들어갔지만, 어느 쪽인지 구분해 저장하지 않았다.)
--
-- 셋 다 조회 시점에 다시 계산하므로 컬럼을 없앤다.
--
-- ddl-auto: update 는 컬럼을 지우지 않으므로 이 파일을 직접 돌려야 한다.
-- 안 지우면 NOT NULL 컬럼이 남아 INSERT 가 실패한다.

ALTER TABLE saved_courses DROP COLUMN IF EXISTS walk_path;
ALTER TABLE saved_courses DROP COLUMN IF EXISTS total_distance;
ALTER TABLE saved_courses DROP COLUMN IF EXISTS total_duration;

-- 확인
--   SELECT column_name FROM information_schema.columns
--   WHERE table_name = 'saved_courses' ORDER BY ordinal_position;
--   -> course_id, user_id, title, dog_size, duration, description, created_at 만 남아야 한다
