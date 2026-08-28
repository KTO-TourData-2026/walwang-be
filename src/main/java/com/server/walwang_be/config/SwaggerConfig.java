package com.server.walwang_be.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    @Bean
    public OpenAPI openAPI(){
        // 1. 보안 스키마 이름 지정
        String jwtSchemeName = "jwtAuth";

        // 2. 모든 API에 이 인증을 적용하겠다는 요구사항 설정
        SecurityRequirement securityRequirement = new SecurityRequirement().addList(jwtSchemeName);

        // 3. 우측 상단 자물쇠 UI(Components) 설정
        Components components = new Components()
                .addSecuritySchemes(jwtSchemeName, new SecurityScheme()
                        .name(jwtSchemeName)
                        .type(SecurityScheme.Type.HTTP) // HTTP 기반
                        .scheme("bearer")               // Bearer 방식
                        .bearerFormat("JWT"));          // 포맷은 JWT

        // 4. 기존 코드에 Components와 SecurityItem을 얹어서 리턴!
        return new OpenAPI()
                .addServersItem(new Server().url("/"))
                .components(components)               // 💡 자물쇠 UI 추가
                .addSecurityItem(securityRequirement) // 💡 모든 API에 자물쇠 달아주기
                .info(apiInfo());
    }

    private Info apiInfo(){
        return new Info()
                .title("CLBACK API")
                .description("TalkUp 종프1")
                .version("1.0.0");
    }
}