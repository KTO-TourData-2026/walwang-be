package com.server.walwang_be.config;

import com.server.walwang_be.security.JwtAuthenticationEntryPoint;
import com.server.walwang_be.security.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * JwtAuthenticationFilter가 서블릿 필터로 한 번 더 등록되지 않게 막는다.
     *
     * @Component인 필터는 스프링 부트가 보안 체인과 별개로 모든 요청에 거는 서블릿 필터로도 등록한다.
     * 앱 API에서는 OncePerRequestFilter가 두 번째 실행을 건너뛰어 티가 안 났지만,
     * JWT 필터가 없는 관리자 체인에서는 체인이 끝난 "뒤에" 이 필터가 돌아
     * Bearer 헤더의 회원으로 SecurityContext를 덮어쓴다. 그러면 접속기록의 operator가
     * 관리자가 아니라 그 회원으로 바뀔 수 있다. 이 필터는 아래 체인 안에서만 돈다.
     *
     * AdminJwtIsolationTest가 이 동작을 고정한다.
     */
    @Bean
    public FilterRegistrationBean<JwtAuthenticationFilter> jwtAuthenticationFilterRegistration() {
        FilterRegistrationBean<JwtAuthenticationFilter> registration =
                new FilterRegistrationBean<>(jwtAuthenticationFilter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
                // 토큰 기반이라 CSRF/폼로그인/세션이 모두 필요 없다.
                .csrf(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                        // 본문 파싱 실패 등으로 ERROR 디스패치될 때 401로 둔갑하지 않도록 열어둔다.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/user/signUp", "/user/login", "/user/reissue",
                                "/user/email-verification/send", "/user/email-verification/confirm").permitAll()
                        .requestMatchers(HttpMethod.GET, "/user/check-email", "/user/check-nickname").permitAll()
                        // /user/logout 포함 나머지는 인증 필요
                        .anyRequest().authenticated())
                .exceptionHandling(handling ->
                        handling.authenticationEntryPoint(jwtAuthenticationEntryPoint))
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
