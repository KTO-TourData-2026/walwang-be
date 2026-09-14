-- 리뷰 신고 + 블라인드 상태.
-- 약관 11조(정보통신망법 44조의2)가 정한 임시조치 절차를 담는다.
--
-- ddl-auto: update 가 테이블과 컬럼을 만들 수는 있지만, 이 파일로 직접 돌리는 편이 낫다:
--   - blind_status 는 NOT NULL 인데 기존 리뷰에 값이 없다. Hibernate 는 DEFAULT 를 붙이지 않아
--     NOT NULL 추가가 실패한다. 아래처럼 채운 뒤 제약을 거는 순서가 필요하다.
--   - unique 제약과 인덱스 이름을 우리가 정해두면 나중에 찾아 지우기 쉽다.

-- 1) 리뷰 노출 상태 -------------------------------------------------
-- 먼저 DEFAULT 와 함께 넣어 기존 행이 전부 NORMAL 로 채워지게 한다.
ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS blind_status VARCHAR(20) NOT NULL DEFAULT 'NORMAL';

ALTER TABLE reviews
    ADD COLUMN IF NOT EXISTS blinded_at TIMESTAMP NULL;

-- 값 검증. 관리자가 DB 에서 손으로 바꾸는 자리라 오타를 DB 가 막아줘야 한다.
ALTER TABLE reviews DROP CONSTRAINT IF EXISTS ck_reviews_blind_status;
ALTER TABLE reviews
    ADD CONSTRAINT ck_reviews_blind_status
        CHECK (blind_status IN ('NORMAL', 'BLINDED', 'DELETED'));

-- 조회는 거의 항상 "가려지지 않은 것"만 찾는다. 부분 인덱스로 정상 리뷰만 담는다.
CREATE INDEX IF NOT EXISTS idx_reviews_store_visible
    ON reviews (store_id, is_demo, created_at DESC)
    WHERE blind_status = 'NORMAL';

CREATE INDEX IF NOT EXISTS idx_reviews_user_visible
    ON reviews (user_id, created_at DESC)
    WHERE blind_status = 'NORMAL';

-- 2) 신고 ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS review_report (
    report_id   UUID         PRIMARY KEY,
    review_id   UUID         NOT NULL,
    reporter_id UUID         NOT NULL,
    reason      VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP    NOT NULL,

    CONSTRAINT fk_review_report_review
        FOREIGN KEY (review_id)   REFERENCES reviews (review_id),
    CONSTRAINT fk_review_report_reporter
        FOREIGN KEY (reporter_id) REFERENCES users (user_id),

    -- 한 사람이 같은 리뷰를 여러 번 눌러 신고 수를 부풀리지 못하게 한다.
    CONSTRAINT uk_review_report_review_reporter
        UNIQUE (review_id, reporter_id)
);

-- 관리자가 "신고 많은 순"으로 훑는다.
CREATE INDEX IF NOT EXISTS idx_review_report_review
    ON review_report (review_id);

-- 확인
--   SELECT blind_status, count(*) FROM reviews GROUP BY blind_status;
--   -> 기존 리뷰는 전부 NORMAL 이어야 한다
--
-- 관리자 조치는 관리자 페이지에서 한다 (관리자 페이지 > 신고).
-- 기록이 남고 재집계까지 함께 돌아간다 — docs/admin-runbook.md 참고.
-- 아래 SQL 은 관리자 페이지를 쓸 수 없을 때의 대체 수단이다. 이 경로로 바꾸면
-- 접속기록이 남지 않고 store_size_status 재집계도 돌지 않으므로 반드시 수동으로 재집계해야 한다.
--   UPDATE reviews SET blind_status = 'BLINDED', blinded_at = now() WHERE review_id = '...';
--   UPDATE reviews SET blind_status = 'NORMAL',  blinded_at = NULL  WHERE review_id = '...';
--
-- blinded_at 에는 인덱스를 두지 않는다. 임시조치 만료를 찾는 조회
-- (blind_status = 'BLINDED' AND blinded_at < now() - interval '30 days', BlindExpiryScheduler)는
-- 하루에 한 번 돌고 가려진 리뷰 자체가 소수라 전체 훑기로 충분하다.
--
-- 임시조치가 30일을 넘긴 리뷰
--   SELECT review_id, blinded_at FROM reviews
--    WHERE blind_status = 'BLINDED' AND blinded_at < now() - interval '30 days'
--    ORDER BY blinded_at;
