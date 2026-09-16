package com.server.walwang_be.controller;

import com.server.walwang_be.entity.AccessAction;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 관리자 페이지가 인증 없이 열리지 않는지. 여기가 뚫리면 회원 정보가 통째로 나간다.
 */
class AdminSecurityTest extends AdminWebTestSupport {

    // ── IP 허용목록 ─────────────────────────────────────────────

    @Test
    void 허용목록_밖에서는_로그인_화면도_404이고_DB에_기록하지_않는다() throws Exception {
        mockMvc.perform(get("/admin/login").with(viaNginx(OUTSIDE_IP)))
                .andExpect(status().isNotFound());

        verifyNoInteractions(accessLogService, adminUserService);
    }

    @Test
    void X_Forwarded_For_앞에_허용IP를_끼워_넣어도_막힌다() throws Exception {
        mockMvc.perform(get("/admin/login").with(viaNginx(ALLOWED_IP + ", " + OUTSIDE_IP)))
                .andExpect(status().isNotFound());
    }

    @Test
    void 프록시를_거치지_않은_요청이_보낸_X_Forwarded_For는_믿지_않는다() throws Exception {
        mockMvc.perform(get("/admin/login").with(request -> {
                    request.setRemoteAddr(OUTSIDE_IP);
                    request.addHeader("X-Forwarded-For", ALLOWED_IP);
                    return request;
                }))
                .andExpect(status().isNotFound());
    }

    @Test
    void 허용목록_밖에서는_올바른_계정으로도_로그인할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/login").with(viaNginx(OUTSIDE_IP)).with(csrf())
                        .param("username", USERNAME).param("password", PASSWORD).param("code", currentCode()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(accessLogService, loginAttempts);
    }

    @Test
    void 허용IP에서는_로그인_화면이_열리고_인식한_IP를_보여준다() throws Exception {
        mockMvc.perform(get("/admin/login").with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(ALLOWED_IP)))
                .andExpect(content().string(containsString("name=\"code\"")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));

        verifyNoInteractions(accessLogService);
    }

    // ── 인증 ───────────────────────────────────────────────────

    @Test
    void 로그인하지_않으면_로그인_화면으로_보낸다() throws Exception {
        mockMvc.perform(get("/admin/users").with(fromAllowedIp()))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminUserService, accessLogService);
    }

    @Test
    void 앱_회원의_JWT만으로는_관리자_화면에_들어갈_수_없다() throws Exception {
        mockMvc.perform(get("/admin/users").with(fromAllowedIp())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminUserService, accessLogService);
    }

    @Test
    void 아이디_비밀번호_OTP가_맞으면_로그인되고_기록된다() throws Exception {
        MockHttpSession session = login();

        verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.LOGIN_SUCCESS, null, "관리자 로그인");
        verify(loginAttempts).clearFailures(USERNAME, ALLOWED_IP);
        // 고시 제6조 자동 접속차단
        assertThat(session.getMaxInactiveInterval()).isEqualTo(30 * 60);
    }

    @Test
    void 비밀번호가_틀리면_LOGIN_FAILURE로_기록한다() throws Exception {
        mockMvc.perform(post("/admin/login").with(fromAllowedIp()).with(csrf())
                        .param("username", USERNAME).param("password", "wrong").param("code", currentCode()))
                .andExpect(redirectedUrl("/admin/login?error"));

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.LOGIN_FAILURE), isNull(), anyString());
        verify(loginAttempts).recordFailure(USERNAME, ALLOWED_IP);
    }

    @Test
    void 없는_아이디도_같은_응답을_주고_입력값으로_기록한다() throws Exception {
        mockMvc.perform(post("/admin/login").with(fromAllowedIp()).with(csrf())
                        .param("username", "mallory").param("password", PASSWORD).param("code", currentCode()))
                .andExpect(redirectedUrl("/admin/login?error"));

        verify(accessLogService).record(eq("mallory"), eq(ALLOWED_IP), eq(AccessAction.LOGIN_FAILURE), isNull(), anyString());
    }

    @Test
    void 비밀번호는_맞고_OTP가_틀리면_TOTP_FAILURE로_기록한다() throws Exception {
        String staleCode = codeAt(System.currentTimeMillis() / 1000 / 30 - 10);

        mockMvc.perform(post("/admin/login").with(fromAllowedIp()).with(csrf())
                        .param("username", USERNAME).param("password", PASSWORD).param("code", staleCode))
                .andExpect(redirectedUrl("/admin/login?error"));

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.TOTP_FAILURE), isNull(), anyString());
        verify(loginAttempts).recordFailure(USERNAME, ALLOWED_IP);
        verify(accessLogService, never()).record(anyString(), anyString(), eq(AccessAction.LOGIN_SUCCESS), isNull(), anyString());
    }

    @Test
    void 이미_쓴_OTP는_다시_쓸_수_없다() throws Exception {
        given(loginAttempts.markTotpStepUsed(eq(USERNAME), org.mockito.ArgumentMatchers.anyLong())).willReturn(false);

        mockMvc.perform(post("/admin/login").with(fromAllowedIp()).with(csrf())
                        .param("username", USERNAME).param("password", PASSWORD).param("code", currentCode()))
                .andExpect(redirectedUrl("/admin/login?error"));

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.TOTP_FAILURE), isNull(), anyString());
    }

    @Test
    void 잠기면_올바른_값으로도_로그인되지_않고_LOGIN_LOCKED로_기록한다() throws Exception {
        given(loginAttempts.isLocked(USERNAME, ALLOWED_IP)).willReturn(true);

        mockMvc.perform(post("/admin/login").with(fromAllowedIp()).with(csrf())
                        .param("username", USERNAME).param("password", PASSWORD).param("code", currentCode()))
                .andExpect(redirectedUrl("/admin/login?error"));

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.LOGIN_LOCKED), isNull(), anyString());
        // 잠긴 동안에는 확인 자체를 하지 않으므로 실패 횟수도 늘지 않는다.
        verify(loginAttempts, never()).recordFailure(anyString(), anyString());
    }

    @Test
    void CSRF_토큰이_없는_로그인_요청은_처리하지_않는다() throws Exception {
        mockMvc.perform(post("/admin/login").with(fromAllowedIp())
                        .param("username", USERNAME).param("password", PASSWORD).param("code", currentCode()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(accessLogService, loginAttempts);
    }

    /**
     * 해지는 되돌릴 수 없다. 다른 사이트가 시작한 요청으로는 절대 실행되지 않아야 한다.
     * 세션 쿠키의 SameSite=Strict 와 CSP form-action 'self' 가 한 겹 더 막지만,
     * 서버에서도 토큰 없이는 핸들러까지 오지 않는지 여기서 고정한다.
     */
    @Test
    void CSRF_토큰이_없는_해지_요청은_처리하지_않는다() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/terminate")
                        .param("reason", "약관 12조 위반").param("confirmNickname", "멍멍")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminTerminationService);
    }

    @Test
    void 로그인하지_않으면_해지할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/terminate")
                        .param("reason", "약관 12조 위반").param("confirmNickname", "멍멍")
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminTerminationService);
    }

    @Test
    void 허용목록_밖에서는_해지_요청도_404() throws Exception {
        mockMvc.perform(post("/admin/users/" + UUID.randomUUID() + "/terminate")
                        .param("reason", "약관 12조 위반").param("confirmNickname", "멍멍")
                        .with(viaNginx(OUTSIDE_IP)).with(csrf()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(adminTerminationService);
    }

    @Test
    void CSRF_토큰이_없는_블라인드_요청은_처리하지_않는다() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/reports/" + UUID.randomUUID() + "/blind")
                        .param("reason", "약관 11조 위반")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminModerationService);
    }

    // ── 점수 재집계 (예전 /internal, 앱 로그인만으로 부를 수 있었다) ─────────────

    @Test
    void CSRF_토큰이_없는_재집계_요청은_처리하지_않는다() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/recompute/store")
                        .param("storeId", UUID.randomUUID().toString()).param("reason", "목 리뷰 적재")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/admin/recompute/all")
                        .param("reason", "목 리뷰 적재")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(adminRecomputeService);
    }

    @Test
    void 로그인하지_않으면_재집계를_볼_수도_실행할_수도_없다() throws Exception {
        mockMvc.perform(get("/admin/recompute").with(fromAllowedIp()))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/admin/recompute/store")
                        .param("storeId", UUID.randomUUID().toString()).param("reason", "목 리뷰 적재")
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/admin/recompute/all")
                        .param("reason", "목 리뷰 적재")
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminRecomputeService, accessLogService);
    }

    /**
     * 옮긴 이유 그 자체. 앱 회원의 JWT는 관리자 체인에서 아무 권한도 만들지 않는다
     * (관리자 체인에 JWT 필터가 없고, 서블릿 필터 등록도 꺼져 있다 — AdminJwtIsolationTest).
     */
    @Test
    void 앱_회원_JWT로는_재집계를_실행할_수_없다() throws Exception {
        mockMvc.perform(post("/admin/recompute/all")
                        .param("reason", "목 리뷰 적재")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN)
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/admin/recompute/store")
                        .param("storeId", UUID.randomUUID().toString()).param("reason", "목 리뷰 적재")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN)
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminRecomputeService, accessLogService);
    }

    @Test
    void 허용목록_밖에서는_재집계_요청도_404() throws Exception {
        mockMvc.perform(post("/admin/recompute/all")
                        .param("reason", "목 리뷰 적재")
                        .with(viaNginx(OUTSIDE_IP)).with(csrf()))
                .andExpect(status().isNotFound());

        verifyNoInteractions(adminRecomputeService);
    }

    @Test
    void 로그인하지_않으면_신고를_볼_수도_처리할_수도_없다() throws Exception {
        mockMvc.perform(get("/admin/reports").with(fromAllowedIp()))
                .andExpect(redirectedUrl("/admin/login"));
        mockMvc.perform(post("/admin/reports/" + UUID.randomUUID() + "/unblind")
                        .param("reason", "이의제기 수용")
                        .with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login"));

        verifyNoInteractions(adminModerationService, accessLogService);
    }

    @Test
    void 로그아웃은_기록되고_세션이_끊긴다() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/logout").session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/login?logout"));

        verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.LOGOUT, null, "관리자 로그아웃");
        assertThat(session.isInvalid()).isTrue();
    }

    // ── 응답 헤더 ───────────────────────────────────────────────

    /**
     * 리뷰 사진을 보여주려면 img-src 에 그 오리진이 있어야 한다.
     * 열리는 것은 이미지 하나뿐이고 스크립트·fetch·프레임은 default-src 'none' 이 그대로 막는다.
     */
    @Test
    void 리뷰_사진_오리진만_이미지로_허용한다() throws Exception {
        mockMvc.perform(get("/admin/login").with(fromAllowedIp()))
                .andExpect(header().string("Content-Security-Policy",
                        containsString("img-src 'self' " + SUPABASE_URL)))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("Content-Security-Policy", not(containsString("script-src"))));
    }

    @Test
    void 캐시_프레임_스크립트_리퍼러를_막는_헤더가_붙는다() throws Exception {
        mockMvc.perform(get("/admin/login").with(fromAllowedIp()))
                .andExpect(header().string("Content-Security-Policy", containsString("default-src 'none'")))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(header().string("Referrer-Policy", "no-referrer"));
    }
}
