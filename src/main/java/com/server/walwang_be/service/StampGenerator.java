package com.server.walwang_be.service;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.client.SupabaseStorageClient;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.repo.PassportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.util.Optional;
import java.util.UUID;

/**
 * 발도장 한 건을 실제로 만드는 자리.
 *
 * PassportService에서 따로 떼어냈다. 같은 빈 안에서 부르면 프록시를 거치지 않아
 * @Transactional이 통째로 무시되고, 그러면 조회한 Passport가 detached 상태라
 * 상태를 바꿔도 UPDATE가 나가지 않는다 — 여권이 영원히 PENDING에 남는다.
 * 빈을 나눠 두면 호출이 반드시 프록시를 지나므로 그 사고가 구조적으로 막힌다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StampGenerator {

    private final PassportRepository passportRepository;
    private final PawStampClient pawStampClient;
    private final SupabaseStorageClient storageClient;

    /**
     * 도장을 만들어 여권에 붙인다.
     *
     * 실루엣을 못 딴 것(FALLBACK)과 물어보지 못한 것(장애)을 구분한다 —
     * 앞엣것은 여기서 확정하고, 뒤엣것은 예외로 올려 PENDING으로 남긴다.
     * 그래야 재시도가 같은 답을 반복해서 받지 않는다.
     */
    @Transactional
    public void generate(UUID passportId) {
        Passport passport = passportRepository.findWithReviewByPassportId(passportId)
                .orElse(null);
        if (passport == null || passport.getStatus() != StampStatus.PENDING) {
            // 리뷰가 지워졌거나 이미 처리된 건이다.
            return;
        }

        String photoUrl = passport.getReview().getPhotoUrl();
        if (photoUrl == null || photoUrl.isBlank()) {
            passport.applyFallback(PawStampClient.DEFAULT_STAMP_URL);
            return;
        }

        Optional<byte[]> stamp = pawStampClient.createStamp(download(photoUrl));
        if (stamp.isEmpty()) {
            // AI가 실루엣을 못 땄다. 장애가 아니라 정상 응답이므로 여기서 확정한다.
            passport.applyFallback(PawStampClient.DEFAULT_STAMP_URL);
            return;
        }

        // userId는 UUID다. %d로 찍으면 IllegalFormatConversionException이 난다.
        String path = "stamps/%s/%s.png".formatted(
                passport.getUser().getUserId(), UUID.randomUUID());
        passport.applyStamp(storageClient.upload(path, stamp.get(), MediaType.IMAGE_PNG));
    }

    private byte[] download(String photoUrl) {
        byte[] photo = RestClient.create().get()
                .uri(photoUrl)
                .retrieve()
                .body(byte[].class);

        if (photo == null || photo.length == 0) {
            throw new IllegalStateException("사진을 내려받지 못했습니다: " + photoUrl);
        }
        return photo;
    }
}
