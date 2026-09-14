package com.server.walwang_be.controller;

import com.server.walwang_be.dto.AdminAccessLogRow;
import com.server.walwang_be.dto.AdminUserDetail;
import com.server.walwang_be.dto.AdminReportListItem;
import com.server.walwang_be.dto.AdminReportedReviewDetail;
import com.server.walwang_be.dto.AdminUserListItem;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.service.AdminModerationService;
import com.server.walwang_be.service.AdminTerminationService;
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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 개인정보를 보여주는 화면마다 "기록이 먼저, 조회는 그다음"이 지켜지는지.
 */
class AdminControllerTest extends AdminWebTestSupport {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID REVIEW_ID = UUID.fromString("dddddddd-eeee-ffff-0000-111111111111");

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
                .andExpect(content().string(containsString("실루엣 도장")))
                // 해지 폼은 상세 화면 안에 있고, 접힌 상태로 나간다.
                .andExpect(content().string(containsString("/admin/users/" + USER_ID + "/terminate")))
                .andExpect(content().string(containsString("name=\"confirmNickname\"")));

        InOrder order = inOrder(accessLogService, adminUserService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_USER, USER_ID, "회원 상세(리뷰·저장·도장 요약)");
        order.verify(adminUserService).getUser(USER_ID);
    }

    @Test
    void 접속기록_열람도_기록된다() throws Exception {
        given(accessLogService.find("bob", USER_ID, null, 0)).willReturn(new PageImpl<>(List.of(
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
        order.verify(accessLogService).find("bob", USER_ID, null, 0);
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

    // ── 이용계약 해지 ───────────────────────────────────────────

    @Test
    void 해지는_사유와_확인_닉네임을_서비스로_넘기고_목록으로_돌아간다() throws Exception {
        given(adminTerminationService.terminate(USER_ID, "약관 12조 위반", "멍멍", USERNAME, ALLOWED_IP))
                .willReturn("멍멍");
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/users/" + USER_ID + "/terminate")
                        .param("reason", "약관 12조 위반")
                        .param("confirmNickname", "멍멍")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/users"))
                // 목록 화면이 누구를 해지했는지 알려준다. 다시 조회하지 않고 이미 읽은 닉네임을 쓴다.
                .andExpect(flash().attribute("terminated", "멍멍"));

        verify(adminTerminationService).terminate(USER_ID, "약관 12조 위반", "멍멍", USERNAME, ALLOWED_IP);
    }

    /**
     * 기록은 서비스가 삭제와 같은 트랜잭션에서 남기므로 컨트롤러는 accessLogService를 부르지 않는다.
     * 여기서 record(...)가 불리면 기록이 따로 커밋된다는 뜻이고, 그러면 원자성이 깨진다.
     */
    @Test
    void 해지는_접속기록을_따로_먼저_커밋하지_않는다() throws Exception {
        given(adminTerminationService.terminate(any(), any(), any(), any(), any())).willReturn("멍멍");
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/users/" + USER_ID + "/terminate")
                        .param("reason", "약관 12조 위반")
                        .param("confirmNickname", "멍멍")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().is3xxRedirection());

        verify(accessLogService, never()).record(any(), any(), eq(AccessAction.TERMINATE_USER), any(), any());
        verify(accessLogService, never()).recordInCurrentTransaction(any(), any(), any(), any(), any());
    }

    @Test
    void 확인_닉네임이_다르면_400_화면이_나가고_이유를_알려준다() throws Exception {
        willThrow(new AdminTerminationService.TerminationRejected("닉네임이 일치하지 않습니다. 해지를 실행하지 않았습니다."))
                .given(adminTerminationService).terminate(any(), any(), any(), any(), any());
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/users/" + USER_ID + "/terminate")
                        .param("reason", "약관 12조 위반")
                        .param("confirmNickname", "다른닉네임")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("닉네임이 일치하지 않습니다")));
    }

    @Test
    void 사유가_없으면_서비스를_부르지_않고_400_화면() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/users/" + USER_ID + "/terminate")
                        .param("confirmNickname", "멍멍")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("text/html"));

        verify(adminTerminationService, never()).terminate(any(), any(), any(), any(), any());
    }

    @Test
    void GET_으로는_해지할_수_없다() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/users/" + USER_ID + "/terminate")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isMethodNotAllowed());

        verify(adminTerminationService, never()).terminate(any(), any(), any(), any(), any());
    }

    // ── 신고와 임시조치 ─────────────────────────────────────────

    @Test
    void 신고_목록은_기록한_뒤에_조회한다() throws Exception {
        given(adminModerationService.listReports(0)).willReturn(new PageImpl<>(List.of(
                new AdminReportListItem(REVIEW_ID, 3, "2026-09-13 09:00:00", "정상",
                        false, "", false, "테스트 카페", "멍멍", "2026-09-01 10:00:00", "실제",
                        "욕설 (2건), 광고")),
                PageRequest.of(0, 20), 1));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/reports").session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("욕설 (2건), 광고")))
                .andExpect(content().string(containsString("/admin/reports/" + REVIEW_ID)));

        InOrder order = inOrder(accessLogService, adminModerationService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_REPORTS, null,
                "신고 목록 page=1");
        order.verify(adminModerationService).listReports(0);
    }

    /** 본문·사진을 보여주는 화면이라 조회가 기록보다 앞서면 안 된다. */
    @Test
    void 신고_리뷰_상세는_기록한_뒤에_본문을_읽고_이스케이프해_보여준다() throws Exception {
        given(adminModerationService.getReportedReview(REVIEW_ID)).willReturn(
                new AdminReportedReviewDetail(REVIEW_ID, USER_ID, "멍멍", "테스트 카페",
                        "2026-09-01 10:00:00", "신고된 본문<script>", true,
                        SUPABASE_URL + "/storage/v1/object/public/review-photos/a.jpg",
                        "소·중형", "가능", "실제", "정상", false, "", "", false,
                        2, List.of(new AdminReportedReviewDetail.ReasonRow("욕설", 2, "2026-09-13 09:00:00"))));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/reports/" + REVIEW_ID).session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("신고된 본문&lt;script&gt;")))
                .andExpect(content().string(not(containsString("신고된 본문<script>"))))
                .andExpect(content().string(containsString("review-photos/a.jpg")))
                .andExpect(content().string(containsString("/admin/reports/" + REVIEW_ID + "/blind")));

        InOrder order = inOrder(accessLogService, adminModerationService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_REPORTED_REVIEW,
                null, "신고 리뷰 상세(본문·사진 포함) 리뷰 " + REVIEW_ID);
        order.verify(adminModerationService).getReportedReview(REVIEW_ID);
    }

    @Test
    void 블라인드는_사유를_서비스로_넘기고_신고_목록으로_돌아간다() throws Exception {
        given(adminModerationService.blind(REVIEW_ID, "약관 11조 위반", USERNAME, ALLOWED_IP))
                .willReturn(new AdminModerationService.Applied(REVIEW_ID, "테스트 카페", 2));
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/reports/" + REVIEW_ID + "/blind")
                        .param("reason", "약관 11조 위반")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/reports"))
                .andExpect(flash().attribute("moderatedCounted", 2L));

        verify(adminModerationService).blind(REVIEW_ID, "약관 11조 위반", USERNAME, ALLOWED_IP);
        // 기록은 서비스가 상태 변경·재집계와 같은 트랜잭션에서 남긴다.
        verify(accessLogService, never()).record(any(), any(), eq(AccessAction.BLIND_REVIEW), any(), any());
    }

    @Test
    void 해제는_unblind를_부른다() throws Exception {
        given(adminModerationService.unblind(REVIEW_ID, "이의제기 수용", USERNAME, ALLOWED_IP))
                .willReturn(new AdminModerationService.Applied(REVIEW_ID, "테스트 카페", 3));
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/reports/" + REVIEW_ID + "/unblind")
                        .param("reason", "이의제기 수용")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(redirectedUrl("/admin/reports"));

        verify(adminModerationService).unblind(REVIEW_ID, "이의제기 수용", USERNAME, ALLOWED_IP);
    }

    @Test
    void 상태가_맞지_않으면_400_화면이_나가고_이유를_알려준다() throws Exception {
        willThrow(new AdminModerationService.ModerationRejected(
                "정상 노출 중인 리뷰만 블라인드할 수 있습니다. 아무것도 바꾸지 않았습니다."))
                .given(adminModerationService).blind(any(), any(), any(), any());
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/reports/" + REVIEW_ID + "/blind")
                        .param("reason", "약관 11조 위반")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(content().string(containsString("정상 노출 중인 리뷰만")));
    }

    @Test
    void 처리_사유가_없으면_서비스를_부르지_않고_400_화면() throws Exception {
        MockHttpSession session = login();

        mockMvc.perform(post("/admin/reports/" + REVIEW_ID + "/blind")
                        .session(session).with(fromAllowedIp()).with(csrf()))
                .andExpect(status().isBadRequest());

        verify(adminModerationService, never()).blind(any(), any(), any(), any());
    }

    // ── 접속기록 필터 ───────────────────────────────────────────

    @Test
    void 접속기록은_수행업무로도_거를_수_있고_그_조건도_기록된다() throws Exception {
        given(accessLogService.find("", null, AccessAction.TERMINATE_USER, 0))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/access-logs").param("action", "TERMINATE_USER")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isOk())
                // 선택 상자가 고른 값을 유지한다.
                .andExpect(content().string(containsString("value=\"TERMINATE_USER\" selected")));

        InOrder order = inOrder(accessLogService);
        order.verify(accessLogService).record(USERNAME, ALLOWED_IP, AccessAction.VIEW_ACCESS_LOG, null,
                "접속기록 열람 page=1 action=TERMINATE_USER");
        order.verify(accessLogService).find("", null, AccessAction.TERMINATE_USER, 0);
    }

    /** 링크를 잘못 고친 정도로 화면을 닫지 않는다. 필터만 무시한다. */
    @Test
    void 모르는_수행업무_값은_무시하고_전체를_보여준다() throws Exception {
        given(accessLogService.find("", null, null, 0))
                .willReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
        MockHttpSession session = login();

        mockMvc.perform(get("/admin/access-logs").param("action", "NOT_AN_ACTION")
                        .session(session).with(fromAllowedIp()))
                .andExpect(status().isOk());

        verify(accessLogService).find("", null, null, 0);
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
