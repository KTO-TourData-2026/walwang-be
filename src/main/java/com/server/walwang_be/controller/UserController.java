package com.server.walwang_be.controller;

import com.server.walwang_be.dto.LoginRequest;
import com.server.walwang_be.dto.RefreshTokenResponse;
import com.server.walwang_be.dto.SignUpRequest;
import com.server.walwang_be.dto.TokenRequest;
import com.server.walwang_be.dto.TokenResponse;
import com.server.walwang_be.dto.UserPatchRequest;
import com.server.walwang_be.security.BearerTokenResolver;
import com.server.walwang_be.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
    public ResponseEntity<Void> logout(@RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
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
            @AuthenticationPrincipal Long userId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody UserPatchRequest request) {
        TokenResponse tokens = userService.updateMe(
                userId, BearerTokenResolver.resolve(authorization), request);
        return toTokenResponse(tokens);
    }

    private ResponseEntity<RefreshTokenResponse> toTokenResponse(TokenResponse tokens) {
        return ResponseEntity.ok()
                .header(HttpHeaders.AUTHORIZATION, BearerTokenResolver.PREFIX + tokens.accessToken())
                .body(new RefreshTokenResponse(tokens.refreshToken()));
    }
}
