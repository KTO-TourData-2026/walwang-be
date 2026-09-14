package com.server.walwang_be.config;

import com.server.walwang_be.repo.AdminLoginAttemptRepository;
import com.server.walwang_be.security.AdminAccountRegistry;
import com.server.walwang_be.security.AdminAuthenticationHandlers;
import com.server.walwang_be.security.AdminAuthenticationProvider;
import com.server.walwang_be.security.AdminIpAllowlistFilter;
import com.server.walwang_be.security.ClientIpResolver;
import com.server.walwang_be.security.IpAllowlist;
import com.server.walwang_be.security.TotpVerifier;
import com.server.walwang_be.service.AccessLogService;
import jakarta.servlet.SessionCookieConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.servlet.CookieSameSiteSupplier;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.savedrequest.NullRequestCache;
import org.springframework.security.web.session.DisableEncodeUrlFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 관리자 페이지(/admin/**) 전용 보안 체인. 앱 API 체인(SecurityConfig)과 완전히 분리한다.
 *
 * 분리하지 않으면 앱 체인의 anyRequest().authenticated()가 /admin 에도 걸려,
 * 앱에 로그인한 회원 누구나 관리자 화면을 열 수 있게 된다. @Order(1)로 먼저 매칭시킨다.
 *
 * 들어오는 순서:
 *   1. IP 허용목록 (밖이면 404, 로그인 화면도 안 보인다)
 *   2. 아이디 + 비밀번호 + OTP 폼 로그인 → 세션
 *   3. ROLE_ADMIN 확인
 *
 * 설정이 비었거나 틀리면 닫힌다: 허용목록이 비면 전부 404, 계정이 없으면 아무도 로그인하지 못한다.
 * 판단 근거는 docs/admin-access-log.md 참고.
 */
@Slf4j
@Configuration
// SupabaseProperties 도 함께 건다. 앱은 @ConfigurationPropertiesScan 으로 묶지만
// 관리자 화면 테스트(@WebMvcTest)는 애플리케이션 클래스를 올리지 않아 그 스캔이 돌지 않는다.
@EnableConfigurationProperties({AdminProperties.class, SupabaseProperties.class})
public class AdminSecurityConfig {

    /**
     * 인라인 스크립트·스타일을 쓰지 않는다. 회원 닉네임 같은 값이 이스케이프를 뚫더라도 실행되지 않게 한다.
     *
     * img-src 에는 리뷰 사진이 있는 Supabase 오리진이 더해진다(contentSecurityPolicy 참고).
     * 신고 리뷰 상세에서 사진을 봐야 임시조치를 판단할 수 있기 때문이다.
     * default-src 'none' 은 그대로라 스크립트·fetch·프레임은 여전히 전부 막힌다.
     */
    private static final List<String> CONTENT_SECURITY_POLICY = List.of(
            "default-src 'none'",
            "style-src 'self'",
            "form-action 'self'",
            "frame-ancestors 'none'",
            "base-uri 'none'");

    @Bean
    @Order(1)
    public SecurityFilterChain adminSecurityFilterChain(HttpSecurity http,
                                                        AdminProperties properties,
                                                        Environment environment,
                                                        ClientIpResolver clientIpResolver,
                                                        AdminAccountRegistry accountRegistry,
                                                        AdminLoginAttemptRepository loginAttempts,
                                                        TotpVerifier totpVerifier,
                                                        PasswordEncoder passwordEncoder,
                                                        AccessLogService accessLogService,
                                                        SupabaseProperties supabaseProperties) throws Exception {
        AdminAuthenticationHandlers handlers = new AdminAuthenticationHandlers(accessLogService, clientIpResolver);
        AdminAuthenticationProvider provider = new AdminAuthenticationProvider(
                accountRegistry, loginAttempts, totpVerifier, passwordEncoder);

        return http
                .securityMatcher("/admin/**")
                // 체인의 맨 앞(DisableEncodeUrlFilter 앞)에 둔다. CSRF·세션·로그인보다 먼저 거른다.
                .addFilterBefore(new AdminIpAllowlistFilter(allowlist(properties, environment), clientIpResolver),
                        DisableEncodeUrlFilter.class)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/admin/login", "/admin/admin.css").permitAll()
                        .anyRequest().hasRole("ADMIN"))
                .authenticationManager(new ProviderManager(provider))
                .formLogin(form -> form
                        .loginPage("/admin/login")
                        .loginProcessingUrl("/admin/login")
                        .authenticationDetailsSource(AdminAuthenticationProvider.detailsSource(clientIpResolver))
                        .successHandler(handlers)
                        .failureHandler(handlers))
                .logout(logout -> logout
                        .logoutUrl("/admin/logout")
                        .logoutSuccessHandler(handlers))
                .httpBasic(AbstractHttpConfigurer::disable)
                // 로그인 전 요청을 세션에 담아 두지 않는다. 로그인하면 늘 회원 목록으로 간다.
                .requestCache(cache -> cache.requestCache(new NullRequestCache()))
                .sessionManagement(session -> session
                        .sessionFixation(fixation -> fixation.changeSessionId())
                        // 한 계정은 한 곳에서만. 다른 곳에서 로그인하면 먼저 연 세션이 끊긴다.
                        .sessionConcurrency(concurrency -> concurrency
                                .maximumSessions(1)
                                .expiredUrl("/admin/login?expired")))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(
                                contentSecurityPolicy(supabaseProperties)))
                        // 상세 URL에 회원 id가 들어 있다. 다른 사이트로 넘어갈 때 따라가지 않게 한다.
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
                .build();
    }

    /**
     * 리뷰 사진을 보여주려면 그 파일이 있는 오리진을 img-src 에 넣어야 한다.
     *
     * 경로까지는 적지 않는다 — 버킷 이름이 바뀌어도 화면이 조용히 깨지지 않게 오리진만 연다.
     * 설정이 비었거나 주소 모양이 아니면 'self' 만 남긴다. 닫히는 쪽으로 틀리면 사진이 안 보일 뿐이고,
     * 열리는 쪽으로 틀리면 CSP 에 엉뚱한 값이 들어간다.
     */
    static String contentSecurityPolicy(SupabaseProperties properties) {
        List<String> directives = new ArrayList<>(CONTENT_SECURITY_POLICY);
        directives.add(storageOrigin(properties)
                .map(origin -> "img-src 'self' " + origin)
                .orElse("img-src 'self'"));
        return String.join("; ", directives);
    }

    private static Optional<String> storageOrigin(SupabaseProperties properties) {
        if (properties == null || properties.url() == null || properties.url().isBlank()) {
            log.warn("supabase.url 이 비어 있어 관리자 화면에서 리뷰 사진을 표시할 수 없습니다.");
            return Optional.empty();
        }
        try {
            URI uri = URI.create(properties.url().strip());
            if (uri.getScheme() == null || uri.getHost() == null) {
                throw new IllegalArgumentException("scheme 이나 host 가 없습니다");
            }
            String origin = uri.getPort() < 0
                    ? "%s://%s".formatted(uri.getScheme(), uri.getHost())
                    : "%s://%s:%d".formatted(uri.getScheme(), uri.getHost(), uri.getPort());
            return Optional.of(origin);
        } catch (IllegalArgumentException e) {
            log.warn("supabase.url 을 오리진으로 읽을 수 없어 리뷰 사진을 표시하지 않습니다: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * server.forward-headers-strategy=framework 이면 ForwardedHeaderFilter가 X-Forwarded-For의
     * 왼쪽 끝으로 getRemoteAddr()를 바꿔 버린다. 클라이언트가 헤더로 허용 IP를 사칭할 수 있으므로 닫는다.
     */
    private static IpAllowlist allowlist(AdminProperties properties, Environment environment) {
        String strategy = environment.getProperty("server.forward-headers-strategy", "");
        if ("framework".equalsIgnoreCase(strategy.trim())) {
            log.error("server.forward-headers-strategy=framework 에서는 클라이언트 IP를 믿을 수 없어 "
                    + "/admin 을 닫습니다. native 로 바꾸거나 설정을 지우세요.");
            return IpAllowlist.denyAll();
        }
        IpAllowlist allowlist = IpAllowlist.of(properties.allowlist());
        if (allowlist.size() == 0) {
            log.warn("관리자 IP 허용목록(ADMIN_ALLOWLIST)이 비어 있습니다. /admin 은 모든 요청에 404를 줍니다.");
        } else {
            log.info("관리자 IP 허용목록 {}개를 불러왔습니다.", allowlist.size());
        }
        return allowlist;
    }

    /** 동시 세션 제한이 끝난 세션을 알아차리려면 세션 종료 이벤트가 필요하다. */
    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 세션 쿠키. 앱 API는 세션을 쓰지 않으므로 이 설정은 사실상 관리자 페이지에만 해당한다.
     *
     * Secure: TLS는 nginx에서 끝나 톰캣은 요청을 http로 보므로, 자동 판단에 맡기면 Secure가 빠진다.
     *         (브라우저는 localhost 에서는 Secure 쿠키를 http로도 보내므로 로컬 개발은 그대로 된다.)
     * Path:   /admin 밖으로는 쿠키가 나가지 않는다.
     */
    @Bean
    public ServletContextInitializer adminSessionCookieInitializer() {
        return servletContext -> {
            SessionCookieConfig cookie = servletContext.getSessionCookieConfig();
            cookie.setHttpOnly(true);
            cookie.setSecure(true);
            cookie.setPath("/admin");
        };
    }

    /** 다른 사이트에서 시작한 요청에는 세션 쿠키를 싣지 않는다(CSRF 방어를 한 겹 더). */
    @Bean
    public CookieSameSiteSupplier adminCookieSameSiteSupplier() {
        return CookieSameSiteSupplier.ofStrict();
    }
}
