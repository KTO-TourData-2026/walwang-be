package com.server.walwang_be.service;

import com.server.walwang_be.client.SupabaseStorageClient;
import com.server.walwang_be.entity.Review;
import com.server.walwang_be.entity.Store;
import com.server.walwang_be.repo.PassportRepository;
import com.server.walwang_be.repo.RefreshTokenRepository;
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
 */
@Service
@RequiredArgsConstructor
public class UserWithdrawalService {

    private final UserRepository userRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final ReviewRepository reviewRepository;
    private final PassportRepository passportRepository;
    private final SavedStoreRepository savedStoreRepository;
    private final SavedCourseRepository savedCourseRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final ReviewService reviewService;
    private final SupabaseStorageClient storageClient;
    private final JwtProvider jwtProvider;

    @Transactional
    public void withdraw(UUID userId, String accessToken) {
        if (!userRepository.existsById(userId)) {
            throw new BadCredentialsException("존재하지 않는 사용자입니다.");
        }

        List<Review> reviews = reviewRepository.findByUser_UserId(userId);

        // 사진 URL과 가게는 리뷰가 사라지기 전에 챙겨둔다.
        List<String> photoUrls = new ArrayList<>();
        Set<Store> affectedStores = new LinkedHashSet<>();
        for (Review review : reviews) {
            addIfPresent(photoUrls, review.getPhotoUrl());
            addIfPresent(photoUrls, review.getThumbnailUrl());
            affectedStores.add(review.getStore());
        }

        // 여권이 리뷰를 참조하므로 리뷰보다 먼저 지운다.
        passportRepository.deleteByUser_UserId(userId);
        reviewRepository.deleteAll(reviews);
        savedStoreRepository.deleteByUser_UserId(userId);
        savedCourseRepository.deleteByUser_UserId(userId);
        userAgreementRepository.deleteByUser_UserId(userId);
        // 재집계가 지워진 리뷰를 다시 세지 않도록 먼저 DB에 반영한다.
        reviewRepository.flush();

        affectedStores.forEach(reviewService::recompute);
        userRepository.deleteById(userId);

        // 토큰은 롤백되지 않으므로 커밋이 확정된 뒤에 정리한다.
        afterCommit(() -> {
            refreshTokenRepository.deleteAllByUserId(userId);
            if (accessToken != null) {
                tokenBlacklistRepository.blacklist(accessToken, jwtProvider.getExpiresAt(accessToken));
            }
            photoUrls.forEach(storageClient::deleteByPublicUrlQuietly);
        });
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
