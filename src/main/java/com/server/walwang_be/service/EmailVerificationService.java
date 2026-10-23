package com.server.walwang_be.service;

import com.server.walwang_be.client.EmailSender;
import com.server.walwang_be.repo.EmailVerificationRepository;
import com.server.walwang_be.repo.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.UUID;

/**
 * 가입 이메일 인증. 코드 발송 → 코드 확인 → 인증 토큰 발급 → 가입이 토큰을 소비한다.
 *
 * 확인을 마치면 "이 주소는 인증됨"으로 표시하지 않고 토큰을 내준다 — 주소에 표시를 걸면
 * 그 시간 안에 같은 주소로 가입 요청을 보낸 누구나 계정을 가져갈 수 있다.
 * 영수증 토큰(ReceiptTokenRepository)과 같은 생각이다.
 *
 * 개인정보 처리방침에 적힌 것과 맞물려 있다:
 *   - 코드 유효시간 5분 (EmailVerificationRepository.CODE_TTL)
 *   - 인증 완료 사실과 일시는 users.email_verified_at에 둔다 — 탈퇴하면 행과 함께 지워진다.
 *   - Resend로 나가는 것은 주소와 코드가 든 본문뿐이다 (EmailSender).
 *
 * TODO: users.email은 대소문자를 구분해 저장·조회한다(existsByEmail). Foo@x.com이 있어도
 *  foo@x.com이 따로 인증·가입된다. 인증 키는 EmailHasher가 소문자로 맞추지만 중복 확인은
 *  그대로다. 로그인까지 걸린 문제라 이번 범위 밖으로 둔다.
 */
@Service
@RequiredArgsConstructor
public class EmailVerificationService {

    /** 같은 주소로 한 시간에 보낼 수 있는 횟수. */
    static final int MAX_SENDS_PER_EMAIL = 5;
    /**
     * 같은 IP에서 한 시간에 보낼 수 있는 횟수. 주소를 바꿔 가며 보내는 것을 막는다.
     * 통신사 NAT로 여러 사람이 한 IP를 쓸 수 있어 주소 기준보다 넉넉히 둔다.
     */
    static final int MAX_SENDS_PER_IP = 20;
    /** 코드 하나에 허용하는 검증 시도. 다 쓰면 코드를 버린다. */
    static final int MAX_ATTEMPTS = 5;

    /** 가입됨과 차단됨을 한 문장으로 답한다. 나눠서 알려주면 남의 주소 상태를 알아낼 수 있다. */
    static final String UNAVAILABLE_EMAIL = "가입할 수 없는 이메일입니다.";

    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final WithdrawnEmailService withdrawnEmailService;
    private final EmailHasher emailHasher;
    private final EmailVerificationRepository verificationRepository;
    private final EmailSender emailSender;

    /**
     * 인증 코드를 보낸다.
     *
     * 순서가 곧 정책이다.
     *   1. 가입됐거나 차단된 주소는 아무것도 세지 않고 돌려보낸다.
     *   2. IP 한도 → 주소 쿨다운 → 주소 시간당 한도. 앞에서 막히면 뒤의 횟수는 쓰지 않는다.
     *   3. 발송이 실패하면 코드와 쿨다운을 거둬 바로 다시 시도할 수 있게 한다.
     *      시간당 횟수는 돌려주지 않는다 — 실패를 핑계로 Resend를 계속 두드리지 못하게.
     *
     * 트랜잭션을 걸지 않는다. 외부 HTTP 호출을 기다리는 동안 DB 연결을 잡고 있을 이유가 없다.
     */
    public void sendCode(String email, String ip) {
        if (userRepository.existsByEmail(email) || withdrawnEmailService.blocked(email)) {
            throw new IllegalStateException(UNAVAILABLE_EMAIL);
        }

        if (verificationRepository.countIpSend(ip) > MAX_SENDS_PER_IP) {
            throw new TooManyRequestsException("요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.");
        }
        String emailHash = emailHasher.hash(email);
        if (!verificationRepository.tryStartCooldown(emailHash)) {
            throw new TooManyRequestsException("인증 코드는 1분에 한 번만 요청할 수 있습니다.");
        }
        if (verificationRepository.countSend(emailHash) > MAX_SENDS_PER_EMAIL) {
            throw new TooManyRequestsException("인증 코드 요청 횟수를 초과했습니다. 1시간 뒤에 다시 시도해 주세요.");
        }

        String code = newCode();
        verificationRepository.saveCode(emailHash, code);
        try {
            emailSender.sendVerificationCode(email, code);
        } catch (EmailSender.MailUnavailableException e) {
            verificationRepository.deleteCode(emailHash);
            verificationRepository.clearCooldown(emailHash);
            throw e;
        }
    }

    /**
     * 코드를 확인하고 인증 토큰을 내준다.
     *
     * 시도 횟수를 먼저 올리고 비교한다. 비교하고 나서 올리면 동시에 들어온 요청들이
     * 모두 "아직 5회 전"으로 보고 한도를 넘겨 맞춰볼 수 있다.
     *
     * @return 가입 요청에 실어 보낼 토큰
     */
    public String confirm(String email, String code) {
        String emailHash = emailHasher.hash(email);
        String saved = verificationRepository.findCode(emailHash)
                .orElseThrow(() -> new IllegalArgumentException(
                        "인증 코드가 만료되었거나 없습니다. 코드를 다시 요청해 주세요."));

        long attempt = verificationRepository.countAttempt(emailHash);
        if (attempt > MAX_ATTEMPTS) {
            verificationRepository.deleteCode(emailHash);
            throw new IllegalArgumentException("인증 시도 횟수를 초과했습니다. 코드를 다시 요청해 주세요.");
        }

        if (!matches(saved, code)) {
            if (attempt >= MAX_ATTEMPTS) {
                verificationRepository.deleteCode(emailHash);
                throw new IllegalArgumentException("인증 시도 횟수를 초과했습니다. 코드를 다시 요청해 주세요.");
            }
            throw new IllegalArgumentException(
                    "인증 코드가 일치하지 않습니다. (남은 시도 %d회)".formatted(MAX_ATTEMPTS - attempt));
        }

        verificationRepository.deleteCode(emailHash);
        String token = UUID.randomUUID().toString();
        verificationRepository.saveToken(token, emailHash, Instant.now());
        return token;
    }

    /**
     * 가입 요청의 토큰이 이 주소를 인증한 것인지 확인하고 인증 시각을 돌려준다.
     * 토큰은 여기서 지우지 않는다 — 가입이 커밋된 뒤에 consume으로 지운다.
     */
    public Instant requireVerified(String token, String email) {
        return verificationRepository.findToken(token)
                .filter(verified -> verified.emailHash().equals(emailHasher.hash(email)))
                .map(EmailVerificationRepository.Verified::verifiedAt)
                .orElseThrow(() -> new AccessDeniedException(
                        "이메일 인증이 만료되었거나 유효하지 않습니다. 다시 인증해 주세요."));
    }

    public void consume(String token) {
        verificationRepository.deleteToken(token);
    }

    /** 000000 ~ 999999. 앞자리 0도 살린다. */
    static String newCode() {
        return "%06d".formatted(RANDOM.nextInt(1_000_000));
    }

    /** 걸린 시간으로 몇 자리까지 맞았는지 알 수 없게 상수 시간으로 비교한다. */
    private static boolean matches(String saved, String input) {
        if (input == null) {
            return false;
        }
        return MessageDigest.isEqual(
                saved.getBytes(StandardCharsets.UTF_8), input.getBytes(StandardCharsets.UTF_8));
    }

    /** 발송 한도 초과 → 429. */
    public static class TooManyRequestsException extends RuntimeException {
        public TooManyRequestsException(String message) {
            super(message);
        }
    }
}
