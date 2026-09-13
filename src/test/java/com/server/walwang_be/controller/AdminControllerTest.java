package com.server.walwang_be.controller;

import com.server.walwang_be.dto.AdminAccessLogRow;
import com.server.walwang_be.dto.AdminUserDetail;
import com.server.walwang_be.dto.AdminUserListItem;
import com.server.walwang_be.entity.AccessAction;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockHttpSession;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개인정보를 보여주는 화면마다 "기록이 먼저, 조회는 그다음"이 지켜지는지.
 */
class AdminControllerTest extends AdminWebTestSupport {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Test
    void 회원_목록은_기록한_뒤에_조회하고_이메일은_가려서_보여준다() throws Exception {
        given(adminUserService.listUsers("", 0)).willReturn(new PageImpl<>(List.of(
                new AdminUserListItem(USER_ID, "ab***@example.com", "멍멍<script>", "2026-09-01 10:00:00", 3)),
                PageRequest.of(0, 20), 1));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users").session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("ab***@example.com")))
                // Mustache가 이스케이프한다.
                .andExpect(content().string(containsString("멍멍&lt;script&gt;")))
                .andExpect(content().string(not(containsString("멍멍<script>"))))
                .andExpect(content().string(containsString("/admin/users/" + USER_ID)));

        InOrder order = inOrder(accessLogService, adminUserService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.LIST_USERS, null, "회원 목록 page=1");
        order.verify(adminUserService).listUsers("", 0);
    }

    @Test
    void 검색은_검색어와_함께_기록된다() throws Exception {
        given(adminUserService.listUsers("kim", 1)).willReturn(new PageImpl<>(List.of(), PageRequest.of(1, 20), 20));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users").param("q", " kim ").param("page", "1")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isOk());

        InOrder order = inOrder(accessLogService, adminUserService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.SEARCH_USERS, null, "회원 검색 q=kim page=2");
        order.verify(adminUserService).listUsers("kim", 1);
    }

    @Test
    void 회원_상세는_대상_회원과_함께_기록한_뒤에_조회한다() throws Exception {
        given(adminUserService.getUser(USER_ID)).willReturn(new AdminUserDetail(
                USER_ID, "abcdef@example.com", "멍멍", "2026-09-01 10:00:00",
                2, List.of(new AdminUserDetail.CountRow("실제 · 정상", 2)),
                List.of(new AdminUserDetail.RecentReview("2026-09-02 11:00:00", "테스트 카페", "소·중형", "가능", "정상", "실제")),
                1, 1, List.of(new AdminUserDetail.CourseRow("2026-09-03 12:00:00", "주말 코스", "실제")),
                1, List.of(new AdminUserDetail.CountRow("실루엣 도장", 1))));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users/" + USER_ID).session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("abcdef@example.com")))
                .andExpect(content().string(containsString("테스트 카페")))
                .andExpect(content().string(containsString("주말 코스")))
                .andExpect(content().string(containsString("실루엣 도장")));

        InOrder order = inOrder(accessLogService, adminUserService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_USER, USER_ID, "회원 상세(리뷰·저장·도장 요약)");
        order.verify(adminUserService).getUser(USER_ID);
    }

    @Test
    void 접속기록_열람도_기록된다() throws Exception {
        given(accessLogService.find("bob", USER_ID, 0)).willReturn(new PageImpl<>(List.of(
                new AdminAccessLogRow(7, "2026-09-13 09:00:00", "bob", "203.0.113.20", true, USER_ID.toString(),
                        "VIEW_USER", "회원 상세 조회", "회원 상세(리뷰·저장·도장 요약)"),
                new AdminAccessLogRow(6, "2026-09-13 08:59:00", "bob", "203.0.113.20", false, "",
                        "LOGIN_SUCCESS", "로그인", "관리자 로그인")),
                PageRequest.of(0, 50), 2));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/access-logs").param("operator", "bob").param("targetUserId", USER_ID.toString())
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("회원 상세 조회")))
                .andExpect(content().string(containsString("203.0.113.20")));

        InOrder order = inOrder(accessLogService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_ACCESS_LOG, USER_ID,
                "접속기록 열람 page=1 operator=bob targetUserId=" + USER_ID);
        order.verify(accessLogService).find("bob", USER_ID, 0);
    }

    @Test
    void 기록에_실패하면_조회하지_않고_화면도_보여주지_않는다() throws Exception {
        MockHttpSession session = login();
        willThrow(new IllegalStateException("DB 연결 실패"))
                .given(accessLogService).record(eq(USERNAME), any(), eq(AccessAction.VIEW_USER), any(), any());

        mockMvc.perform(get("/admin/users/" + USER_ID).session(session).with(fromAllowedIp()))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(containsString("화면을 표시하지 않았습니다")));

        verify(adminUserService, never()).getUser(any());
    }

    @Test
    void 회원_ID_형식이_틀리면_조회하지_않고_400_화면() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users/not-a-uuid").session(session).with(fromAllowedIp()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        verify(adminUserService, never()).getUser(any());
    }
}
