package com.server.walwang_be.config;

import com.server.walwang_be.security.AccessLogEnforcementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminWebConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AccessLogEnforcementInterceptor())
                .addPathPatterns("/admin/**")
                // 로그인 화면은 개인정보를 보여주지 않는다. 로그인 시도는 AdminAuthenticationHandlers가 남긴다.
                .excludePathPatterns("/admin/login");
    }
}
