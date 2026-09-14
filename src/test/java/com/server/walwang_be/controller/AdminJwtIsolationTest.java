package com.server.walwang_be.controller;

import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.security.JwtAuthenticationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ApplicationContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * JwtAuthenticationFilter 이중 등록 문제를 고정한다.
 *
 * 문제: @Component 필터라 스프링 부트가 서블릿 필터로도 등록했다. JWT 필터가 없는 관리자 체인에서는
 * 보안 체인이 끝난 "뒤에" 이 필터가 돌아, Bearer 헤더에 담긴 앱 회원으로 SecurityContext를 덮어썼다.
 * 관리자 세션을 가진 요청에 앱 JWT를 붙이면 컨트롤러가 보는 인증이 그 회원으로 바뀐다.
 *
 * 두 겹으로 막는다.
 *   1. SecurityConfig가 서블릿 필터 등록을 끈다(원인 제거).
 *   2. AdminOperator가 ROLE_ADMIN이 아닌 인증으로는 기록·조회를 거부한다(원인이 되살아나도 다른 사람 이름으로는 안 남는다).
 *
 * 1번을 되돌리면(registration.setEnabled(true)) 첫 테스트가 200 대신 403으로 실패한다.
 */
class AdminJwtIsolationTest extends AdminWebTestSupport {

    @Autowired
    ApplicationContext applicationContext;

    @Test
    void 관리자_세션에_앱_JWT를_붙여도_operator는_관리자_그대로다() throws Exception {
        given(adminUserService.listUsers("", 0)).willReturn(Page.empty(PageRequest.of(0, 20)));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users")
                        .session(session)
                        .with(fromAllowedIp())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN))
                .andExpect(status().isOk())
                // 머리글의 계정 이름도 관리자다.
                .andExpect(content().string(containsString("<span>" + USERNAME + "</span>")));

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.LIST_USERS), isNull(), anyString());
        verify(accessLogService, never()).record(eq(APP_USER_ID.toString()), any(), any(), any(), any());
        verify(adminUserService).listUsers("", 0);
    }

    @Test
    void 접속기록_열람에서도_operator가_바뀌지_않는다() throws Exception {
        given(accessLogService.find("", null, null, 0)).willReturn(Page.empty(PageRequest.of(0, 50)));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/access-logs")
                        .session(session)
                        .with(fromAllowedIp())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN))
                .andExpect(status().isOk());

        verify(accessLogService).record(eq(USERNAME), eq(ALLOWED_IP), eq(AccessAction.VIEW_ACCESS_LOG), isNull(), anyString());
        verify(accessLogService, never()).record(eq(APP_USER_ID.toString()), any(), any(), any(), any());
    }

    @Test
    void JWT_필터는_서블릿_필터로_따로_등록되지_않는다() {
        var registrations = applicationContext.getBeansOfType(FilterRegistrationBean.class).values().stream()
                .filter(registration -> registration.getFilter() instanceof JwtAuthenticationFilter)
                .toList();

        assertThat(registrations).hasSize(1);
        assertThat(registrations.get(0).isEnabled()).isFalse();
    }

    @Test
    void 앱_API_체인에서는_JWT가_여전히_동작한다() throws Exception {
        // 이 슬라이스에는 앱 컨트롤러가 없다. 인증을 통과하면 404, 못 하면 401이다.
        mockMvc.perform(get("/user/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + APP_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/user/me"))
                .andExpect(status().isUnauthorized());
    }
}
