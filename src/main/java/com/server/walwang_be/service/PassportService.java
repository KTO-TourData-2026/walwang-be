package com.server.walwang_be.service;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.client.SupabaseStorageClient;
import com.server.walwang_be.dto.PassportDetailResponse;
import com.server.walwang_be.dto.PassportSummaryResponse;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.PassportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

/**
 * 여권 도장.
 *
 * 도장 생성은 리뷰 등록과 분리한다 — 세그멘테이션이 수십 초 걸릴 수 있어서
 * 그 시간만큼 사용자를 기다리게 할 수 없다. 리뷰는 바로 저장되고,
 * 도장은 PENDING 상태로 남았다가 뒤에서 채워진다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PassportService {

    /** 이 시간이 지나도 PENDING이면 작업이 유실된 것으로 보고 다시 집는다. */
    private static final Duration STUCK_AFTER = Duration.ofMinutes(5);

    private final PassportRepository passportRepository;
    private final PawStampClient pawStampClient;
    private final SupabaseStorageClient storageClient;

    @Transactional(readOnly = true)
    public List<PassportSummaryResponse> findMine(UUID userId, int page, int size) {
        return passportRepository
                .findByUser_UserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(PassportSummaryResponse::from)
                .getContent();
    }

    /** 남의 여권은 존재를 알려주지 않고 403으로 끊는다. */
    @Transactional(readOnly = true)
    public PassportDetailResponse findOne(UUID userId, UUID passportId) {
        Passport passport = passportRepository.findWithReviewByPassportId(passportId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 여권입니다."));

        if (!passport.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("자신의 여권만 볼 수 있습니다.");
        }
        return PassportDetailResponse.from(passport);
    }

    /**
     * 리뷰가 저장된 직후 도장 자리를 만든다.
     *
     * 사진이 없으면 실루엣을 딸 것도 없으니 곧바로 기본 발도장으로 확정한다.
     * 호출하는 쪽의 트랜잭션 안에서 실행된다.
     */
    @Transactional
    public Passport createFor(User user, Store store, Review review) {
        boolean hasPhoto = review.getPhotoUrl() != null && !review.getPhotoUrl().isBlank();

        return passportRepository.save(Passport.builder()
                .user(user)
                .store(store)
                .review(review)
                .status(hasPhoto ? StampStatus.PENDING : StampStatus.FALLBACK)
                .stampUrl(hasPhoto ? null : PawStampClient.DEFAULT_STAMP_URL)
                .build());
    }

    /**
     * 실제 도장 생성. 반드시 리뷰 트랜잭션이 커밋된 뒤에 불러야 한다 —
     * 다른 스레드에서 도므로 커밋 전에는 여권 행이 보이지 않는다.
     */
    @Async("stampExecutor")
    public void generateStampAsync(UUID passportId) {
        try {
            generateStamp(passportId);
        } catch (Exception e) {
            // 여기서 예외가 새어 나가도 받아줄 곳이 없다. PENDING으로 남겨두면
            // 재시도 스케줄러가 나중에 다시 집어간다.
            log.warn("발도장 생성 실패 (재시도 대기): passportId={} / {}", passportId, e.toString());
        }
    }

    @Transactional
    public void generateStamp(UUID passportId) {
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

        String path = "stamps/%d/%s.png".formatted(passport.getUser().getUserId(), UUID.randomUUID());
        passport.applyStamp(storageClient.upload(path, stamp.get(), MediaType.IMAGE_PNG));
    }

    /**
     * 유실된 작업을 다시 집는다.
     *
     * 서버가 내려가거나 큐가 넘치면 비동기 작업은 그대로 사라진다.
     * 이 스케줄러가 없으면 그 여권은 영영 빈 도장으로 남는다.
     */
    @Scheduled(fixedDelay = 5, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void retryStuckStamps() {
        List<Passport> stuck = passportRepository.findTop20ByStatusAndCreatedAtBefore(
                StampStatus.PENDING, Instant.now().minus(STUCK_AFTER));

        stuck.forEach(passport -> generateStampAsync(passport.getPassportId()));
    }

    /** 업로드해 둔 사진을 다시 받아온다. 공개 URL이라 인증이 필요 없다. */
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
