package com.server.walwang_be.security;

/** Authorization 헤더에서 Bearer 토큰을 꺼내는 공용 로직. */
public final class BearerTokenResolver {

    public static final String PREFIX = "Bearer ";

    private BearerTokenResolver() {
    }

    /** @return 토큰 문자열, 헤더가 없거나 형식이 맞지 않으면 null */
    public static String resolve(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith(PREFIX)) {
            return null;
        }
        String token = authorizationHeader.substring(PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }
}
