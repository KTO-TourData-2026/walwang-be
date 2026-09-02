-- 시연 모드용 스키마.
--
-- ddl-auto: update 가 만들지 못하는 것만 여기 둔다 —
--   * pgvector 의 vector 타입 (Hibernate 가 매핑하지 못한다)
--   * 인덱스 (update 는 컬럼만 만들고 인덱스는 만들지 않는다)
--
-- 실행 순서: 앱을 한 번 띄워 컬럼이 생긴 뒤 이 파일을 돌린다.
-- 여러 번 돌려도 안전하도록 전부 IF NOT EXISTS 로 적었다.

-- ---------------------------------------------------------------
-- 1. 확장
-- ---------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS postgis;

-- ---------------------------------------------------------------
-- 2. 장소 임베딩
--
-- place_id 는 stores.store_id 를 가리킨다. 그 컬럼이 uuid 라
-- bigint 가 아니라 uuid 로 맞춘다.
--
-- is_demo 를 PK 에 넣는 이유는 같은 장소가 두 벌 필요하기 때문이다 —
-- 시연용(목 리뷰 기반)과 실제용(실제 리뷰 기반)이 공존한다.
-- ---------------------------------------------------------------
CREATE TABLE IF NOT EXISTS place_embedding (
    place_id   UUID         NOT NULL,
    is_demo    BOOLEAN      NOT NULL,
    embedding  vector(1536) NOT NULL,
    updated_at TIMESTAMP    NOT NULL DEFAULT now(),
    PRIMARY KEY (place_id, is_demo),
    CONSTRAINT fk_place_embedding_store
        FOREIGN KEY (place_id) REFERENCES stores (store_id) ON DELETE CASCADE
);

-- ---------------------------------------------------------------
-- 3. 인덱스
-- ---------------------------------------------------------------

-- 코사인 유사도 검색용. lists 는 행 수의 제곱근 근처가 권장값이라,
-- 데이터가 크게 늘면 다시 만들어야 한다.
-- ivfflat 은 데이터가 있어야 학습되므로 적재 후에 만드는 편이 낫다.
CREATE INDEX IF NOT EXISTS idx_place_embedding_cosine
    ON place_embedding USING ivfflat (embedding vector_cosine_ops)
    WITH (lists = 100);

-- 시연/실제를 갈라 읽는 조회가 대부분이다.
CREATE INDEX IF NOT EXISTS idx_place_embedding_demo
    ON place_embedding (is_demo);

-- 지도 반경 검색(ST_DWithin)이 전체 스캔을 타지 않게 한다.
CREATE INDEX IF NOT EXISTS idx_stores_geog
    ON stores USING GIST (geog);

-- 가게별 리뷰를 시연/실제로 갈라 최신순으로 읽는다.
CREATE INDEX IF NOT EXISTS idx_reviews_store_demo_created
    ON reviews (store_id, is_demo, created_at DESC);

-- 내 리뷰 목록도 같은 축으로 읽는다.
CREATE INDEX IF NOT EXISTS idx_reviews_user_created
    ON reviews (user_id, created_at DESC);

-- 외부 API 재수집 시 중복 판별에 쓴다.
CREATE UNIQUE INDEX IF NOT EXISTS idx_stores_external_id
    ON stores (external_id);
