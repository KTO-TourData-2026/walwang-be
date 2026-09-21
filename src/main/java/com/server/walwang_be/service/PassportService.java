package com.server.walwang_be.service;

import com.server.walwang_be.client.PawStampClient;
import com.server.walwang_be.dto.PassportDetailResponse;
import com.server.walwang_be.dto.PassportSummaryResponse;
import com.server.walwang_be.entity.Passport;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.StampStatus;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.repo.PassportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 여권 도장.
 *
 * 도장 생성은 리뷰 등록 트랜잭션 안에서 동기로 끝낸다 — 프론트가 캐싱 때문에
 * 등록 응답에서 최종 stampUrl을 바로 받아야 하기 때문이다. 그 대신 세그멘테이션
 * 타임아웃을 8초(ai.stamp-timeout)로 끊어 사용자가 기다리는 시간을 묶어 둔다.
 * 8초 안에 못 받으면 FALLBACK으로 확정하고 리뷰는 그대로 저장된다.
 */
@Service
@RequiredArgsConstructor
public class PassportService {

    private final PassportRepository passportRepository;
    private final StampGenerator stampGenerator;

    /**
     * 여권 미리보기. 시연 도장과 실제 도장이 함께 나간다(PassportRepository).
     *
     * demo는 받지 않는다 — 요약에는 가게 이름이 들어가지 않아 가명을 고를 일도 없다.
     */
    @Transactional(readOnly = true)
    public List<PassportSummaryResponse> findMine(UUID userId, int page, int size) {
        return passportRepository
                .findByUser_UserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(PassportSummaryResponse::from)
                .getContent();
    }

    /**
     * 남의 여권은 존재를 알려주지 않고 403으로 끊는다.
     *
     * 축으로는 끊지 않는다 — 목록(findMine)이 시연 도장까지 보여주므로, 여기서 404를 내면
     * 목록에 보이는 도장을 눌렀을 때 열리지 않는다.
     *
     * @param demo 가게 이름에만 쓴다 — 시연 모드면 가명으로 나간다
     */
    @Transactional(readOnly = true)
    public PassportDetailResponse findOne(UUID userId, UUID passportId, boolean demo) {
        Passport passport = passportRepository.findWithReviewByPassportId(passportId)
                .orElseThrow(() -> new NoSuchElementException("존재하지 않는 여권입니다."));

        if (!passport.getUser().getUserId().equals(userId)) {
            throw new AccessDeniedException("자신의 여권만 볼 수 있습니다.");
        }
        return PassportDetailResponse.from(passport, demo);
    }

    /**
     * 리뷰가 저장된 직후 도장 자리를 만든다.
     *
     * 사진이 없으면 실루엣을 딸 것도 없으니 곧바로 기본 발도장으로 확정한다.
     * 호출하는 쪽의 트랜잭션 안에서 실행된다.
     *
     * is_demo는 리뷰에서 그대로 물려받는다 — 도장이 스스로 정할 값이 아니다.
     * 리뷰가 어느 쪽에 속하는지가 곧 그 도장이 어느 쪽에 속하는지다.
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
                .demo(review.isDemo())
                .build());
    }

    /**
     * 실제 도장 생성. 부르는 쪽의 트랜잭션 안에서 그대로 돈다.
     *
     * 다른 빈(StampGenerator)을 거쳐야 @Transactional이 걸린다. 같은 빈에서 부르면
     * 프록시를 지나지 않아 상태 변경이 저장되지 않는다.
     *
     * try/catch로 감싸지 않는다 — 감싸도 소용이 없기 때문이다. 참여 중인 트랜잭션이
     * rollback-only로 찍히면 여기서 잡아도 커밋이 터진다. 그래서 예외를 삼키는 책임은
     * StampGenerator.generate 안쪽에 있고, 이 메서드는 예외를 보지 않는다.
     */
    public StampGenerator.StampResult generateStampNow(UUID passportId) {
        return stampGenerator.generate(passportId);
    }
}
