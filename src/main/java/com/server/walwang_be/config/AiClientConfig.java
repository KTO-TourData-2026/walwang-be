package com.server.walwang_be.config;

import com.server.walwang_be.client.AiEmbeddingClient;
import com.server.walwang_be.client.AiPawStampClient;
import com.server.walwang_be.client.AiReceiptOcrClient;
import com.server.walwang_be.client.EmbeddingClient;
import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.client.ReceiptOcrClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * AI 서비스 연결.
 *
 * ai.base-url이 설정돼 있으면 실제 서비스를 호출하고, 없으면 스텁이 대신 선다 —
 * AI 서버 없이도 나머지 기능을 개발하고 띄울 수 있어야 하기 때문이다.
 *
 * 조건부 빈은 @Component가 아니라 여기 @Bean 메서드에 걸어야 한다.
 * 컴포넌트 스캔에서는 @ConditionalOnMissingBean이 제대로 평가되지 않는다.
 */
@Configuration
public class AiClientConfig {

    @Bean
    @ConditionalOnProperty(name = "ai.base-url")
    public ReceiptOcrClient aiReceiptOcrClient(AiProperties properties) {
        return new AiReceiptOcrClient(properties);
    }

    /**
     * OCR이 연결되지 않았을 때. 항상 읽기 실패로 응답한다 —
     * 아무 영수증이나 통과시키면 검증이 붙어 있다고 착각한 채 배포될 수 있다.
     */
    @Bean
    @ConditionalOnMissingBean(ReceiptOcrClient.class)
    public ReceiptOcrClient stubReceiptOcrClient() {
        return (receipt, storeId, storeName) -> {
            throw new ReceiptOcrClient.OcrUnreadableException("OCR이 아직 연결되지 않았습니다.");
        };
    }

    @Bean
    @ConditionalOnProperty(name = "ai.base-url")
    public PawStampClient aiPawStampClient(AiProperties properties) {
        return new AiPawStampClient(properties);
    }

    /** 세그멘테이션이 연결되지 않았을 때. 항상 기본 발도장으로 떨어뜨린다. */
    @Bean
    @ConditionalOnMissingBean(PawStampClient.class)
    public PawStampClient stubPawStampClient() {
        return photo -> Optional.empty();
    }

    @Bean
    @ConditionalOnProperty(name = "ai.base-url")
    public EmbeddingClient aiEmbeddingClient(AiProperties properties) {
        return new AiEmbeddingClient(properties);
    }

    /**
     * 임베딩이 연결되지 않았을 때.
     *
     * 갱신은 아무것도 하지 않는다 — 실패해도 리뷰 등록을 막지 않으므로
     * 여기서 예외를 던질 이유가 없다.
     * 기준 벡터는 빈 값을 준다 — 코스 추천이 거리순 폴백으로 내려간다.
     */
    @Bean
    @ConditionalOnMissingBean(EmbeddingClient.class)
    public EmbeddingClient stubEmbeddingClient() {
        return new EmbeddingClient() {
            @Override
            public void refresh(UUID storeId) {
            }

            @Override
            public Optional<float[]> query(List<String> tags) {
                return Optional.empty();
            }
        };
    }
}
