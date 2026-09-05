-- 목 리뷰에 대응하는 여권(도장) 백필.
--
-- ■ 배경
-- AI팀이 시연용 리뷰를 DB에 직접 INSERT 해서 ReviewService.create() 를 타지 않았다.
-- 도장 자리는 그 안의 PassportService.createFor() 가 만드는데, 그 경로를 건너뛴 탓에
-- passports 행이 하나도 없다. 그래서 시연 모드에서 여권이 비어 있다.
-- 이 파일은 그 빠진 행만 채운다. reviews 는 건드리지 않는다.
--
-- ■ 무엇이 들어가나
--   user_id, store_id, review_id  : 리뷰에서 그대로 가져온다
--   is_demo                       : true (리뷰의 is_demo 를 물려받는 규칙 그대로다)
--   status                        : 'FALLBACK'
--   stamp_url                     : NULL
--   created_at                    : 리뷰의 created_at
--
-- status 를 'PENDING' 이 아니라 'FALLBACK' 으로 넣는 이유가 있다.
-- PassportRepository.findTop20ByStatusAndCreatedAtBefore(PENDING, ...) 로 도는
-- 재시도 스케줄러가 오래된 PENDING 을 다시 집어간다. 백필 행은 created_at 이
-- 과거라 넣자마자 전부 조건에 걸려 AI 세그멘테이션 호출이 43번 나간다.
-- FALLBACK 은 "확정" 상태라 스케줄러가 건드리지 않는다.
--
-- stamp_url 을 NULL 로 두어도 화면은 비지 않는다.
-- PassportSummaryResponse / PassportDetailResponse 가 NULL 을 읽으면
-- PawStampClient.DEFAULT_STAMP_URL 로 채워 기본 발도장을 내보낸다.
-- (엔티티의 applyFallback 은 그 상수를 컬럼에 직접 써 넣지만, 응답이 어느 쪽이든
--  같은 값을 내므로 여기서는 NULL 로 둔다 — 정적 파일 경로가 바뀌어도 따라간다.)
--
-- ■ 선행 조건
-- schema-passport-demo.sql 이 먼저 적용돼 passports.is_demo 가 있어야 한다.
--
-- 여러 번 돌려도 안전하다. 이미 여권이 있는 리뷰는 매번 제외된다.

-- ------------------------------------------------------------------
-- 0) passport_id 생성 함수
--
-- 엔티티가 @GeneratedValue(strategy = UUID) 라 값을 자바에서 만든다.
-- 컬럼에 DEFAULT 가 없으므로 SQL 이 직접 채워야 한다.
-- gen_random_uuid() 는 PostgreSQL 13 부터 기본 내장이다.
-- 그보다 낮으면 아래 한 줄의 주석을 풀어라.
-- ------------------------------------------------------------------
-- CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ------------------------------------------------------------------
-- 1) 넣기 전에 대상 확인 (읽기만 한다)
-- ------------------------------------------------------------------
SELECT count(*) AS to_insert
  FROM reviews r
 WHERE r.is_demo = true
   AND r.dog_allowed = true
   AND r.blind_status = 'NORMAL'
   AND NOT EXISTS (SELECT 1 FROM passports p WHERE p.review_id = r.review_id);

-- 리뷰 시각이 실제로 흩어져 있는지. 한 점에 몰려 있으면 목록 정렬이 뒤죽박죽으로 보인다.
SELECT min(created_at) AS oldest,
       max(created_at) AS newest,
       count(DISTINCT created_at) AS distinct_times,
       count(*) AS total
  FROM reviews
 WHERE is_demo = true AND dog_allowed = true;

-- ------------------------------------------------------------------
-- 2) 백필
--
-- dog_allowed = false 는 "거절당했다" 는 기록이라 도장이 찍히면 안 된다.
-- blind_status 는 'NORMAL' 만 고른다 — 가려 둔 리뷰에 도장을 새로 만드는 것은
-- 가린 의미를 지운다. (실제 등록 경로도 NORMAL 로만 시작한다.)
-- NOT EXISTS 는 중복 방지다. passports.review_id 에 unique 가 걸려 있어
-- 이게 없으면 두 번째 실행이 제약 위반으로 죽는다.
-- ------------------------------------------------------------------
INSERT INTO passports (passport_id, user_id, store_id, review_id,
                       stamp_url, status, is_demo, created_at)
SELECT gen_random_uuid(),
       r.user_id,
       r.store_id,
       r.review_id,
       NULL,
       'FALLBACK',
       true,
       r.created_at
  FROM reviews r
 WHERE r.is_demo = true
   AND r.dog_allowed = true
   AND r.blind_status = 'NORMAL'
   AND NOT EXISTS (SELECT 1 FROM passports p WHERE p.review_id = r.review_id);

-- ------------------------------------------------------------------
-- 3) 실행 후 확인
-- ------------------------------------------------------------------

-- (1) 축이 갈렸는지. 기존 도장은 전부 false, 이번에 넣은 것만 true 여야 한다.
SELECT is_demo, status, count(*)
  FROM passports
 GROUP BY is_demo, status
 ORDER BY is_demo, status;

-- (2) 도장이 리뷰와 1:1 로 맞는지. 두 수가 같아야 한다.
SELECT (SELECT count(*) FROM reviews r
         WHERE r.is_demo = true AND r.dog_allowed = true AND r.blind_status = 'NORMAL')
       AS demo_reviews,
       (SELECT count(*) FROM passports WHERE is_demo = true)
       AS demo_passports;

-- (3) 도장의 축이 리뷰의 축과 어긋난 행이 있는지. 0 이어야 한다.
SELECT count(*) AS axis_mismatch
  FROM passports p
  JOIN reviews r ON r.review_id = p.review_id
 WHERE p.is_demo <> r.is_demo;

-- (4) 누구의 여권이 찼는지. 목 리뷰가 계정 하나로 들어갔다면 한 줄만 나온다.
--     그 계정으로 로그인해야 시연 모드에서 도장이 보인다.
SELECT u.user_id, u.nickname, count(*) AS stamps
  FROM passports p
  JOIN users u ON u.user_id = p.user_id
 WHERE p.is_demo = true
 GROUP BY u.user_id, u.nickname
 ORDER BY stamps DESC;

-- (5) 목록 화면이 실제로 무엇을 내보내는지. 최신순 상위 10개.
--     stamp_url 이 NULL 이어도 응답에는 기본 발도장이 채워진다.
SELECT p.created_at, p.status, p.stamp_url, s.name, s.display_name
  FROM passports p
  JOIN stores s ON s.store_id = p.store_id
 WHERE p.is_demo = true
 ORDER BY p.created_at DESC
 LIMIT 10;

-- ------------------------------------------------------------------
-- 되돌리기 — 이번 백필로 생긴 행만 지운다.
-- 시연 모드에서 사용자가 직접 쓴 리뷰의 도장은 verified = false 라 남는다.
-- ------------------------------------------------------------------
-- DELETE FROM passports p
--  USING reviews r
--  WHERE r.review_id = p.review_id
--    AND p.is_demo = true
--    AND p.status = 'FALLBACK'
--    AND p.stamp_url IS NULL
--    AND r.verified = true;
