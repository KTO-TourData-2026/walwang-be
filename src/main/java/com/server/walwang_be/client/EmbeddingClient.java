package com.server.walwang_be.client;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 임베딩을 다루는 AI 창구. 두 갈래다.
 *
 * refresh는 가게 벡터를 다시 계산하라고 알리는 쪽이다 — 벡터는 AI가
 * place_embedding에 직접 넣으므로 백엔드는 받지도 저장하지도 않는다.
 *
 * query는 사용자가 고른 태그를 같은 벡터 공간의 한 점으로 바꿔 받아오는 쪽이다.
 * 이 값이 있어야 코스 추천에서 가게를 유사도순으로 줄 세울 수 있다.
 *
 * 태그는 StoreTag로 바꾸지 않고 받은 문자열 그대로 넘긴다 — 우리가 모르는 코드가
 * 하나 섞였다고 추천 요청 전체가 400으로 끝나면 안 된다. 모르는 코드는 AI가 판단한다.
 */
public interface EmbeddingClient {

    /** place_embedding.embedding의 차원. 다른 길이가 오면 pgvector가 질의에서 터진다. */
    int DIMENSION = 1536;

    /**
     * @throws EmbeddingStoreNotFoundException AI가 store_id를 찾지 못했을 때 (404)
     * @throws EmbeddingUnavailableException   그 밖의 AI 장애
     */
    void refresh(UUID storeId);

    /**
     * 태그를 기준 벡터로 바꾼다.
     *
     * @param tags 사용자가 고른 태그 코드. 비어 있으면 부르지 않는다 — AI는 400으로 답한다
     * @return 1536차원 벡터. 태그가 비었거나 길이가 맞지 않으면 비어 있다
     * @throws EmbeddingUnavailableException AI 장애
     */
    Optional<float[]> query(List<String> tags);

    /**
     * AI 쪽에 해당 가게가 없다. 다시 불러도 결과가 같으므로
     * 장애와 구분해 재시도 대상에서 빼려고 따로 둔다.
     */
    class EmbeddingStoreNotFoundException extends RuntimeException {
        public EmbeddingStoreNotFoundException(String message) {
            super(message);
        }
    }

    /** 임베딩 서비스 장애. 다음 리뷰가 들어오면 어차피 다시 갱신된다. */
    class EmbeddingUnavailableException extends RuntimeException {
        public EmbeddingUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
