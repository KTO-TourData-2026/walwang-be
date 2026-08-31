package com.server.walwang_be.service;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 업로드 이미지를 원본용·썸네일용 2벌로 만든다.
 *
 * JPEG로 다시 인코딩하는 과정에서 EXIF가 통째로 사라진다 —
 * 촬영 위치(GPS)가 사진에 박힌 채 공개 버킷에 올라가는 걸 막는 게 핵심이다.
 * 다만 EXIF의 회전 정보도 같이 날아가므로, 지우기 전에 회전을 픽셀에 먼저 반영한다
 * (Thumbnailator가 기본으로 해준다).
 */
@Component
public class ImageProcessor {

    /** 원본이라도 이 이상은 두지 않는다. 휴대폰 사진은 그냥 두면 수 MB다. */
    private static final int ORIGINAL_MAX_EDGE = 1600;
    private static final int THUMBNAIL_MAX_EDGE = 400;

    private static final double ORIGINAL_QUALITY = 0.85;
    private static final double THUMBNAIL_QUALITY = 0.75;

    /** 원본과 썸네일 한 쌍. 항상 함께 만들어져 함께 저장된다. */
    public record ProcessedImage(byte[] original, byte[] thumbnail) {
    }

    public ProcessedImage process(MultipartFile file) {
        try {
            byte[] original = resize(file, ORIGINAL_MAX_EDGE, ORIGINAL_QUALITY);
            byte[] thumbnail = resize(file, THUMBNAIL_MAX_EDGE, THUMBNAIL_QUALITY);
            return new ProcessedImage(original, thumbnail);
        } catch (IOException e) {
            throw new IllegalArgumentException("이미지를 읽을 수 없습니다.", e);
        }
    }

    private byte[] resize(MultipartFile file, int maxEdge, double quality) throws IOException {
        try (InputStream in = file.getInputStream();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Thumbnails.of(in)
                    // 원본이 이미 작으면 억지로 키우지 않는다.
                    .size(maxEdge, maxEdge)
                    .keepAspectRatio(true)
                    .outputQuality(quality)
                    .outputFormat("jpg")
                    .toOutputStream(out);
            return out.toByteArray();
        }
    }
}
