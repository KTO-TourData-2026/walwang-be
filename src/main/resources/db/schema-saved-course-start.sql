-- 저장 코스에 출발 좌표를 남긴다.
--
-- 추천은 요청 좌표(출발점)에서 가게를 이어 경로를 계산하는데, 저장할 때 그 좌표를
-- 버려서 조회하면 첫 구간이 빠진 채 다시 계산됐다 — 같은 코스가 거리부터 달랐다.
--
-- 출발점은 가게가 아니라 saved_course_stores(store_id FK)에는 넣을 수 없어 코스에 둔다.
--
-- ddl-auto: update 가 컬럼을 만들어 주지만, update 를 쓰지 않는 DB 에는 이 파일을 직접 돌린다.
-- 여러 번 돌려도 안전하다.
--
-- 새로 저장하는 코스는 서버가 첫 가게 좌표로 채운다(CourseService.save).
-- 기존 행은 null 로 둔다. 조회 때 첫 가게를 출발점으로 삼으므로(CourseService.startOf)
-- 채운 것과 결과가 같다.

ALTER TABLE saved_courses ADD COLUMN IF NOT EXISTS start_lat DOUBLE PRECISION;
ALTER TABLE saved_courses ADD COLUMN IF NOT EXISTS start_lng DOUBLE PRECISION;

-- 확인
--   SELECT column_name, data_type, is_nullable FROM information_schema.columns
--    WHERE table_name = 'saved_courses' AND column_name IN ('start_lat', 'start_lng');
--   -> 두 행, double precision, YES
--
-- 되돌리기
--   ALTER TABLE saved_courses DROP COLUMN IF EXISTS start_lng;
--   ALTER TABLE saved_courses DROP COLUMN IF EXISTS start_lat;
