-- store_size_status 초기화.
--
-- 가게마다 크기별로 한 행씩 0으로 미리 만들어 둔다.
-- 그래야 리뷰가 없는 가게도 조회 시 UNKNOWN이 나온다 —
-- 행이 없으면 응답의 sizeStatus가 빈 배열이 되어 화면이 아무것도 못 그린다.
--
-- 실행 전 확인:
--   SELECT count(*) FROM store_size_status;   -- 0 이면 아래를 돌린다
--
-- 여러 번 돌려도 안전하다(ON CONFLICT DO NOTHING).

-- ---------------------------------------------------------------
-- 주의: 크기는 두 가지다
--
-- DogSize enum 이 SMALL_MEDIUM / LARGE 두 값뿐이라
-- small / medium / large 세 벌이 아니라 두 벌을 만든다.
-- 세 단계로 나누려면 enum 부터 바꿔야 하고, 그러면
-- reviews.dog_size 에 이미 쌓인 값도 함께 옮겨야 한다.
--
-- 등급(status) 컬럼은 이 테이블에 없다. 점수만 저장하고
-- 조회할 때 AvailabilityStatus.of(possible_score, impossible_score) 로 계산한다 —
-- 임계값을 바꿨을 때 저장된 데이터를 손대지 않고 곧바로 반영하기 위해서다.
-- 점수가 0이면 그 계산이 UNKNOWN 을 돌려주므로, 0 으로 넣는 것이 곧 unknown 이다.
-- ---------------------------------------------------------------

INSERT INTO store_size_status (
    store_id,
    size,
    possible_score,
    impossible_score,
    possible_count,
    impossible_count,
    last_verified_at,
    updated_at
)
SELECT
    s.store_id,
    d.size,
    0,      -- possible_score   : 0 이면 UNKNOWN 으로 계산된다
    0,      -- impossible_score
    0,      -- possible_count   : 화면에 그대로 보여주는 원본 건수
    0,      -- impossible_count
    NULL,   -- last_verified_at : 아직 확인된 적 없음
    now()   -- updated_at       : 이 행이 계산된 시각
FROM stores s
CROSS JOIN (VALUES ('SMALL_MEDIUM'), ('LARGE')) AS d(size)
ON CONFLICT (store_id, size) DO NOTHING;

-- 확인
--   SELECT size, count(*) FROM store_size_status GROUP BY size;
--   -> stores 건수 × 2 가 나와야 한다
