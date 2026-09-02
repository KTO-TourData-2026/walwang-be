package com.server.walwang_be.controller;

import com.server.walwang_be.dto.LoginRequest;
import com.server.walwang_be.dto.CourseRenameRequest;
import com.server.walwang_be.dto.MyReviewResponse;
import com.server.walwang_be.dto.PassportDetailResponse;
import com.server.walwang_be.dto.PassportSummaryResponse;
import com.server.walwang_be.dto.RefreshTokenResponse;
import com.server.walwang_be.dto.SignUpRequest;
import com.server.walwang_be.dto.TokenRequest;
import com.server.walwang_be.dto.TokenResponse;
import com.server.walwang_be.dto.SavedCourseListResponse;
import com.server.walwang_be.dto.StoreCardResponse;
import com.server.walwang_be.dto.UserPatchRequest;
import com.server.walwang_be.dto.UserProfileResponse;
import com.server.walwang_be.security.BearerTokenResolver;
import com.server.walwang_be.service.CourseService;
import com.server.walwang_be.service.PassportService;
import com.server.walwang_be.service.StoreService;
import com.server.walwang_be.service.UserService;
import com.server.walwang_be.service.UserWithdrawalService;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
@Validated
public class UserController {

    private final UserService userService;
    private final UserWithdrawalService userWithdrawalService;
    private final PassportService passportService;
    private final StoreService storeService;
    private final CourseService courseService;

    @PostMapping("/signUp")
    public ResponseEntity<Void> signUp(@Valid @RequestBody SignUpRequest request) {
        userService.signUp(request);
        return ResponseEntity.ok().build();
    }

    /** 성공 시 200 + Authorization 헤더(access) + 본문 {refreshToken}. */
    @PostMapping("/login")
    public ResponseEntity<RefreshTokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return toTokenResponse(userService.login(request));
    }

    /** 만료된 access로도 호출할 수 있어야 하므로 인증을 걸지 않는다. */
    @PostMapping("/reissue")
    public ResponseEntity<RefreshTokenResponse> reissue(@Valid @RequestBody TokenRequest request) {
        return toTokenResponse(userService.reissue(request.refreshToken()));
    }

    /** 중복 없으면 200, 중복이면 409. */
    @GetMapping("/check-email")
    public ResponseEntity<Void> checkEmail(@RequestParam @NotBlank @Email String email) {
        return userService.isEmailTaken(email)
                ? ResponseEntity.status(HttpStatus.CONFLICT).build()
                : ResponseEntity.ok().build();
    }

    /** 중복 없으면 200, 중복이면 409. */
    @GetMapping("/check-nickname")
    public ResponseEntity<Void> checkNickname(@RequestParam @NotBlank String nickname) {
        return userService.isNicknameTaken(nickname)
                ? ResponseEntity.status(HttpStatus.CONFLICT).build()
                : ResponseEntity.ok().build();
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody TokenRequest request) {
        userService.logout(BearerTokenResolver.resolve(authorization), request.refreshToken());
        return ResponseEntity.ok().build();
    }

    /**
     * 회원 수정. 인증이 필요하며, userId는 Access Token의 sub에서 온다.
     * 미인증이면 여기까지 오지 않고 JwtAuthenticationEntryPoint가 401을 낸다.
     *
     * 응답으로 항상 새 토큰 쌍을 준다 — 비밀번호를 바꾸면 기존 토큰이 모두 폐기되므로
     * 앱은 응답의 토큰으로 갈아끼우기만 하면 된다.
     */
    @PatchMapping("/me")
    public ResponseEntity<RefreshTokenResponse> updateMe(
            @AuthenticationPrincipal UUID userId,
            @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody UserPatchRequest request) {
        TokenResponse tokens = userService.updateMe(
                userId, BearerTokenResolver.resolve(authorization), request);
        return toTokenResponse(tokens);
    }

    /**
     * 회원 탈퇴. 작성한 리뷰·저장한 가게·코스가 함께 사라지고,
     * 쓰던 토큰도 즉시 무효화된다. 되돌릴 수 없다.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal UUID userId,
            @Parameter(hidden = true) @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        userWithdrawalService.withdraw(userId, BearerTokenResolver.resolve(authorization));
        return ResponseEntity.ok().build();
    }

    /** 마이 화면. 닉네임과 함께 리뷰 수·모은 도장 수·저장 장소 수가 나간다. */
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> findMe(@AuthenticationPrincipal UUID userId) {
        return ResponseEntity.ok(userService.findMe(userId));
    }

    /** 내가 쓴 리뷰 목록. 최신순. */
    @GetMapping("/me/reviews")
    public ResponseEntity<List<MyReviewResponse>> findMyReviews(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(userService.findMyReviews(userId, page, size));
    }

    /** 내가 저장한 가게 목록. 최근에 저장한 것부터. */
    @GetMapping("/store")
    public ResponseEntity<List<StoreCardResponse>> findSavedStores(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok(storeService.findSavedStores(userId, page, size));
    }

    /** 저장한 코스 목록. 카드에 필요한 것만 담고 경로선은 빼서 내려준다. */
    @GetMapping("/me/saved-courses")
    public ResponseEntity<SavedCourseListResponse> findMySavedCourses(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "false") boolean demo) {
        return ResponseEntity.ok(new SavedCourseListResponse(
                courseService.findMyCourses(userId, demo)));
    }

    /** 저장한 코스 제목 수정. 남의 코스면 403, 없으면 404. */
    @PatchMapping("/me/saved-courses/{courseId}")
    public ResponseEntity<Void> renameSavedCourse(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID courseId,
            @Valid @RequestBody CourseRenameRequest request) {
        courseService.rename(userId, courseId, request.title());
        return ResponseEntity.ok().build();
    }

    /** 여권 미리보기. 최신 도장부터. */
    @GetMapping("/me/passport")
    public ResponseEntity<List<PassportSummaryResponse>> findMyPassport(
            @AuthenticationPrincipal UUID userId,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "10") @Min(1) @Max(10) int size) {
        return ResponseEntity.ok(passportService.findMine(userId, page, size));
    }

    /** 여권 도장 상세. 남의 여권이면 403. */
    @GetMapping("/me/passport/{passportId}")
    public ResponseEntity<PassportDetailResponse> findMyPassportDetail(
            @AuthenticationPrincipal UUID userId,
            @PathVariable UUID passportId) {
        return ResponseEntity.ok(passportService.findOne(userId, passportId));
    }

    private ResponseEntity<RefreshTokenResponse> toTokenResponse(TokenResponse tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenResolver.PREFIX + tokens.accessToken())
                .body(new RefreshTokenResponse(tokens.refreshToken()));
    }
}
