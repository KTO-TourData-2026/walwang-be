package com.server.walwang_be.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

/**
 * 관리자 페이지 접속기록. 「개인정보의 안전성 확보조치 기준」 제8조가 요구하는
 * 계정 / 접속일시 / 접속지 / 처리한 정보주체 / 수행업무를 한 행에 담는다.
 *
 * 한 번 쓰면 바꾸지 않는다. @Immutable로 앱이 UPDATE를 내지 않게 하고,
 * DB 쪽에서도 트리거가 UPDATE와 1년이 안 된 행의 DELETE를 막는다(db/schema-access-log.sql).
 *
 * target_user_id에 users FK를 걸지 않는다. 탈퇴는 행을 실제로 지우는데,
 * FK가 있으면 탈퇴가 막히거나 접속기록이 함께 지워져 1년 보관이 깨진다.
 */
@Entity
@Table(name = "access_log")
@Immutable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AccessLog {

    public static final int OPERATOR_MAX = 50;
    public static final int IP_MAX = 45;
    public static final int DETAIL_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "log_id")
    private Long logId;

    /** 관리자 아이디. 로그인 실패 행이면 입력된 문자열이다. */
    @Column(nullable = false, length = OPERATOR_MAX)
    private String operator;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;

    /** nginx 가 붙인 X-Forwarded-For 의 오른쪽 끝 값. ClientIpResolver 참고. */
    @Column(nullable = false, length = IP_MAX)
    private String ip;

    /** 처리한 정보주체. 목록·검색·로그인처럼 한 사람으로 정해지지 않으면 비어 있다. */
    @Column(name = "target_user_id")
    private UUID targetUserId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private AccessAction action;

    /** 어느 화면에서 무엇을 했는지. 검색어와 페이지 번호가 여기 들어간다. */
    @Column(length = DETAIL_MAX)
    private String detail;

    @Builder
    private AccessLog(String operator, String ip, UUID targetUserId,
                      AccessAction action, String detail) {
        this.operator = operator;
        this.accessedAt = Instant.now();
        this.ip = ip;
        this.targetUserId = targetUserId;
        this.action = action;
        this.detail = detail;
    }
}
