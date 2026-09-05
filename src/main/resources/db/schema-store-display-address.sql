-- 주소의 시연용 마스킹.
--
-- 가게 이름은 display_name 으로 갈라 두었는데 주소는 그대로 나가고 있었다.
-- 목 리뷰가 붙은 채로 상세 번지까지 보이면 그 평판이 실제 가게에 그대로 붙는다.
--
-- ddl-auto: update 로도 컬럼은 생기지만, 값을 채우는 일과 같이 봐야 해서 여기 둔다.
-- 인덱스는 없다 — 주소로 검색하지 않고 화면에 보여주기만 한다.
--
-- 여러 번 돌려도 안전하다.

-- 1) 컬럼 ------------------------------------------------------------
-- 실제 주소(255)보다 넉넉하게 잡는다. "… 일대" 처럼 말이 덧붙는다.
-- NOT NULL 로 두지 않는다. 채우지 못한 가게가 있어도 상세 번지가 새지는 않는다 —
-- 값이 비면 애플리케이션이 실제 주소가 아니라 마스킹 문구를 내보낸다(Store.MASKED_ADDRESS).
ALTER TABLE stores
    ADD COLUMN IF NOT EXISTS display_address VARCHAR(300);

-- 2) 값 --------------------------------------------------------------
-- 상세 번지를 "일대" 로 바꾸는 식이다. 예)
--   서울특별시 성동구 연무장길 89  ->  서울특별시 성동구 연무장길 일대
--
-- 주소 형태가 제각각이라(도로명/지번, 건물명이 붙은 것) 여기서 일괄로 돌리지 않는다.
-- 잘못 뭉개면 번지가 그대로 남는데 마스킹한 줄 알게 되므로, 채운 뒤 아래로 훑어본다.

-- 확인
--   얼마나 채웠는지
--   SELECT count(*) FILTER (WHERE display_address IS NOT NULL AND display_address <> '') AS masked,
--          count(*) AS total
--     FROM stores;
--
--   번지가 남아 있는 것이 없는지 (숫자로 끝나면 의심스럽다)
--   SELECT store_id, address, display_address
--     FROM stores
--    WHERE display_address ~ '[0-9]\s*$'
--    LIMIT 50;
--
-- 되돌리기
--   ALTER TABLE stores DROP COLUMN IF EXISTS display_address;
