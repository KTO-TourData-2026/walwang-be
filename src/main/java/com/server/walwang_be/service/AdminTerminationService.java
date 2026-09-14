package com.server.walwang_be.service;

import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.User;
import com.server.walwang_be.entity.WithdrawalReason;
import com.server.walwang_be.repo.AdminUserQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 이용계약 해지(강제 탈퇴).
 *
 * 약관 12조를 위반해 계약이 해지된 경우 14조 5항에 따라 이메일 해시를 30일 보관해 재가입을 막는다.
 * 그 경로가 여기 하나뿐이다 — 자진 탈퇴(DELETE /users/me)는 사유를 받지 않고 항상 SELF다.
 *
 * <p><b>접속기록과 삭제를 한 트랜잭션에 둔다.</b> 조회 화면은 "기록을 먼저 커밋하고 데이터를 읽는"
 * 순서를 쓰지만(AccessLogService 주석), 지우는 작업에는 그 순서를 쓸 수 없다.
 *
 * <ul>
 *   <li>기록을 먼저 커밋하면: 삭제가 실패했을 때 "해지했다"는 기록만 남는다.
 *   <li>기록을 나중에 하면: 기록이 실패했을 때 되돌릴 수 없는 삭제가 기록 없이 끝난다.
 * </ul>
 *
 * 그래서 둘을 묶었다. 함께 커밋되거나 함께 없어지므로 접속기록과 실제 상태가 어긋나지 않는다.
 * 대가로 해지에 실패한 시도는 기록에 남지 않는다 — 다만 그 경우 개인정보가 바뀌지도, 관리자가
 * 새로 알게 되는 것도 없다(닉네임은 직전 VIEW_USER 로 기록된 상세 화면에 이미 나와 있다).
 *
 * 되돌릴 수 없다. 리뷰·여권·저장 목록이 지워지고 Supabase 의 사진·도장 파일까지 사라진다.
 * 그래서 실행 전에 닉네임 재입력을 요구한다. 판단 근거는 docs/admin-access-log.md 참고.
 */
@Service
@RequiredArgsConstructor
public class AdminTerminationService {

    /** 해지 사유 최대 길이. access_log.detail(500자)에 접두어와 함께 담긴다. */
    public static final int REASON_MAX = 200;

    private static final String DETAIL_PREFIX = "이용계약 해지(약관 14조 5항) — 사유: ";

    private final AdminUserQueryRepository adminUserQueryRepository;
    private final AccessLogService accessLogService;
    private final UserWithdrawalService userWithdrawalService;

    /**
     * 해지한다. 돌려주는 값은 해지한 회원의 닉네임 — 완료 메시지에 쓴다.
     * 이미 읽은 값이라 화면을 위해 다시 조회하지 않는다.
     *
     * @throws TerminationRejected  입력이 비었거나 너무 길거나, 확인 닉네임이 다를 때
     * @throws NoSuchElementException  그 회원이 없을 때
     */
    @Transactional
    public String terminate(UUID userId, String reason, String confirmNickname, String operator, String ip) {
        // DB를 읽기 전에 입력 모양만 먼저 본다. 형식이 틀린 요청으로는 개인정보를 읽지 않는다.
        String trimmedReason = reason == null ? "" : reason.strip();
        if (trimmedReason.isEmpty()) {
            throw new TerminationRejected("해지 사유를 입력하세요. 해지를 실행하지 않았습니다.");
        }
        if (trimmedReason.length() > REASON_MAX) {
            throw new TerminationRejected(
                    "해지 사유는 " + REASON_MAX + "자 이내로 입력하세요. 해지를 실행하지 않았습니다.");
        }
        String confirm = confirmNickname == null ? "" : confirmNickname.strip();
        if (confirm.isEmpty()) {
            throw new TerminationRejected("확인을 위해 닉네임을 입력하세요. 해지를 실행하지 않았습니다.");
        }

        User user = adminUserQueryRepository.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("회원을 찾을 수 없습니다."));

        // 오조작을 막는 장치다. 상세 화면을 잘못 열어 둔 채 실행 버튼을 누르는 것을 여기서 잡는다.
        if (!user.getNickname().equals(confirm)) {
            throw new TerminationRejected("닉네임이 일치하지 않습니다. 해지를 실행하지 않았습니다.");
        }

        // 같은 트랜잭션에 담는다. 아래 삭제가 실패하면 이 행도 함께 사라진다.
        accessLogService.recordInCurrentTransaction(
                operator, ip, AccessAction.TERMINATE_USER, userId, DETAIL_PREFIX + trimmedReason);

        // 액세스 토큰은 넘길 것이 없다 — 관리자는 그 회원의 토큰을 들고 있지 않다.
        // Refresh Token 은 지워지므로 갱신은 막히고, 이미 발급된 액세스 토큰은 만료까지 남는다
        // (docs/admin-access-log.md "알려진 한계").
        userWithdrawalService.withdraw(userId, null, WithdrawalReason.TERMINATED);

        return user.getNickname();
    }

    /**
     * 실행하지 않고 되돌려보낸 요청.
     *
     * 메시지는 코드에 있는 고정 문자열만 담는다 — 관리자가 넣은 값을 오류 화면에 되비추지 않는다.
     */
    public static class TerminationRejected extends RuntimeException {
        public TerminationRejected(String message) {
            super(message);
        }
    }
}
