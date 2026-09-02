package com.server.walwang_be.service;

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
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    /** 마이 화면. 리뷰·도장·저장 개수는 목록 API가 페이징이라 여기서 따로 센다. */
    @Transactional(readOnly = true)
    public UserProfileResponse findMe(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new BadCredentialsException("존재하지 않는 사용자입니다."));

        return UserProfileResponse.of(user,
                reviewRepository.countByUser_UserId(userId),
                passportRepository.countByUser_UserId(userId),
                savedStoreRepository.countByUser_UserId(userId));
    }

    /** 내가 쓴 리뷰 목록. 최신순. */
    @Transactional(readOnly = true)
    public List<MyReviewResponse> findMyReviews(Long userId, int page, int size) {
        return reviewRepository
                .findByUser_UserIdOrderByCreatedAtDesc(userId, PageRequest.of(page, size))
                .map(MyReviewResponse::from)
                .getContent();
    }

    @Transactional
    public void signUp(SignUpRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new IllegalStateException("이미 가입된 이메일입니다.");
        }
        if (userRepository.existsByNickname(request.nickname())) {
            throw new IllegalStateException("이미 사용 중인 닉네임입니다.");
        }
        validateRequiredAgreements(request.agreements());

        User user = userRepository.save(User.builder()
                .email(request.email())
                .password(passwordEncoder.encode(request.password()))
                .nickname(request.nickname())
                .build());

        List<UserAgreement> agreements = request.agreements().stream()
                .map(a -> UserAgreement.builder()
                        .user(user)
                        .termCode(a.termCode())
                        .agreed(a.agreed())
                        .build())
                .toList();
        userAgreementRepository.saveAll(agreements);
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
        Long userId = refreshTokenRepository.findUserId(refreshToken)
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
    public TokenResponse updateMe(Long userId, String currentAccessToken, UserPatchRequest request) {
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
    public boolean isEmailTaken(String email) {
        return userRepository.existsByEmail(email);
    }

    @Transactional(readOnly = true)
    public boolean isNicknameTaken(String nickname) {
        return userRepository.existsByNickname(nickname);
    }

    private TokenResponse issueTokens(Long userId) {
        String accessToken = jwtProvider.createAccessToken(userId);
        String refreshToken = UUID.randomUUID().toString();
        refreshTokenRepository.save(refreshToken, userId);
        return new TokenResponse(accessToken, refreshToken);
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
