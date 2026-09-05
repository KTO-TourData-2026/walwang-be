package com.server.walwang_be.service;

import com.server.walwang_be.client.SupabaseStorageClient;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.entity.WithdrawalReason;
import com.server.walwang_be.repo.PassportRepository;
import com.server.walwang_be.repo.RefreshTokenRepository;
import com.server.walwang_be.repo.ReviewReportRepository;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.SavedCourseRepository;
import com.server.walwang_be.repo.SavedStoreRepository;
import com.server.walwang_be.repo.TokenBlacklistRepository;
import com.server.walwang_be.repo.UserAgreementRepository;
import com.server.walwang_be.repo.UserRepository;
import com.server.walwang_be.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 회원 탈퇴.
 *
 * 유저를 지우기 전에 그 유저를 참조하는 것들을 먼저 정리해야 한다 —
 * 남겨두면 FK 제약에 걸려 삭제 자체가 실패한다.
 *
 * 리뷰를 지우면 그 리뷰가 반영돼 있던 가게 점수도 다시 세야 하므로
 * ReviewService의 재집계를 그대로 빌려 쓴다.
 *
 * 임베딩도 같은 목록을 쓴다 — 점수가 달라진 가게는 설명도 달라졌다는 뜻이다.
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final WithdrawnEmailService withdrawnEmailService;
    private final UserAgreementRepository userAgreementRepository;
    private final ReviewRepository reviewRepository;
    private final ReviewReportRepository reviewReportRepository;
    private final PassportRepository passportRepository;
    private final SavedStoreRepository savedStoreRepository;
    private final SavedCourseRepository savedCourseRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final ReviewService reviewService;
    private final EmbeddingService embeddingService;
    private final SupabaseStorageClient storageClient;
    private final JwtProvider jwtProvider;

    @Transactional
    public void withdraw(UUID userId, String accessToken) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 사용자입니다."));
        // 유저를 지우기 전에 주소를 챙긴다. 지운 뒤에는 읽을 수 없다.
        String email = user.getEmail();

        List<Review> reviews = reviewRepository.findByUser_UserId(userId);

        // 사진 URL과 가게는 리뷰가 사라지기 전에 챙겨둔다.
        List<String> photoUrls = new ArrayList<>();
        Set<Store> affectedStores = new LinkedHashSet<>();
        for (Review review : reviews) {
            addIfPresent(photoUrls, review.getPhotoUrl());
            addIfPresent(photoUrls, review.getThumbnailUrl());
            affectedStores.add(review.getStore());
        }

        // 도장 파일 주소는 여권 행이 사라지기 전에 챙긴다. 리뷰 사진과 같은 이유다 —
        // 행이 없어지면 어떤 파일을 올렸는지 알 방법이 없다.
        List<String> stampUrls = passportRepository.findStoredStampUrlsByUser(userId);

        // 여권이 리뷰를 참조하므로 리뷰보다 먼저 지운다.
        passportRepository.deleteByUser_UserId(userId);
        // 이 사람이 낸 신고와, 이 사람의 리뷰를 향해 남이 낸 신고를 모두 걷어낸다 —
        // 둘 중 하나라도 남으면 FK에 걸려 탈퇴 자체가 실패한다.
        reviewReportRepository.deleteByReporter_UserId(userId);
        if (!reviews.isEmpty()) {
            reviewReportRepository.deleteByReview_ReviewIdIn(
                    reviews.stream().map(Review::getReviewId).toList());
        }
        reviewRepository.deleteAll(reviews);
        savedStoreRepository.deleteByUser_UserId(userId);
        savedCourseRepository.deleteByUser_UserId(userId);
        userAgreementRepository.deleteByUser_UserId(userId);
        // 재집계가 지워진 리뷰를 다시 세지 않도록 먼저 DB에 반영한다.
        reviewRepository.flush();

        // 두 축을 모두 다시 센다. 이 사람의 리뷰가 어느 쪽에 걸려 있었는지 행마다 다르고,
        // 탈퇴는 자주 일어나지 않아 한 번 더 세는 비용이 문제가 되지 않는다.
        affectedStores.forEach(store -> {
            reviewService.recompute(store, false);
            reviewService.recompute(store, true);
        });
        // 재집계와 대상이 같다. 다만 커밋 뒤에 불러야 해서 id만 미리 빼둔다 —
        // 그때는 엔티티가 준영속이라 새로 읽어올 수 없다.
        List<UUID> storeIdsToRefresh = affectedStores.stream().map(Store::getStoreId).toList();
        userRepository.deleteById(userId);

        // 자진 탈퇴는 기록만 남고 재가입을 막지 않는다(WithdrawalReason 참고).
        withdrawnEmailService.record(email, WithdrawalReason.SELF);

        // 토큰은 롤백되지 않으므로 커밋이 확정된 뒤에 정리한다.
        afterCommit(() -> {
            refreshTokenRepository.deleteAllByUserId(userId);
            if (accessToken != null) {
                tokenBlacklistRepository.blacklist(accessToken, jwtProvider.getExpiresAt(accessToken));
            }
            photoUrls.forEach(storageClient::deleteByPublicUrlQuietly);
            stampUrls.forEach(storageClient::deleteByPublicUrlQuietly);
        });

        // 리뷰가 통째로 빠지면 그 가게의 설명도 달라진다. 리뷰 삭제와 같은 이유로 갱신을 알린다.
        // 목록에서 이미 중복이 걸러져 가게당 한 번만 나간다.
        afterCommit(() -> storeIdsToRefresh.forEach(embeddingService::refreshAsync));
    }

    private void addIfPresent(List<String> target, String value) {
        if (value != null && !value.isBlank()) {
            target.add(value);
        }
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
