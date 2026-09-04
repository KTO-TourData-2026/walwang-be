package com.server.walwang_be.client;

import com.server.walwang_be.config.AiProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * POST /internal/segmentation/stamp 을 호출한다.
 *
 * 성공과 폴백이 둘 다 200이라 상태코드로는 구분할 수 없다.
 * 대신 Content-Type을 본다 — 이미지면 성공, JSON이면 폴백이다.
 */
@Slf4j
public class AiPawStampClient implements PawStampClient {

    /** 로그에 남길 응답 본문 길이. 폴백 JSON이나 FastAPI detail은 이 안에 다 들어온다. */
    private static final int LOGGED_BODY_LIMIT = 500;

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
            if (isImage && body != null && body.length > 0) {
                return Optional.of(body);
            }
            // {"result":"FALLBACK_PAW"} — 실루엣을 못 땄다는 정상 응답이다.
            // 이미지도 폴백 JSON도 아닌 응답이 섞여 있을 수 있으니 무엇을 받았는지 남긴다.
            log.info("발도장 폴백 응답: contentType={} / {}", contentType, preview(body));
            return Optional.empty();

        } catch (RestClientResponseException e) {
            // 4xx/5xx. 이 예외도 RestClientException이라 아래 catch에 걸리지만,
            // 그러면 타임아웃과 한 덩어리가 되어 원인을 알 수 없다.
            // 특히 422는 우리가 보낸 요청이 규약과 다르다는 뜻이라 재시도해도 같은 답이 온다.
            log.warn("발도장 생성 실패: status={} / {}",
                    e.getStatusCode(), preview(e.getResponseBodyAsByteArray()));
            throw new StampUnavailableException(
                    "발도장 생성 서비스가 " + e.getStatusCode().value() + "로 응답했습니다.", e);

        } catch (RestClientException e) {
            // 연결 실패, 타임아웃 등 응답 자체를 받지 못한 경우.
            log.warn("발도장 생성 서비스에 연결하지 못했습니다: {}", e.toString());
            throw new StampUnavailableException("발도장 생성 서비스에 연결하지 못했습니다.", e);
        }
    }

    /** 바이너리가 섞여 들어와도 로그가 깨지지 않게 잘라서 문자열로 만든다. */
    private String preview(byte[] body) {
        if (body == null || body.length == 0) {
            return "본문 없음";
        }
        int length = Math.min(body.length, LOGGED_BODY_LIMIT);
        String text = new String(body, 0, length, StandardCharsets.UTF_8);
        return body.length > length ? text + "...(" + body.length + "바이트)" : text;
    }

    /** 파일명이 있어야 상대 쪽에서 파트를 파일로 인식한다. */
    private Resource toResource(byte[] photo) {
        return new ByteArrayResource(photo) {
            @Override
            public String getFilename() {
                return "photo.jpg";
            }
        };
    }
}
