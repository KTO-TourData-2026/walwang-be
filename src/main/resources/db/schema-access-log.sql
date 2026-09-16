-- 관리자 페이지 접속기록.
-- 「개인정보의 안전성 확보조치 기준」 제8조의 접속기록 항목을 담는다.
--   계정 = operator / 접속일시 = accessed_at / 접속지 = ip
--   처리한 정보주체 = target_user_id / 수행업무 = action + detail
-- 설계 판단 근거는 docs/admin-access-log.md 에 있다.
--
-- ⚠️ 앱을 배포하기 "전에" 돌린다.
--    먼저 배포하면 ddl-auto: update 가 이 파일과 다른 모양으로 테이블을 만들고
--    (CHECK 제약 없음, identity 방식 다름), 아래 CREATE TABLE IF NOT EXISTS 는 건너뛰어진다.
--    인덱스와 트리거는 그래도 걸리지만 CHECK 제약은 빠진 채로 남는다.
--
-- 앱이 접속하는 DB 계정과 같은 계정으로 돌린다(Supabase SQL Editor 의 postgres).
-- 운영·개발 두 프로젝트에 각각 돌린다. 로컬은 개발 프로젝트를 보므로 개발에 먼저 돌려 확인한다.

CREATE TABLE IF NOT EXISTS access_log (
    log_id         BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    operator       VARCHAR(50)  NOT NULL,
    accessed_at    TIMESTAMPTZ  NOT NULL,
    ip             VARCHAR(45)  NOT NULL,   -- IPv6 문자열 최대 길이
    -- users FK 를 걸지 않는다. 탈퇴는 행을 실제로 지우므로, FK 가 있으면
    -- 탈퇴가 막히거나(RESTRICT) 접속기록이 함께 사라져(CASCADE) 1년 보관이 깨진다.
    target_user_id UUID,
    action         VARCHAR(30)  NOT NULL,
    detail         VARCHAR(500),

    -- AccessAction enum 과 똑같이 맞춘다. 값을 추가하면 여기도 고쳐야 INSERT 가 통과한다.
    -- 아래 ALTER 블록이 같은 목록을 다시 걸므로, 이미 있는 테이블에도 값이 따라간다.
    CONSTRAINT ck_access_log_action CHECK (action IN (
        'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'TOTP_FAILURE', 'LOGIN_LOCKED', 'LOGOUT',
        'LIST_USERS', 'SEARCH_USERS', 'VIEW_USER', 'VIEW_ACCESS_LOG', 'TERMINATE_USER',
        'VIEW_REPORTS', 'VIEW_REPORTED_REVIEW', 'BLIND_REVIEW', 'UNBLIND_REVIEW',
        'RECOMPUTE_STORE', 'RECOMPUTE_ALL'
    ))
);

-- ─────────────────────────────────────────────────────────────
-- action 목록 맞추기 (이미 테이블이 있는 DB 용)
--
-- 위 CREATE TABLE 은 테이블이 있으면 통째로 건너뛰므로, AccessAction 에 값을 추가했을 때
-- 제약이 옛 목록에 머문다. 그래서 제약만 따로 다시 건다. 몇 번 돌려도 결과가 같다.
--
-- ⚠️ 앱을 배포하기 "전에" 돌린다. 순서를 어기면 새 action 으로 기록하는 순간 INSERT 가
--    CHECK 에 걸린다. 조회 화면은 기록이 실패하면 화면이 열리지 않고, 이용계약 해지는
--    기록과 삭제가 한 트랜잭션이라 통째로 롤백된다 — 회원이 지워지는 일은 없지만 기능이 멈춘다.
-- ─────────────────────────────────────────────────────────────

ALTER TABLE access_log DROP CONSTRAINT IF EXISTS ck_access_log_action;
ALTER TABLE access_log ADD CONSTRAINT ck_access_log_action CHECK (action IN (
    'LOGIN_SUCCESS', 'LOGIN_FAILURE', 'TOTP_FAILURE', 'LOGIN_LOCKED', 'LOGOUT',
    'LIST_USERS', 'SEARCH_USERS', 'VIEW_USER', 'VIEW_ACCESS_LOG',
    -- 2026-09-14 추가. 관리자 페이지의 이용계약 해지(약관 14조 5항).
    'TERMINATE_USER',
    -- 2026-09-14 추가. 신고 조회와 리뷰 임시조치(약관 10조·11조).
    'VIEW_REPORTS', 'VIEW_REPORTED_REVIEW', 'BLIND_REVIEW', 'UNBLIND_REVIEW',
    -- 2026-09-16 추가. 점수 재집계를 /internal 에서 관리자 페이지로 옮겼다.
    'RECOMPUTE_STORE', 'RECOMPUTE_ALL'
));

-- 접속기록 열람(최신순)과 보관 기간 정리
CREATE INDEX IF NOT EXISTS idx_access_log_accessed_at ON access_log (accessed_at DESC);
-- "이 회원의 정보를 누가 봤는가"
CREATE INDEX IF NOT EXISTS idx_access_log_target_user ON access_log (target_user_id);


-- ─────────────────────────────────────────────────────────────
-- 위·변조 방지
--
--   UPDATE           : 항상 막는다.
--   DELETE           : 1년이 지난 행만 허용한다(보관 기간 정리).
--   TRUNCATE         : 항상 막는다.
--
-- 한계: DB 소유자/슈퍼유저는 트리거를 지우거나 끌 수 있다. 완전한 방어가 아니라
-- "조용히 고치는 것"을 막고, 지우려면 DDL 이 Supabase 로그에 남게 만드는 장치다.
-- 판단 근거는 docs/admin-access-log.md "위·변조 방지" 참고.
--
-- 보관 기간을 바꾸면 아래 interval 과 AccessLogRepository.deleteExpired 의 interval 을
-- 반드시 함께 바꾼다. 트리거 쪽이 더 길면 정리가 트리거에 막혀 통째로 실패한다.
-- ─────────────────────────────────────────────────────────────

CREATE OR REPLACE FUNCTION access_log_guard() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'UPDATE' THEN
        RAISE EXCEPTION 'access_log 는 수정할 수 없습니다 (log_id=%)', OLD.log_id;
    ELSIF TG_OP = 'DELETE' THEN
        IF OLD.accessed_at >= now() - interval '1 year' THEN
            RAISE EXCEPTION 'access_log 는 보관 기간(1년)이 지난 행만 지울 수 있습니다 (log_id=%)', OLD.log_id;
        END IF;
        RETURN OLD;
    ELSIF TG_OP = 'TRUNCATE' THEN
        RAISE EXCEPTION 'access_log 는 TRUNCATE 할 수 없습니다';
    END IF;
    RETURN NULL;
END;
$$;

DROP TRIGGER IF EXISTS trg_access_log_guard_row ON access_log;
CREATE TRIGGER trg_access_log_guard_row
    BEFORE UPDATE OR DELETE ON access_log
    FOR EACH ROW EXECUTE FUNCTION access_log_guard();

DROP TRIGGER IF EXISTS trg_access_log_guard_truncate ON access_log;
CREATE TRIGGER trg_access_log_guard_truncate
    BEFORE TRUNCATE ON access_log
    FOR EACH STATEMENT EXECUTE FUNCTION access_log_guard();


-- ─────────────────────────────────────────────────────────────
-- Supabase REST(PostgREST) 노출 차단
--
-- Supabase 는 public 스키마에 새로 생긴 테이블에 anon / authenticated 권한을 기본으로 준다.
-- 그대로 두면 anon 키만으로 REST API 에서 접속기록을 읽을 수 있다.
--
-- 운영·개발 프로젝트는 2026-09-13 에 public 스키마 전체와 기본 권한까지 회수했다
-- (docs/admin-access-log.md "Supabase REST 권한 회수 기록"). 그래도 이 블록은 남긴다 —
-- 스키마 전체 회수를 빼먹은 새 프로젝트에서도 접속기록만큼은 닫히게 하려는 것이다.
-- 앱은 DB 에 직접 접속하므로 이 권한이 필요 없다.
-- (Supabase 가 아닌 PostgreSQL 에는 두 롤이 없으므로 있을 때만 회수한다.)
-- ─────────────────────────────────────────────────────────────

DO
$$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
        REVOKE ALL ON access_log FROM anon;
    END IF;
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
        REVOKE ALL ON access_log FROM authenticated;
    END IF;
END;
$$;


-- ─────────────────────────────────────────────────────────────
-- 확인
--   SELECT tgname FROM pg_trigger WHERE tgrelid = 'access_log'::regclass AND NOT tgisinternal;
--   -- trg_access_log_guard_row, trg_access_log_guard_truncate 두 줄이 나와야 한다.
--
--   SELECT grantee, privilege_type FROM information_schema.role_table_grants
--    WHERE table_name = 'access_log';
--   -- anon, authenticated 가 없어야 한다.
--
--   SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'ck_access_log_action';
--   -- 목록에 TERMINATE_USER, BLIND_REVIEW, RECOMPUTE_ALL 이 있어야 한다. 없으면 위 ALTER 블록을 돌린다.
--
-- 재집계 이력 (사유가 detail 에 들어 있다. RECOMPUTE_ALL 은 실행 요청 시각이다)
--   SELECT accessed_at AT TIME ZONE 'Asia/Seoul' AS kst, operator, ip, action, detail
--     FROM access_log WHERE action IN ('RECOMPUTE_STORE', 'RECOMPUTE_ALL')
--    ORDER BY accessed_at DESC;
--
-- 리뷰 임시조치 이력 (사유가 detail 에 들어 있다)
--   SELECT accessed_at AT TIME ZONE 'Asia/Seoul' AS kst, operator, action, target_user_id, detail
--     FROM access_log WHERE action IN ('BLIND_REVIEW', 'UNBLIND_REVIEW')
--    ORDER BY accessed_at DESC;
--
-- 이용계약 해지 이력 (사유가 detail 에 들어 있다. 1년 보관)
--   SELECT accessed_at AT TIME ZONE 'Asia/Seoul' AS kst, operator, ip, target_user_id, detail
--     FROM access_log WHERE action = 'TERMINATE_USER' ORDER BY accessed_at DESC;
--
-- 보관 기간 정리 (앱의 AccessLogRetentionScheduler 가 매일 04:30 KST 에 같은 쿼리를 돈다)
--   DELETE FROM access_log WHERE accessed_at < now() - interval '1 year';
--
-- 월 1회 점검 (제8조) 예시
--   -- 한 달간 관리자별 수행업무
--   SELECT operator, action, count(*) FROM access_log
--    WHERE accessed_at >= now() - interval '1 month'
--    GROUP BY operator, action ORDER BY operator, action;
--
--   -- 로그인 실패 / OTP 실패 / 잠금
--   SELECT accessed_at, operator, ip, action FROM access_log
--    WHERE action IN ('LOGIN_FAILURE', 'TOTP_FAILURE', 'LOGIN_LOCKED')
--      AND accessed_at >= now() - interval '1 month'
--    ORDER BY accessed_at DESC;
--
--   -- 업무 시간 외(KST 22시~07시) 조회
--   SELECT accessed_at AT TIME ZONE 'Asia/Seoul' AS kst, operator, ip, action, target_user_id
--    FROM access_log
--    WHERE accessed_at >= now() - interval '1 month'
--      AND (extract(hour FROM accessed_at AT TIME ZONE 'Asia/Seoul') >= 22
--           OR extract(hour FROM accessed_at AT TIME ZONE 'Asia/Seoul') < 7)
--    ORDER BY accessed_at DESC;
--
-- 되돌리기 (테이블째 없애야 할 때만. 트리거가 있어서 DROP 전에 지워야 한다)
--   DROP TRIGGER trg_access_log_guard_row ON access_log;
--   DROP TRIGGER trg_access_log_guard_truncate ON access_log;
--   DROP TABLE access_log;
--   DROP FUNCTION access_log_guard();
