package com.server.walwang_be.controller;

import com.server.walwang_be.dto.AdminAccessLogRow;
import com.server.walwang_be.dto.AdminPagination;
import com.server.walwang_be.dto.AdminReportListItem;
import com.server.walwang_be.dto.AdminUserListItem;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.security.AccessLogEnforcementInterceptor;
import com.server.walwang_be.security.AdminAuthenticationProvider;
import com.server.walwang_be.security.AdminOperator;
import com.server.walwang_be.security.ClientIpResolver;
import com.server.walwang_be.service.AccessLogService;
import com.server.walwang_be.service.AdminModerationService;
import com.server.walwang_be.service.AdminRecomputeService;
import com.server.walwang_be.service.AdminTerminationService;
import com.server.walwang_be.service.AdminUserService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 페이지. 서버에서 그린다(Mustache).
 *
 * 개인정보를 보여주는 핸들러는 모두 같은 순서를 지킨다.
 *   1. audit(...) — 접속기록을 커밋한다. 실패하면 예외로 끝나 화면이 나가지 않는다.
 *   2. 데이터를 읽어 모델에 담는다.
 *
 * AOP나 인터셉터로 한꺼번에 기록하지 않고 여기서 직접 부르는 이유는
 * docs/admin-access-log.md "기록 방식" 참고. 빠뜨리면 AccessLogEnforcementInterceptor가 막는다.
 *
 * 쓰기 작업(이용계약 해지, 리뷰 임시조치·해제, 가게 재집계)은 순서가 다르다 — 기록을 먼저 커밋하지 않고
 * 변경과 같은 트랜잭션에 담는다(AdminTerminationService 주석). 그래서 audit(...)을 쓰지 않는다.
 * 전체 재집계만 예외로, 서비스가 실행 요청을 먼저 커밋한다(AdminRecomputeService 주석).
 *
 * 뷰 이름만 돌려준다. @ResponseBody를 쓰면 위 인터셉터가 막기 전에 본문이 나간다.
 */
@Hidden
@Controller
@RequestMapping("/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int QUERY_MAX = 100;
    private static final int PAGE_MAX = 10_000;

    private final AdminUserService adminUserService;
    private final AdminTerminationService adminTerminationService;
    private final AdminModerationService adminModerationService;
    private final AdminRecomputeService adminRecomputeService;
    private final AccessLogService accessLogService;
    private final ClientIpResolver clientIpResolver;

    /** 모든 화면의 머리글(로그아웃 폼)에 필요한 값. */
    @ModelAttribute
    public void commonAttributes(HttpServletRequest request, Authentication authentication, Model model) {
        CsrfToken csrf = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        model.addAttribute("csrfParameter", csrf == null ? "_csrf" : csrf.getParameterName());
        model.addAttribute("csrfToken", csrf == null ? "" : csrf.getToken());
        boolean signedIn = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> AdminAuthenticationProvider.ROLE.equals(authority.getAuthority()));
        model.addAttribute("operator", signedIn ? authentication.getName() : "");
    }

    @GetMapping("/login")
    public String login(@RequestParam(required = false) String error,
                        @RequestParam(required = false) String logout,
                        @RequestParam(required = false) String expired,
                        HttpServletRequest request,
                        Model model) {
        model.addAttribute("title", "로그인");
        model.addAttribute("error", error != null);
        model.addAttribute("loggedOut", logout != null);
        model.addAttribute("expired", expired != null);
        // nginx 헤더 설정이 맞는지 눈으로 확인하는 용도. 허용목록 안에서만 보이는 화면이다.
        model.addAttribute("clientIp", clientIpResolver.resolve(request));
        return "admin/login";
    }

    @GetMapping("/users")
    public String users(@RequestParam(required = false) String q,
                        @RequestParam(defaultValue = "0") int page,
                        HttpServletRequest request,
                        Authentication authentication,
                        Model model) {
        String query = normalizeQuery(q);
        int pageIndex = clampPage(page);

        if (query.isEmpty()) {
            audit(request, authentication, AccessAction.LIST_USERS, null,
                    "회원 목록 page=" + (pageIndex + 1));
        } else {
            audit(request, authentication, AccessAction.SEARCH_USERS, null,
                    "회원 검색 q=" + query + " page=" + (pageIndex + 1));
        }

        Page<AdminUserListItem> users = adminUserService.listUsers(query, pageIndex);
        model.addAttribute("title", "회원 목록");
        model.addAttribute("query", query);
        model.addAttribute("users", users.getContent());
        model.addAttribute("pagination", AdminPagination.of(users, target -> UriComponentsBuilder
                .fromPath("/admin/users")
                .queryParamIfPresent("q", query.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(query))
                .queryParam("page", target)
                .encode()
                .toUriString()));
        return "admin/users";
    }

    @GetMapping("/users/{userId}")
    public String user(@PathVariable UUID userId,
                       HttpServletRequest request,
                       Authentication authentication,
                       Model model) {
        audit(request, authentication, AccessAction.VIEW_USER, userId, "회원 상세(리뷰·저장·도장 요약)");

        model.addAttribute("title", "회원 상세");
        model.addAttribute("user", adminUserService.getUser(userId));
        return "admin/user-detail";
    }

    /**
     * 이용계약 해지(강제 탈퇴). 되돌릴 수 없다 — 리뷰·저장·도장·여권이 지워지고
     * Supabase 의 사진·도장 파일도 사라지며, 같은 이메일은 30일간 재가입이 막힌다.
     *
     * 여기서는 audit(...)을 부르지 않는다. 그 헬퍼는 접속기록을 따로 먼저 커밋하는데,
     * 그러면 삭제가 실패했을 때 기록만 남는다. 기록은 서비스가 삭제와 같은 트랜잭션에서 남긴다.
     *
     * 실패하면 예외로 끝난다. 그때는 postHandle 이 돌지 않으므로 인터셉터가 오류 화면을 막지 않는다.
     */
    @PostMapping("/users/{userId}/terminate")
    public String terminateUser(@PathVariable UUID userId,
                                @RequestParam String reason,
                                @RequestParam String confirmNickname,
                                HttpServletRequest request,
                                Authentication authentication,
                                RedirectAttributes redirect) {
        String operator = AdminOperator.require(authentication);
        String nickname = adminTerminationService.terminate(
                userId, reason, confirmNickname, operator, clientIpResolver.resolve(request));
        // 기록은 위 트랜잭션 안에서 이미 커밋됐다. 인터셉터에는 남겼다는 사실만 알린다.
        AccessLogEnforcementInterceptor.markRecorded(request);

        redirect.addFlashAttribute("terminated", nickname);
        return "redirect:/admin/users";
    }

    // ── 신고와 임시조치 ────────────────────────────────────────

    @GetMapping("/reports")
    public String reports(@RequestParam(defaultValue = "0") int page,
                          HttpServletRequest request,
                          Authentication authentication,
                          Model model) {
        int pageIndex = clampPage(page);
        audit(request, authentication, AccessAction.VIEW_REPORTS, null,
                "신고 목록 page=" + (pageIndex + 1));

        Page<AdminReportListItem> reports = adminModerationService.listReports(pageIndex);
        model.addAttribute("title", "신고");
        model.addAttribute("reports", reports.getContent());
        model.addAttribute("temporaryMeasureDays", AdminModerationService.TEMPORARY_MEASURE.toDays());
        model.addAttribute("pagination", AdminPagination.of(reports, target -> UriComponentsBuilder
                .fromPath("/admin/reports")
                .queryParam("page", target)
                .encode()
                .toUriString()));
        return "admin/reports";
    }

    /**
     * 신고된 리뷰 상세. 이 화면만 리뷰 본문과 사진을 보여준다 —
     * 약관 11조의 판단 대상이 본문과 사진이라 보지 않고는 정할 수 없다.
     * 그래서 대상 회원(작성자)과 함께 남긴다. 판단 근거는 docs/admin-access-log.md 참고.
     */
    @GetMapping("/reports/{reviewId}")
    public String reportedReview(@PathVariable UUID reviewId,
                                 HttpServletRequest request,
                                 Authentication authentication,
                                 Model model) {
        // 기록이 먼저다. 대상 회원은 아직 모르므로(리뷰를 읽어야 안다) 리뷰 id를 detail에 남긴다.
        audit(request, authentication, AccessAction.VIEW_REPORTED_REVIEW, null,
                "신고 리뷰 상세(본문·사진 포함) 리뷰 " + reviewId);

        model.addAttribute("title", "신고 리뷰");
        model.addAttribute("review", adminModerationService.getReportedReview(reviewId));
        return "admin/reported-review";
    }

    /**
     * 임시조치와 해제. 되돌릴 수 있는 작업이라 확인 단계는 두지 않고, 사유만 받는다.
     *
     * 해지와 같은 이유로 audit(...)을 쓰지 않는다 — 기록은 서비스가 상태 변경·재집계와
     * 같은 트랜잭션에서 남긴다.
     */
    @PostMapping("/reports/{reviewId}/blind")
    public String blindReview(@PathVariable UUID reviewId,
                              @RequestParam String reason,
                              HttpServletRequest request,
                              Authentication authentication,
                              RedirectAttributes redirect) {
        return applyModeration(reviewId, reason, request, authentication, redirect, true);
    }

    @PostMapping("/reports/{reviewId}/unblind")
    public String unblindReview(@PathVariable UUID reviewId,
                                @RequestParam String reason,
                                HttpServletRequest request,
                                Authentication authentication,
                                RedirectAttributes redirect) {
        return applyModeration(reviewId, reason, request, authentication, redirect, false);
    }

    private String applyModeration(UUID reviewId, String reason, HttpServletRequest request,
                                   Authentication authentication, RedirectAttributes redirect,
                                   boolean blind) {
        String operator = AdminOperator.require(authentication);
        String ip = clientIpResolver.resolve(request);

        AdminModerationService.Applied applied = blind
                ? adminModerationService.blind(reviewId, reason, operator, ip)
                : adminModerationService.unblind(reviewId, reason, operator, ip);
        AccessLogEnforcementInterceptor.markRecorded(request);

        redirect.addFlashAttribute("moderated", (blind ? "블라인드" : "블라인드 해제") + " 처리했습니다.");
        redirect.addFlashAttribute("moderatedStore", applied.storeName());
        redirect.addFlashAttribute("moderatedCounted", applied.countedReviews());
        return "redirect:/admin/reports";
    }

    // ── 점수 재집계 ───────────────────────────────────────────

    /**
     * 재집계 화면. 입력 폼과 전체 재집계 진행 상태만 있고 개인정보를 보여주지 않는다
     * (실행한 계정도 싣지 않는다 — 접속기록에 있다). 그래서 이 화면은 기록하지 않고
     * 인터셉터에만 그 판단을 알린다. 여기에 회원·리뷰 정보를 더하면 audit(...)부터 넣는다.
     */
    @GetMapping("/recompute")
    public String recompute(HttpServletRequest request, Model model) {
        AccessLogEnforcementInterceptor.markRecorded(request);

        model.addAttribute("title", "점수 재집계");
        model.addAttribute("reasonMax", AdminRecomputeService.REASON_MAX);
        model.addAttribute("job", adminRecomputeService.status());
        return "admin/recompute";
    }

    /**
     * 가게 하나를 다시 센다. 되돌릴 필요가 없는 작업이라(다시 세면 같은 값) 확인 단계 없이 사유만 받는다.
     * 기록은 서비스가 재집계와 같은 트랜잭션에서 남긴다.
     */
    @PostMapping("/recompute/store")
    public String recomputeStore(@RequestParam UUID storeId,
                                 @RequestParam String reason,
                                 HttpServletRequest request,
                                 Authentication authentication,
                                 RedirectAttributes redirect) {
        String operator = AdminOperator.require(authentication);
        AdminRecomputeService.StoreResult result = adminRecomputeService.recomputeStore(
                storeId, reason, operator, clientIpResolver.resolve(request));
        AccessLogEnforcementInterceptor.markRecorded(request);

        redirect.addFlashAttribute("recomputedStore", result.storeName());
        redirect.addFlashAttribute("recomputedReal", result.realCounted());
        redirect.addFlashAttribute("recomputedDemo", result.demoCounted());
        return "redirect:/admin/recompute";
    }

    /**
     * 전체 재집계를 백그라운드로 시작하고 곧바로 돌아온다. 결과는 재집계 화면을 새로 고쳐 본다.
     * 기록("실행 요청")은 서비스가 시작 전에 커밋한다.
     */
    @PostMapping("/recompute/all")
    public String recomputeAll(@RequestParam String reason,
                               HttpServletRequest request,
                               Authentication authentication,
                               RedirectAttributes redirect) {
        String operator = AdminOperator.require(authentication);
        adminRecomputeService.startRecomputeAll(reason, operator, clientIpResolver.resolve(request));
        AccessLogEnforcementInterceptor.markRecorded(request);

        redirect.addFlashAttribute("recomputeAllStarted", true);
        return "redirect:/admin/recompute";
    }

    /**
     * 접속기록 열람도 기록한다. 화면에 다른 관리자의 IP와 정보주체 id가 나오고,
     * 특정 회원으로 거르면 "누가 그 사람을 봤는지"를 조회하는 셈이라 대상도 함께 남긴다.
     */
    @GetMapping("/access-logs")
    public String accessLogs(@RequestParam(required = false) String operator,
                             @RequestParam(required = false) UUID targetUserId,
                             @RequestParam(required = false) String action,
                             @RequestParam(defaultValue = "0") int page,
                             HttpServletRequest request,
                             Authentication authentication,
                             Model model) {
        String operatorFilter = AdminOperator.sanitize(operator);
        AccessAction actionFilter = parseAction(action);
        int pageIndex = clampPage(page);

        StringBuilder detail = new StringBuilder("접속기록 열람 page=").append(pageIndex + 1);
        if (!operatorFilter.isEmpty()) {
            detail.append(" operator=").append(operatorFilter);
        }
        if (targetUserId != null) {
            detail.append(" targetUserId=").append(targetUserId);
        }
        if (actionFilter != null) {
            detail.append(" action=").append(actionFilter.name());
        }
        audit(request, authentication, AccessAction.VIEW_ACCESS_LOG, targetUserId, detail.toString());

        Page<AdminAccessLogRow> logs =
                accessLogService.find(operatorFilter, targetUserId, actionFilter, pageIndex);
        model.addAttribute("title", "접속기록");
        model.addAttribute("operatorFilter", operatorFilter);
        model.addAttribute("targetUserIdFilter", targetUserId == null ? "" : targetUserId.toString());
        model.addAttribute("actionFilter", actionFilter == null ? "" : actionFilter.name());
        model.addAttribute("actionOptions", actionOptions(actionFilter));
        model.addAttribute("logs", logs.getContent());
        model.addAttribute("pagination", AdminPagination.of(logs, target -> UriComponentsBuilder
                .fromPath("/admin/access-logs")
                .queryParamIfPresent("operator",
                        operatorFilter.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(operatorFilter))
                .queryParamIfPresent("targetUserId", java.util.Optional.ofNullable(targetUserId))
                .queryParamIfPresent("action", java.util.Optional.ofNullable(actionFilter).map(Enum::name))
                .queryParam("page", target)
                .encode()
                .toUriString()));
        return "admin/access-logs";
    }

    /**
     * 수행업무 필터. 모르는 값은 거르지 않는 것으로 본다.
     *
     * 400으로 끊지 않는 이유는 계정 필터(AdminOperator.sanitize)와 같다 — 링크를 잘못 고친 정도로
     * 화면을 닫을 이유가 없고, 필터가 무시됐다는 것은 선택 상자가 "전체"로 돌아온 것으로 드러난다.
     */
    private static AccessAction parseAction(String action) {
        if (action == null || action.isBlank()) {
            return null;
        }
        for (AccessAction candidate : AccessAction.values()) {
            if (candidate.name().equals(action.strip())) {
                return candidate;
            }
        }
        return null;
    }

    /** 선택 상자에 넣을 값들. 지금 고른 것에 selected를 달아 돌려준다. */
    private static List<ActionOption> actionOptions(AccessAction selected) {
        return Arrays.stream(AccessAction.values())
                .map(action -> new ActionOption(action.name(), action.label(), action == selected))
                .toList();
    }

    /** Mustache가 쓰는 선택 상자 한 줄. */
    public record ActionOption(String value, String label, boolean selected) {
    }

    /**
     * operator는 SecurityContext를 다시 뒤지지 않고, 관리자 권한이 붙은 인증에서만 꺼낸다(AdminOperator).
     */
    private void audit(HttpServletRequest request, Authentication authentication,
                       AccessAction action, UUID targetUserId, String detail) {
        String operator = AdminOperator.require(authentication);
        accessLogService.record(operator, clientIpResolver.resolve(request), action, targetUserId, detail);
        AccessLogEnforcementInterceptor.markRecorded(request);
    }

    private static String normalizeQuery(String q) {
        if (q == null) {
            return "";
        }
        String trimmed = q.strip();
        return trimmed.length() <= QUERY_MAX ? trimmed : trimmed.substring(0, QUERY_MAX);
    }

    private static int clampPage(int page) {
        return Math.min(Math.max(page, 0), PAGE_MAX);
    }
}
