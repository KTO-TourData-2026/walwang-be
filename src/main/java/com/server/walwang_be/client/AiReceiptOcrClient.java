package com.server.walwang_be.client;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.server.walwang_be.config.AiProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** POST /internal/ocr/verify 를 호출한다. */
public class AiReceiptOcrClient implements ReceiptOcrClient {

    private final RestClient restClient;

    public AiReceiptOcrClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.ocrTimeoutOrDefault().toMillis());
        factory.setReadTimeout((int) properties.ocrTimeoutOrDefault().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public OcrResult verify(MultipartFile receipt, Long storeId, String storeName) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("receipt", toResource(receipt));
        parts.add("store_id", storeId);
        parts.add("store_name", storeName);

        try {
            Response response = restClient.post()
                    .uri("/internal/ocr/verify")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    // 422는 "글자를 못 읽었다"는 뜻이라 장애와 구분해서 올린다.
                    .onStatus(HttpStatusCode::is4xxClientError, (req, res) -> {
                        throw new OcrUnreadableException("영수증에서 상호명을 읽지 못했습니다.");
                    })
                    .body(Response.class);

            if (response == null) {
                throw new OcrUnreadableException("영수증에서 상호명을 읽지 못했습니다.");
            }
            return new OcrResult(response.matchedName(), response.similarity());

        } catch (OcrUnreadableException e) {
            throw e;
        } catch (RestClientException e) {
            throw new OcrUnavailableException("영수증 인증 서비스에 연결하지 못했습니다.", e);
        }
    }

    /** 파일명이 있어야 상대 쪽에서 파트를 파일로 인식한다. */
    private Resource toResource(MultipartFile file) {
        try {
            return new ByteArrayResource(file.getBytes()) {
                @Override
                public String getFilename() {
                    return file.getOriginalFilename() == null ? "receipt" : file.getOriginalFilename();
                }
            };
        } catch (IOException e) {
            throw new IllegalArgumentException("영수증 이미지를 읽을 수 없습니다.", e);
        }
    }

    /**
     * AI 응답. 우리 API는 camelCase지만 AI와 주고받는 규약은 snake_case라
     * 필드마다 @JsonProperty로 이름을 못 박는다 —
     * 우리 쪽 표기 규칙을 바꿔도 이 매핑은 흔들리지 않는다.
     *
     * verified와 receipt_token은 읽지 않는다. 통과 기준과 토큰 발급은 서버가 정한다.
     */
    private record Response(
            @JsonProperty("matched_name") String matchedName,
            @JsonProperty("similarity") double similarity
    ) {
    }
}
