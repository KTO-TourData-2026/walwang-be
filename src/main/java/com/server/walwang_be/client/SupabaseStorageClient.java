package com.server.walwang_be.client;

import com.server.walwang_be.config.SupabaseProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Supabase Storage 업로드.
 *
 * 업로드가 끝나면 공개 URL을 돌려주고, 그 문자열이 그대로 review.photo_url 이 된다.
 * 실패는 삼키지 않는다 — 사진이 없는 리뷰와 "저장에 실패한" 리뷰는 구분되어야 한다.
 */
@Slf4j
@Component
public class SupabaseStorageClient {

    private final SupabaseProperties properties;
    private final RestClient restClient;

    public SupabaseStorageClient(SupabaseProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.create();
    }

    /**
     * @param path        버킷 안 경로. 예: reviews/12/9f3c-original.jpg
     * @param contentType 저장될 MIME 타입
     * @return 공개 URL
     */
    public String upload(String path, byte[] content, MediaType contentType) {
        try {
            restClient.post()
                    .uri(properties.objectUrl(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceKey())
                    .contentType(contentType)
                    // 같은 경로로 다시 올라오면 덮어쓴다. 재시도가 409로 막히지 않게 한다.
                    .header("x-upsert", "true")
                    .body(content)
                    .retrieve()
                    .toBodilessEntity();
            return properties.publicUrl(path);
        } catch (RestClientException e) {
            throw new IllegalStateException("사진 업로드에 실패했습니다.", e);
        }
    }

    /**
     * 저장할 때 받았던 공개 URL로 파일을 지운다.
     * DB에는 URL만 남아 있어서, 삭제할 때는 이 경로로 되짚어야 한다.
     */
    public void deleteByPublicUrlQuietly(String publicUrl) {
        if (publicUrl == null || publicUrl.isBlank()) {
            return;
        }
        properties.pathFromPublicUrl(publicUrl).ifPresentOrElse(
                this::deleteQuietly,
                () -> log.warn("스토리지 경로를 알 수 없어 파일을 지우지 못했습니다: {}", publicUrl));
    }

    /** 리뷰가 지워질 때 사진도 함께 지운다. 실패해도 리뷰 삭제는 막지 않는다. */
    public void deleteQuietly(String path) {
        try {
            restClient.delete()
                    .uri(properties.objectUrl(path))
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + properties.serviceKey())
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException e) {
            // 고아 파일이 남는 건 리뷰 삭제를 실패시키는 것보다 낫다.
            // 다만 조용히 넘기면 추적이 안 되므로 경로는 남긴다.
            log.warn("스토리지 파일 삭제 실패 (고아 파일로 남음): {} / {}", path, e.getMessage());
        }
    }
}
