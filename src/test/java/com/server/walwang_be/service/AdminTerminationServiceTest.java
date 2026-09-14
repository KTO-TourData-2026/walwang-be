package com.server.walwang_be.service;

import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.entity.WithdrawalReason;
import com.server.walwang_be.repo.AdminUserQueryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 이용계약 해지. 되돌릴 수 없는 작업이라 "실행하지 않는다"는 판단이 실제로 지켜지는지가 핵심이다.
 *
 * 트랜잭션이 하나로 묶이는지는 여기서 볼 수 없다(@Transactional은 프록시가 거는 것이라 목에서는 돌지 않는다).
 * 여기서는 순서와 실행 여부만 고정하고, 실제 롤백은 AdminPostgresTest가 보는 SQL·제약과
 * AccessLogService.recordInCurrentTransaction의 MANDATORY 설정이 담보한다.
 */
@ExtendWith(MockitoExtension.class)
class AdminTerminationServiceTest {

    private static final UUID USER_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final String OPERATOR = "alice";
    private static final String IP = "203.0.113.10";

    @Mock
    AdminUserQueryRepository adminUserQueryRepository;
    @Mock
    AccessLogService accessLogService;
    @Mock
    UserWithdrawalService userWithdrawalService;

    @InjectMocks
    AdminTerminationService service;

    private void 회원이_있다() {
        given(adminUserQueryRepository.findByUserId(USER_ID)).willReturn(Optional.of(
                User.builder().email("abcdef@example.com").password("hash").nickname("멍멍").build()));
    }

    @Test
    void 기록을_남긴_뒤_TERMINATED로_탈퇴시킨다() {
        회원이_있다();

        assertThat(service.terminate(USER_ID, "약관 12조 위반 — 광고 도배", "멍멍", OPERATOR, IP))
                .isEqualTo("멍멍");

        InOrder order = inOrder(accessLogService, userWithdrawalService);
        order.verify(accessLogService).recordInCurrentTransaction(
                OPERATOR, IP, AccessAction.TERMINATE_USER, USER_ID,
                "이용계약 해지(약관 14조 5항) — 사유: 약관 12조 위반 — 광고 도배");
        // 액세스 토큰은 넘길 것이 없다. 관리자는 그 회원의 토큰을 들고 있지 않다.
        order.verify(userWithdrawalService).withdraw(USER_ID, null, WithdrawalReason.TERMINATED);
    }

    /** 조회 화면이 쓰는 record(REQUIRES_NEW)를 부르면 기록이 따로 커밋돼 원자성이 깨진다. */
    @Test
    void 따로_커밋되는_기록_메서드는_쓰지_않는다() {
        회원이_있다();

        service.terminate(USER_ID, "약관 12조 위반", "멍멍", OPERATOR, IP);

        verify(accessLogService, never()).record(anyString(), anyString(), any(), any(), anyString());
    }

    @Test
    void 확인_닉네임이_다르면_기록도_삭제도_하지_않는다() {
        회원이_있다();

        assertThatThrownBy(() -> service.terminate(USER_ID, "약관 12조 위반", "다른닉네임", OPERATOR, IP))
                .isInstanceOf(AdminTerminationService.TerminationRejected.class)
                .hasMessageContaining("닉네임이 일치하지 않습니다");

        verifyNoInteractions(accessLogService, userWithdrawalService);
    }

    @Test
    void 사유가_비어_있으면_회원을_읽지도_않는다() {
        assertThatThrownBy(() -> service.terminate(USER_ID, "   ", "멍멍", OPERATOR, IP))
                .isInstanceOf(AdminTerminationService.TerminationRejected.class)
                .hasMessageContaining("해지 사유를 입력하세요");

        verifyNoInteractions(adminUserQueryRepository, accessLogService, userWithdrawalService);
    }

    @Test
    void 사유가_200자를_넘으면_실행하지_않는다() {
        String tooLong = "가".repeat(AdminTerminationService.REASON_MAX + 1);

        assertThatThrownBy(() -> service.terminate(USER_ID, tooLong, "멍멍", OPERATOR, IP))
                .isInstanceOf(AdminTerminationService.TerminationRejected.class)
                .hasMessageContaining("200자 이내");

        verifyNoInteractions(adminUserQueryRepository, accessLogService, userWithdrawalService);
    }

    @Test
    void 확인_닉네임이_비어_있으면_실행하지_않는다() {
        assertThatThrownBy(() -> service.terminate(USER_ID, "약관 12조 위반", " ", OPERATOR, IP))
                .isInstanceOf(AdminTerminationService.TerminationRejected.class)
                .hasMessageContaining("닉네임을 입력하세요");

        verifyNoInteractions(adminUserQueryRepository, accessLogService, userWithdrawalService);
    }

    @Test
    void 없는_회원이면_404로_끝난다() {
        given(adminUserQueryRepository.findByUserId(USER_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.terminate(USER_ID, "약관 12조 위반", "멍멍", OPERATOR, IP))
                .isInstanceOf(NoSuchElementException.class);

        verifyNoInteractions(accessLogService, userWithdrawalService);
    }

    /** 기록이 실패하면 삭제는 시작조차 하지 않는다. 한 트랜잭션이라 어차피 롤백되지만 순서로도 막는다. */
    @Test
    void 기록이_실패하면_삭제하지_않는다() {
        회원이_있다();
        willThrow(new IllegalStateException("DB 연결 실패"))
                .given(accessLogService).recordInCurrentTransaction(
                        anyString(), anyString(), eq(AccessAction.TERMINATE_USER), any(), anyString());

        assertThatThrownBy(() -> service.terminate(USER_ID, "약관 12조 위반", "멍멍", OPERATOR, IP))
                .isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(userWithdrawalService);
    }

    @Test
    void 사유의_앞뒤_공백은_다듬어_기록한다() {
        회원이_있다();

        service.terminate(USER_ID, "  약관 12조 위반  ", "  멍멍  ", OPERATOR, IP);

        verify(accessLogService).recordInCurrentTransaction(
                OPERATOR, IP, AccessAction.TERMINATE_USER, USER_ID,
                "이용계약 해지(약관 14조 5항) — 사유: 약관 12조 위반");
    }
}
