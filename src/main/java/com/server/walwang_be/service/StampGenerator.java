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
     * 도장을 만들어 여권에 붙이고, 확정된 상태를 돌려준다.
     *
     * 리뷰 등록 트랜잭션 안에서 동기로 돈다. 그래서 이 메서드는 예외를 밖으로 내보내지 않는다 —
     * 참여 중인 트랜잭션에서 RuntimeException이 새어 나가면 그 트랜잭션이 rollback-only로 찍히고,
     * 부르는 쪽이 잡아도 커밋이 UnexpectedRollbackException으로 터져 리뷰까지 통째로 사라진다.
     * 도장 하나 때문에 리뷰를 잃을 수는 없다.
     *
     * 그래서 세 가지를 한 자리에 모은다 — 실루엣을 못 딴 것(정상 응답), 물어보지 못한 것(장애·타임아웃),
     * 만든 걸 못 올린 것(스토리지 실패). 밖에서는 셋 다 FALLBACK이고, 구분은 로그로만 남는다.
     */
    @Transactional
    public StampResult generate(UUID passportId) {
        Passport passport = passportRepository.findWithReviewByPassportId(passportId)
                .orElse(null);
        if (passport == null) {
            // 리뷰가 지워졌다. 붙일 자리가 없으니 기본값만 돌려준다.
            return new StampResult(StampStatus.FALLBACK, PawStampClient.DEFAULT_STAMP_URL);
        }
        if (passport.getStatus() != StampStatus.PENDING) {
            // 사진이 없어 만들 때 이미 확정됐거나, 재시도가 겹쳐 다른 쪽이 먼저 끝냈다.
            return result(passport);
        }

        String photoUrl = passport.getReview().getPhotoUrl();
        if (photoUrl == null || photoUrl.isBlank()) {
            passport.applyFallback(PawStampClient.DEFAULT_STAMP_URL);
            return result(passport);
        }

        try {
            Optional<byte[]> stamp = pawStampClient.createStamp(download(photoUrl));
            if (stamp.isEmpty()) {
                // AI가 실루엣을 못 땄다. 장애가 아니라 정상 응답이다.
                passport.applyFallback(PawStampClient.DEFAULT_STAMP_URL);
                return result(passport);
            }
            // userId는 UUID다. %d로 찍으면 IllegalFormatConversionException이 난다.
            String path = "stamps/%s/%s.png".formatted(
                    passport.getUser().getUserId(), UUID.randomUUID());
            passport.applyStamp(storageClient.upload(path, stamp.get(), MediaType.IMAGE_PNG));
        } catch (RuntimeException e) {
            // 타임아웃(ai.stamp-timeout), AI 장애, 스토리지 실패가 모두 여기로 온다.
            // 예외 종류만 남긴다 — 메시지에 사진 URL이 섞여 나가는 걸 막는다.
            log.warn("발도장 생성 실패, 기본 발도장으로 확정합니다: passportId={} / {}",
                    passportId, e.getClass().getSimpleName());
            passport.applyFallback(PawStampClient.DEFAULT_STAMP_URL);
        }
        return result(passport);
    }

    private StampResult result(Passport passport) {
        return new StampResult(passport.getStatus(), passport.getStampUrl());
    }

    private byte[] download(String photoUrl) {
        byte[] photo = RestClient.create().get()
                .uri(photoUrl)
                .retrieve()
                .body(byte[].class);

        if (photo == null || photo.length == 0) {
            // URL은 남기지 않는다. 리뷰 사진의 위치라 로그로 흘릴 값이 아니다.
            throw new IllegalStateException("사진을 내려받지 못했습니다.");
        }
        return photo;
    }

    /** 확정된 도장. 리뷰 등록 응답이 이 값을 그대로 내보낸다. */
    public record StampResult(StampStatus status, String stampUrl) {
    }
}
