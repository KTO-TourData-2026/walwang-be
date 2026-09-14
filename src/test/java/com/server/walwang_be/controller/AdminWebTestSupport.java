package com.server.walwang_be.controller;

import com.server.walwang_be.config.AdminSecurityConfig;
import com.server.walwang_be.config.SecurityConfig;
import com.server.walwang_be.repo.AdminLoginAttemptRepository;
import com.server.walwang_be.repo.TokenBlacklistRepository;
import com.server.walwang_be.security.AdminAccountRegistry;
import com.server.walwang_be.security.ClientIpResolver;
import com.server.walwang_be.security.JwtAuthenticationEntryPoint;
import com.server.walwang_be.security.JwtProvider;
import com.server.walwang_be.security.TotpVerifier;
import com.server.walwang_be.service.AccessLogService;
import com.server.walwang_be.service.AdminTerminationService;
import com.server.walwang_be.service.AdminUserService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;

/**
 * 관리자 페이지 웹 테스트의 공통 설정.
 *
 * DB·Redis 없이 뜬다(@WebMvcTest). 기록 서비스와 조회 서비스를 목으로 둬서, 테스트를 돌려도
 * 로컬이 보는 개발 DB의 access_log에 행이 쌓이지 않는다(쌓이면 트리거 때문에 1년간 지울 수 없다).
 * 실제 DB 동작은 AdminPostgresTest가 로컬 컨테이너에서 본다.
 *
 * 앱 체인(SecurityConfig)과 관리자 체인(AdminSecurityConfig)을 둘 다 올린다.
 * JwtAuthenticationFilter는 필터 빈이라 @WebMvcTest가 서블릿 필터로 함께 올린다 —
 * 운영과 같은 조건에서 이중 등록 문제를 재현하려면 이게 필요하다.
 *
 * 하위 클래스가 설정을 똑같이 물려받아 컨텍스트 하나를 같이 쓴다.
 */
@WebMvcTest(controllers = AdminController.class)
@Import({SecurityConfig.class, AdminSecurityConfig.class, JwtAuthenticationEntryPoint.class,
        ClientIpResolver.class, TotpVerifier.class, AdminAccountRegistry.class})
abstract class AdminWebTestSupport {

    static final String USERNAME = "alice";
    static final String PASSWORD = "correct horse battery staple";
    static final String TOTP_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    static final String ALLOWED_IP = "203.0.113.10";
    static final String OUTSIDE_IP = "198.51.100.7";

    /** 앱 회원이 로그인해서 받은 Access Token이라고 치는 값. JwtProvider를 목으로 두고 이 값만 통과시킨다. */
    static final String APP_TOKEN = "app-user-access-token";
    static final UUID APP_USER_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private static final String PASSWORD_HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);

    /**
     * 목록 설정은 한 소스에서만 바인딩되므로 계정 값을 모두 여기서 넣는다
     * (@WebMvcTest(properties)와 나눠 넣으면 한쪽이 통째로 무시된다).
     */
    @DynamicPropertySource
    static void adminProperties(DynamicPropertyRegistry registry) {
        registry.add("admin.accounts[0].username", () -> USERNAME);
        registry.add("admin.accounts[0].bcrypt", () -> PASSWORD_HASH);
        registry.add("admin.accounts[0].totp", () -> TOTP_SECRET);
        registry.add("admin.allowlist", () -> ALLOWED_IP);
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AccessLogService accessLogService;
    @MockitoBean
    AdminUserService adminUserService;
    @MockitoBean
    AdminTerminationService adminTerminationService;
    @MockitoBean
    AdminLoginAttemptRepository loginAttempts;
    @MockitoBean
    JwtProvider jwtProvider;
    @MockitoBean
    TokenBlacklistRepository tokenBlacklistRepository;

    @BeforeEach
    void defaultStubs() {
        given(loginAttempts.markTotpStepUsed(anyString(), anyLong())).willReturn(true);
        given(jwtProvider.parseUserId(APP_TOKEN)).willReturn(APP_USER_ID);
    }

    /** nginx가 같은 호스트에서 넘긴 요청. */
    static RequestPostProcessor viaNginx(String forwardedFor) {
        return request -> {
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", forwardedFor);
            return request;
        };
    }

    static RequestPostProcessor fromAllowedIp() {
        return viaNginx(ALLOWED_IP);
    }

    static String currentCode() {
        return codeAt(Instant.now().getEpochSecond() / 30);
    }

    static String codeAt(long timeStep) {
        return TotpVerifier.generate(TotpVerifier.decodeBase32(TOTP_SECRET), timeStep);
    }

    /** 실제 로그인 흐름(IP → CSRF → 아이디·비밀번호·OTP)을 타서 세션을 얻는다. */
    MockHttpSession login() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/login")
                        .with(fromAllowedIp())
                        .with(csrf())
                        .param("username", USERNAME)
                        .param("password", PASSWORD)
                        .param("code", currentCode()))
                .andExpect(redirectedUrl("/admin/users"))
                .andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }
}
