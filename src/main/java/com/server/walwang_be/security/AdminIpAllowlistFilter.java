package com.server.walwang_be.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 관리자 체인의 맨 앞. 허용목록 밖에서 온 요청은 로그인 화면조차 보지 못한다.
 *
 * 403이 아니라 404를 준다 — 관리자 페이지가 있다는 사실부터 알려주지 않는다.
 *
 * 차단은 access_log에 넣지 않고 서버 로그에만 남긴다. 인터넷 스캐너가 /admin을 수시로 두드리는데,
 * 그걸 전부 DB에 쓰면 아무나 행을 무한히 늘릴 수 있고, 트리거 때문에 1년간 지우지도 못한다.
 * 로그인 실패는 허용된 IP에서만 일어나고 잠금이 걸려 있어 그런 문제가 없다.
 *
 * @Component로 등록하지 않는다. 필터 빈은 스프링 부트가 서블릿 필터로도 자동 등록해서
 * 모든 경로(앱 API 포함)에 걸려 버린다 — JwtAuthenticationFilter가 겪은 문제와 같다.
 * AdminSecurityConfig가 직접 만들어 관리자 체인에만 넣는다.
 */
@Slf4j
public class AdminIpAllowlistFilter extends OncePerRequestFilter {

    private static final int HEADER_LOG_MAX = 200;

    private final IpAllowlist allowlist;
    private final ClientIpResolver clientIpResolver;

    public AdminIpAllowlistFilter(IpAllowlist allowlist, ClientIpResolver clientIpResolver) {
        this.allowlist = allowlist;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String ip = clientIpResolver.resolve(request);
        if (!allowlist.allows(ip)) {
            // 헤더 설정 점검용으로 원본 값을 같이 남긴다. docs/admin-access-log.md "nginx 헤더 확인" 참고.
            log.info("관리자 페이지 IP 차단: ip={} remoteAddr={} xff={} path={}",
                    ip, request.getRemoteAddr(), truncate(request.getHeader("X-Forwarded-For")),
                    request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= HEADER_LOG_MAX ? value : value.substring(0, HEADER_LOG_MAX) + "…";
    }
}
