package com.server.walwang_be.service;

import com.server.walwang_be.entity.BlindStatus;
import com.server.walwang_be.dto.AgreementRequest;
import com.server.walwang_be.dto.LoginRequest;
import com.server.walwang_be.dto.SignUpRequest;
import com.server.walwang_be.dto.TokenResponse;
import com.server.walwang_be.dto.MyReviewResponse;
import com.server.walwang_be.dto.UserPatchRequest;
import com.server.walwang_be.dto.UserProfileResponse;
import com.server.walwang_be.entity.TermCode;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.entity.UserAgreement;
import com.server.walwang_be.repo.PassportRepository;
import com.server.walwang_be.repo.RefreshTokenRepository;
import com.server.walwang_be.repo.ReviewRepository;
import com.server.walwang_be.repo.SavedStoreRepository;
import com.server.walwang_be.repo.TokenBlacklistRepository;
import com.server.walwang_be.repo.UserAgreementRepository;
import com.server.walwang_be.repo.UserRepository;
import com.server.walwang_be.security.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final UserAgreementRepository userAgreementRepository;
    private final ReviewRepository reviewRepository;
    private final PassportRepository passportRepository;
    private final SavedStoreRepository savedStoreRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final TokenBlacklistRepository tokenBlacklistRepository;
    private final WithdrawnEmailService withdrawnEmailService;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    /**
     * 마이 화면. 리뷰·도장·저장 개수는 목록 API가 페이징이라 여기서 따로 센다.
     *
     * 숫자는 저마다 대응하는 목록과 같은 기준으로 센다 — 어긋나면 "리뷰 5개"라고 적힌 화면에
     * 리뷰가 8개 나온다. 리뷰·도장은 목록이 축을 섞으므로 여기서도 섞어 세고,
     * 저장 장소는 목록이 축을 가르므로 demo가 정한 쪽만 센다.
     *
     * @param demo 저장 장소 수에만 걸린다
     */
    @Transactional(readOnly = true)
    public UserProfileResponse findMe(UUID userId, boolean demo) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 사용자입니다."));

        return UserProfileResponse.of(user,
                reviewRepository.countByUser_UserIdAndBlindStatus(userId, BlindStatus.NORMAL),
                passportRepository.countByUser_UserId(userId),
                savedStoreRepository.countByUser_UserIdAndDemo(userId, demo));
    }

    /**
     * 내가 쓴 리뷰 목록. 최신순.
     * 인증을 거치지 않은 리뷰도 내 것이면 그대로 나온다 — 남에게만 안 보일 뿐이다.
     * 시연 리뷰도 함께 나온다(ReviewRepository).
     *
     * @param demo 가게 이름에만 쓴다 — 시연 모드면 가명으로 나간다. 목록을 거르지는 않는다.
     */
    @Transactional(readOnly = true)
    public List<MyReviewResponse> findMyReviews(UUID userId, int page, int size, boolean demo) {
        return reviewRepository
                .findByUser_UserIdAndBlindStatusOrderByCreatedAtDesc(
                        userId, BlindStatus.NORMAL, PageRequest.of(page, size))
                .map(review -> MyReviewResponse.from(review, demo))
                .getContent();
    }

    /**
     * 가입. 이메일 인증 토큰을 맨 먼저 본다 — 인증하지 않은 주소로는 가입 여부·차단 여부를
     * 되묻는 것조차 할 수 없게 하려는 것이다. 토큰은 커밋이 확정된 뒤에 소비한다.
     * 중간에 실패하면 다시 인증하지 않고 바로 재시도할 수 있다.
     */
    @Transactional
    public void signUp(SignUpRequest request) {
        Instant verifiedAt = emailVerificationService.requireVerified(
                request.verificationToken(), request.email());

        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("이미 가입된 이메일입니다.");
        }
        // 해지 이력이 있는 주소(약관 4조 4항). 탈퇴 사실이 드러나지 않게 사유는 말하지 않는다.
        if (withdrawnEmailService.blocked(request.email())) {
            throw new IllegalStateException("현재 가입할 수 없는 이메일입니다.");
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
        }
        validateRequiredAgreements(request.agreements());

        User user = userRepository.save(User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .emailVerifiedAt(verifiedAt)
                .build());

        List<UserAgreement> agreements = request.agreements().stream()
                .map(a -> UserAgreement.builder()
                        .user(user)
                        .termCode(a.termCode())
                        .agreed(a.agreed())
                        .build())
                .toList();
        userAgreementRepository.saveAll(agreements);

        afterCommit(() -> emailVerificationService.consume(request.verificationToken()));
    }

    @Transactional(readOnly = true)
    public TokenResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.email())
                .orElseThrow(() -> new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new BadCredentialsException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return issueTokens(user.getUserId());
    }

    /**
     * Refresh Token 회전(rotation): 쓰인 토큰은 즉시 폐기하고 새 쌍을 발급한다.
     * 탈취된 토큰이 나중에 재사용되면 이미 삭제된 상태라 통과하지 못한다.
     */
    public TokenResponse reissue(String refreshToken) {
        UUID userId = refreshTokenRepository.findUserId(refreshToken)
                .orElseThrow(() -> new BadCredentialsException("유효하지 않은 refresh token 입니다."));

        refreshTokenRepository.delete(refreshToken);
        return issueTokens(userId);
    }

    /**
     * Refresh Token은 삭제해 재발급을 막고, Access Token은 남은 만료 시간만큼
     * 블랙리스트에 올려 즉시 무효화한다. 둘 중 하나만 해서는 로그아웃이 완결되지 않는다.
     */
    public void logout(String accessToken, String refreshToken) {
        refreshTokenRepository.delete(refreshToken);
        if (accessToken != null) {
            tokenBlacklistRepository.blacklist(accessToken, jwtProvider.getExpiresAt(accessToken));
        }
    }

    /**
     * 회원 수정. nickname은 항상 오고(변경이 없으면 기존 값), 비밀번호는
     * pastPassword / newPassword가 함께 왔을 때만 바꾼다.
     *
     * 비밀번호를 바꾸면 그 계정의 Refresh Token을 전부 폐기한다 —
     * 계정이 탈취된 상태에서 비밀번호만 바꾸면 공격자의 세션이 그대로 살아남기 때문이다.
     * 요청을 보낸 기기는 계속 쓸 수 있도록 새 토큰 쌍을 발급해 돌려준다.
     *
     * @return 새로 발급된 토큰 쌍
     */
    @Transactional
    public TokenResponse updateMe(UUID userId, String currentAccessToken, UserPatchRequest request) {
        if (request.hasPartialPasswordChange()) {
            throw new IllegalArgumentException("비밀번호를 변경하려면 기존 비밀번호와 새 비밀번호를 함께 보내야 합니다.");
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 사용자입니다."));

        if (!user.getNickname().equals(request.nickname())) {
            if (userRepository.existsByNickname(request.nickname())) {
                throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
            }
            user.changeNickname(request.nickname());
        }

        if (request.hasPasswordChange()) {
            if (!passwordEncoder.matches(request.pastPassword(), user.getPassword())) {
                throw new IllegalArgumentException("기존 비밀번호가 올바르지 않습니다.");
            }
            user.changePassword(passwordEncoder.encode(request.newPassword()));

            refreshTokenRepository.deleteAllByUserId(userId);
            if (currentAccessToken != null) {
                tokenBlacklistRepository.blacklist(
                        currentAccessToken, jwtProvider.getExpiresAt(currentAccessToken));
            }
        }
        return issueTokens(userId);
    }

    @Transactional(readOnly = true)
    public boolean isNicknameTaken(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    private TokenResponse issueTokens(UUID userId) {
        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(refreshToken, userId);
        return new TokenResponse(accessToken, refreshToken);
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

    /** 필수 약관이 모두 동의(true) 상태로 넘어왔는지 확인한다. */
    private void validateRequiredAgreements(List<AgreementRequest> agreements) {
        Map<TermCode, Boolean> agreed = agreements.stream()
                .collect(Collectors.toMap(AgreementRequest::termCode,
                        AgreementRequest::agreed,
                        (first, second) -> second));

        for (TermCode required : EnumSet.allOf(TermCode.class)) {
            if (!Boolean.TRUE.equals(agreed.get(required))) {
                throw new IllegalArgumentException("필수 약관에 동의해야 합니다: " + required);
            }
        }
    }
}
