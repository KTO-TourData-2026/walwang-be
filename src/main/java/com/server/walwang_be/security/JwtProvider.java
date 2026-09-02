package com.server.walwang_be.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.springframework.stereotype.Component;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Access Token 발급/검증. RS256 비대칭 서명이라
 * 발급은 개인키로, 검증은 공개키로만 이루어진다.
 *
 * payload: { "sub": <user_id>, "iat": <발급 시각>, "exp": <만료 시각> }
 */
@Component
public class JwtProvider {

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final Duration accessTokenValidity;

    public JwtProvider(JwtProperties properties) {
        this.privateKey = PemKeyLoader.loadPrivateKey(properties.privateKey());
        this.publicKey = PemKeyLoader.loadPublicKey(properties.publicKey());
        this.accessTokenValidity = Duration.ofSeconds(properties.accessTokenValidity());
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTokenValidity)))
                .signWith(privateKey, Jwts.SIG.RS256)
                .compact();
    }

    /**
     * 서명과 만료를 검증하고 sub(user_id)를 돌려준다.
     *
     * @throws JwtException 서명 불일치, 만료, 형식 오류 등 검증 실패
     */
    public UUID parseUserId(String token) {
        return UUID.fromString(parseClaims(token).getSubject());
    }

    /** 블랙리스트 TTL 계산용. */
    public Instant getExpiresAt(String token) {
        return parseClaims(token).getExpiration().toInstant();
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(publicKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
