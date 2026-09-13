package com.server.walwang_be.service;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AdminViewFormatsTest {

    @Test
    void 목록용_이메일은_앞_두_글자와_도메인만_남긴다() {
        assertThat(AdminViewFormats.maskEmail("abcdef@example.com")).isEqualTo("ab***@example.com");
        assertThat(AdminViewFormats.maskEmail("ab@example.com")).isEqualTo("a***@example.com");
        assertThat(AdminViewFormats.maskEmail("a@example.com")).isEqualTo("a***@example.com");
        assertThat(AdminViewFormats.maskEmail("noatsign")).isEqualTo("n***");
        assertThat(AdminViewFormats.maskEmail(null)).isEmpty();
    }

    @Test
    void 일시는_한국_시간으로_보여준다() {
        assertThat(AdminViewFormats.dateTime(Instant.parse("2026-09-12T15:30:00Z"))).isEqualTo("2026-09-13 00:30:00");
        assertThat(AdminViewFormats.dateTime(null)).isEqualTo("-");
    }

    @Test
    void 검색어의_LIKE_와일드카드는_문자_그대로_찾는다() {
        assertThat(AdminUserService.likePattern("Kim")).isEqualTo("%kim%");
        assertThat(AdminUserService.likePattern("100%_!")).isEqualTo("%100!%!_!!%");
    }
}
