-- 가입 이메일 인증 시각.
--
-- 개인정보 처리방침(11월 2일 시행)에 "인증 완료 사실과 인증 일시를 저장하고 탈퇴 시 삭제"로 적혀 있다.
-- 컬럼 하나로 둘 다 담는다 — 값이 있으면 인증을 마친 것이고, 그 값이 인증 일시다.
-- users 행에 붙어 있으므로 탈퇴(UserWithdrawalService → deleteById)하면 함께 지워진다.
--
-- 인증 코드·발송 횟수 등은 DB에 두지 않는다. Redis에 TTL로만 있다(EmailVerificationRepository).
--
-- ddl-auto: update 가 컬럼을 만들어 주지만, update 를 쓰지 않는 DB 에는 이 파일을 직접 돌린다.
-- 여러 번 돌려도 안전하다.
--
-- 기존 회원은 null 로 남는다. 인증 이전에 가입한 사람들이다.

ALTER TABLE users ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP;

-- 확인
--   SELECT column_name, data_type, is_nullable FROM information_schema.columns
--    WHERE table_name = 'users' AND column_name = 'email_verified_at';
--   -> 한 행, timestamp without time zone, YES
--
--   SELECT count(*) FILTER (WHERE email_verified_at IS NOT NULL) AS verified,
--          count(*) FILTER (WHERE email_verified_at IS NULL)     AS unverified
--     FROM users;
--
-- 되돌리기
--   ALTER TABLE users DROP COLUMN IF EXISTS email_verified_at;
