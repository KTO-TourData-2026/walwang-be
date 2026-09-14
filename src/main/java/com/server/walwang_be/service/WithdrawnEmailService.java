package com.server.walwang_be.service;

import com.server.walwang_be.entity.WithdrawalReason;
import com.server.walwang_be.entity.WithdrawnEmail;
import com.server.walwang_be.repo.WithdrawnEmailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 탈퇴 이메일 기록과 재가입 차단.
 *
 * 약관 4조 4항이 승낙 거부 사유로 두는 것은 "약관 위반 해지 이력"뿐이다.
 * 그래서 해지(TERMINATED)만 기록하고 막는다 — 자진 탈퇴자는 막을 근거가 없고,
 * 막지도 않을 주소의 해시를 30일 들고 있을 이유도 없다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WithdrawnEmailService {

    /** 보관 기간. 약관이 정한 재가입 제한과 같은 길이다. */
    private static final Duration RETENTION = Duration.ofDays(30);

    /** 재가입을 막는 사유. 지금은 해지 하나뿐이다. */
    private static final List<WithdrawalReason> BLOCKING = Arrays.stream(WithdrawalReason.values())
            .filter(WithdrawalReason::blocksSignUp)
            .toList();

    private final WithdrawnEmailRepository withdrawnEmailRepository;
    private final EmailHasher emailHasher;

    /**
     * 탈퇴 기록을 남긴다.
     *
     * 같은 주소로 다시 탈퇴하면 기존 행을 덮어 만료가 새로 시작된다 —
     * 해시가 PK라 저장이 곧 갱신이다.
     *
     * 다만 차단 중인 기록은 막지 않는 사유로 덮지 않는다. 탈퇴 경로는 이제 해지만
     * 기록하므로 지금은 걸릴 일이 없지만, 여기가 뚫리면 해지당한 사람이 아직 남은
     * 다른 계정을 탈퇴시키는 것만으로 자기 차단을 풀 수 있다.
     */
    @Transactional
    public void record(String email, WithdrawalReason reason) {
        String hash = emailHasher.hash(email);

        if (!reason.blocksSignUp() && blockedByHash(hash)) {
            log.info("차단 중인 주소라 탈퇴 기록을 덮지 않습니다.");
            return;
        }
        withdrawnEmailRepository.save(WithdrawnEmail.builder()
                .emailHash(hash)
                .reason(reason)
                .expiresAt(Instant.now().plus(RETENTION))
                .build());
    }

    /**
     * 지금 이 주소로 가입할 수 없는지.
     *
     * 호출한 쪽은 이유를 되묻지 않는다 — 응답 메시지에 탈퇴 사실이 드러나면
     * 아무나 남의 주소를 넣어보고 그 사람이 해지당했는지 알아낼 수 있다.
     */
    @Transactional(readOnly = true)
    public boolean blocked(String email) {
        if (BLOCKING.isEmpty()) {
            return false;
        }
        return blockedByHash(emailHasher.hash(email));
    }

    private boolean blockedByHash(String emailHash) {
        if (BLOCKING.isEmpty()) {
            return false;
        }
        return withdrawnEmailRepository.existsByEmailHashAndReasonInAndExpiresAtAfter(
                emailHash, BLOCKING, Instant.now());
    }

    /** 보관 기간이 끝난 기록을 지운다. 매일 새벽 4시. */
    @Scheduled(cron = "0 0 4 * * *")
    @Transactional
    public void purgeExpired() {
        long removed = withdrawnEmailRepository.deleteByExpiresAtBefore(Instant.now());
        if (removed > 0) {
            log.info("만료된 탈퇴 이메일 기록 {}건을 정리했습니다.", removed);
        }
    }
}
