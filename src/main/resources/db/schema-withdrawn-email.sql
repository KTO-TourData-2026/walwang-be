-- 탈퇴 이메일 블랙리스트.
-- 약관 4조 4항(약관 위반 해지 이력)에 따른 재가입 제한을 담는다.
--
-- 이메일 원문은 넣지 않는다 — 탈퇴하면 지운다고 해놓고 주소를 들고 있으면 지웠다고 할 수 없다.
-- 같은 주소로 다시 오는지만 알면 되므로 SHA-256 해시(페퍼 적용)로 충분하다.
-- 페퍼는 EMAIL_PEPPER 환경변수로만 주입한다. 이 파일에도, yaml 에도 값을 쓰지 않는다.

CREATE TABLE IF NOT EXISTS withdrawn_email (
    email_hash VARCHAR(64)  PRIMARY KEY,   -- SHA-256(pepper + 소문자 이메일), 16진수 64자
    reason     VARCHAR(20)  NOT NULL,      -- 'SELF' | 'TERMINATED'
                                           -- 탈퇴 경로는 TERMINATED 만 넣는다.
                                           -- SELF 는 예전 행에만 남아 있다(아래 정리 쿼리 참고).
    expires_at TIMESTAMP    NOT NULL,      -- 이 시각이 지나면 청소 대상
    created_at TIMESTAMP    NOT NULL,

    CONSTRAINT ck_withdrawn_email_reason
        CHECK (reason IN ('SELF', 'TERMINATED'))
);

-- 청소 스케줄러가 매일 만료된 행을 훑는다.
CREATE INDEX IF NOT EXISTS idx_withdrawn_email_expires
    ON withdrawn_email (expires_at);

-- 확인
--   SELECT reason, count(*) FROM withdrawn_email GROUP BY reason;
--
-- 예전에 쌓인 자진 탈퇴 행 확인 (이제는 만들지 않는다. 차단에는 쓰이지 않지만
-- 보관할 이유도 없으므로 남아 있으면 지운다. 해시라 누구인지는 알 수 없다)
--   SELECT count(*) AS self_rows,
--          min(created_at) AS oldest,
--          max(expires_at) AS last_expiry
--     FROM withdrawn_email
--    WHERE reason = 'SELF';
--
--   DELETE FROM withdrawn_email WHERE reason = 'SELF';
--
-- 해지 처리 (지금은 API 가 없어 직접 넣는다. 재가입이 30일 막힌다)
--   해시는 서버가 계산해야 한다 — 페퍼를 알아야 하므로 SQL 로는 만들 수 없다.
--   해지 API 가 생기기 전까지는 애플리케이션을 통해 넣어야 한다.
--
-- 되돌리기
--   DELETE FROM withdrawn_email WHERE email_hash = '...';
