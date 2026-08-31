package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Optional;

/**
 * application.yaml 의 supabase.* 설정.
 *
 * supabase:
 *   url: https://xxxxxxxx.supabase.co
 *   service-key: <service_role 키>      # 서버 전용. 절대 클라이언트에 내려보내지 않는다.
 *   bucket: review-photos
 *
 * service_role 키는 RLS를 통째로 우회하므로 반드시 서버에만 둔다.
 */
@ConfigurationProperties(prefix = "supabase")
public record SupabaseProperties(
        String url,
        String serviceKey,
        String bucket
) {
    /** Storage REST 엔드포인트. 업로드/삭제 모두 이 경로 아래로 간다. */
    public String objectUrl(String path) {
        return "%s/storage/v1/object/%s/%s".formatted(url, bucket, path);
    }

    /** 브라우저가 바로 열 수 있는 공개 URL. 버킷이 public이어야 한다. */
    public String publicUrl(String path) {
        return "%s/storage/v1/object/public/%s/%s".formatted(url, bucket, path);
    }

    /**
     * 공개 URL에서 버킷 안 경로를 되짚는다.
     *
     * DB에는 URL만 남기므로 파일을 지우려면 이 변환이 필요하다.
     * 설정이 바뀌었거나 다른 곳에서 온 URL이면 비어 있는 값을 돌려준다 —
     * 엉뚱한 경로를 지우느니 아무것도 안 하는 편이 낫다.
     */
    public Optional<String> pathFromPublicUrl(String publicUrl) {
        if (publicUrl == null || url == null || bucket == null) {
            return Optional.empty();
        }
        String prefix = "%s/storage/v1/object/public/%s/".formatted(url, bucket);
        return publicUrl.startsWith(prefix)
                ? Optional.of(publicUrl.substring(prefix.length()))
                : Optional.empty();
    }
}
