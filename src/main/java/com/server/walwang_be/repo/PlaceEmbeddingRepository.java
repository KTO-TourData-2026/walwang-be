package com.server.walwang_be.repo;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 장소 임베딩 조회.
 *
 * pgvector의 vector 타입은 Hibernate가 매핑하지 못해 엔티티로 두지 않고
 * 네이티브 쿼리로만 다룬다. 테이블은 db/schema-demo.sql 로 만든다.
 *
 * 시연 모드와 실제 모드가 각각 다른 임베딩을 쓰므로 모든 조회에 is_demo가 붙는다.
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PlaceEmbeddingRepository {

    private final EntityManager entityManager;

    /**
     * 후보 중 임베딩을 가진 장소가 하나라도 있는지.
     *
     * 실제 모드에는 리뷰가 없어 임베딩도 없다 — 그건 오류가 아니라
     * 벡터 정렬을 건너뛰고 거리순으로 가라는 신호다.
     */
    public boolean existsAny(Collection<UUID> placeIds, boolean demo) {
        if (placeIds.isEmpty()) {
            return false;
        }
        Object count = entityManager.createNativeQuery("""
                        SELECT count(*) FROM place_embedding
                        WHERE is_demo = :demo AND place_id IN (:ids)
                        """)
                .setParameter("demo", demo)
                .setParameter("ids", placeIds)
                .getSingleResult();

        return ((Number) count).longValue() > 0;
    }

    /**
     * 기준 벡터에 가까운 순으로 장소를 돌려준다.
     *
     * <=> 는 pgvector의 코사인 거리 연산자다. 작을수록 비슷하다.
     * 임베딩이 없는 후보는 결과에서 빠지므로, 호출한 쪽이 남은 것을 뒤에 붙여야 한다.
     *
     * @param queryEmbedding 1536차원 벡터. AI 쪽에서 만들어 넘겨준다
     */
    @SuppressWarnings("unchecked")
    public List<UUID> rankBySimilarity(float[] queryEmbedding,
                                       Collection<UUID> placeIds,
                                       boolean demo) {
        if (queryEmbedding == null || queryEmbedding.length == 0 || placeIds.isEmpty()) {
            return List.of();
        }
        return entityManager.createNativeQuery("""
                        SELECT place_id FROM place_embedding
                        WHERE is_demo = :demo AND place_id IN (:ids)
                        ORDER BY embedding <=> CAST(:query AS vector)
                        """)
                .setParameter("demo", demo)
                .setParameter("ids", placeIds)
                .setParameter("query", toVectorLiteral(queryEmbedding))
                .getResultList();
    }

    /** pgvector는 '[0.1,0.2,...]' 형태의 문자열을 vector로 캐스팅해 받는다. */
    private String toVectorLiteral(float[] values) {
        StringBuilder builder = new StringBuilder(values.length * 8 + 2).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(values[i]);
        }
        return builder.append(']').toString();
    }
}
