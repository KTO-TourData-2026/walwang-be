package com.server.walwang_be.client;

import com.server.walwang_be.config.AiProperties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * POST /internal/segmentation/stamp 을 호출한다.
 *
 * 성공과 폴백이 둘 다 200이라 상태코드로는 구분할 수 없다.
 * 대신 Content-Type을 본다 — 이미지면 성공, JSON이면 폴백이다.
 */
public class AiPawStampClient implements PawStampClient {

    private final RestClient restClient;

    public AiPawStampClient(AiProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.stampTimeoutOrDefault().toMillis());
        factory.setReadTimeout((int) properties.stampTimeoutOrDefault().toMillis());

        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .build();
    }

    @Override
    public Optional<byte[]> createStamp(byte[] photo) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("photo", toResource(photo));

        try {
            ResponseEntity<byte[]> response = restClient.post()
                    .uri("/internal/segmentation/stamp")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .accept(MediaType.IMAGE_PNG, MediaType.APPLICATION_JSON)
                    .body(parts)
                    .retrieve()
                    .toEntity(byte[].class);

            MediaType contentType = response.getHeaders().getContentType();
            byte[] body = response.getBody();

            boolean isImage = contentType != null && contentType.getType().equals("image");
            return (isImage && body != null && body.length > 0)
                    ? Optional.of(body)
                    // {"result":"FALLBACK_PAW"} — 실루엣을 못 땄다는 정상 응답이다.
                    : Optional.empty();

        } catch (RestClientException e) {
            throw new StampUnavailableException("발도장 생성 서비스에 연결하지 못했습니다.", e);
        }
    }

    private Resource toResource(byte[] photo) {
        return new ByteArrayResource(photo) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        };
    }
}
