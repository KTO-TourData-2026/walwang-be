package com.server.walwang_be.service;

import com.server.walwang_be.client.EmbeddingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 임베딩.
 *
 * 리뷰가 하나 붙을 때마다 그 가게의 설명이 달라지므로 AI에 다시 계산하라고 알린다.
 * 벡터는 AI가 place_embedding에 직접 넣는다 — 백엔드는 호출만 하고 끝이다.
 *
 * 반대로 코스 추천에서는 태그를 기준 벡터로 바꿔 받아온다. 그 값이 있어야
 * 저장된 장소 벡터와 견줘 유사도순으로 줄을 세울 수 있다.
 *
 * 리뷰 등록과 분리한 이유는 도장과 같다. 갱신이 늦어지거나 실패해도
 * 사용자의 리뷰는 이미 저장돼 있어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    private final EmbeddingClient embeddingClient;

    /**
     * 반드시 리뷰 트랜잭션이 커밋된 뒤에 불러야 한다 —
     * AI가 갱신하려고 읽는 리뷰는 커밋 전에는 보이지 않는다.
     *
     * 실패는 모두 삼킨다. 재시도하지 않는 것도 의도다 —
     * 다음 리뷰가 들어오면 어차피 같은 가게를 다시 갱신한다.
     */
    @Async("embeddingExecutor")
    public void refreshAsync(UUID storeId) {
        try {
            embeddingClient.refresh(storeId);
        } catch (EmbeddingClient.EmbeddingStoreNotFoundException e) {
            // 우리 DB에는 있는데 AI 쪽에는 없다는 뜻이라 다시 불러도 결과가 같다.
            log.warn("임베딩 갱신 실패 (AI에 가게 없음): storeId={}", storeId);
        } catch (Exception e) {
            log.warn("임베딩 갱신 실패: storeId={} / {}", storeId, e.toString());
        }
    }

    /**
     * 사용자가 고른 태그를 같은 벡터 공간의 한 점으로 바꿔 받는다.
     *
     * 여기만 동기다 — 추천 결과를 만드는 데 필요한 값이라 기다려야 한다.
     * 그래서 타임아웃도 따로(3초) 짧게 잡혀 있다.
     *
     * @return 기준 벡터. 태그가 없거나 AI가 답하지 않으면 null —
     *         부르는 쪽은 그대로 거리순 폴백으로 간다
     */
    public float[] queryEmbedding(List<String> tags) {
        if (tags == null || tags.isEmpty()) {
            return null;
        }
        try {
            return embeddingClient.query(tags).orElse(null);
        } catch (Exception e) {
            // 추천을 실패로 끝내지 않는다. 순서가 거리순이 될 뿐 코스는 나간다.
            log.warn("기준 벡터 조회 실패 (거리순으로 대체): tags={} / {}", tags, e.toString());
            return null;
        }
    }
}
