package com.server.walwang_be.service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** 관리자 화면에 내보낼 값. Mustache는 서식을 못 다루므로 문자열로 만들어 넘긴다. */
public final class AdminViewFormats {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Seoul"));

    private AdminViewFormats() {
    }

    public static String dateTime(Instant instant) {
        return instant == null ? "-" : DATE_TIME.format(instant);
    }

    /**
     * 목록용 이메일 가리기. ab***@gmail.com
     *
     * 목록 조회는 여러 사람이 한꺼번에 보여 접속기록에 정보주체를 특정해 남길 수 없다.
     * 그래서 목록에서는 가리고, 원문은 대상이 기록되는 상세 화면에서만 보인다.
     * 도메인은 남긴다 — 문의 대응 때 어느 계정인지 좁히는 데 필요하다.
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        int at = email.lastIndexOf('@');
        if (at <= 0) {
            return email.charAt(0) + "***";
        }
        String local = email.substring(0, at);
        int visible = local.length() <= 2 ? 1 : 2;
        return local.substring(0, visible) + "***" + email.substring(at);
    }
}
