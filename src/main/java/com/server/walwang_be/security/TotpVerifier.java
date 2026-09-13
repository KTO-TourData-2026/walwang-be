package com.server.walwang_be.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * RFC 6238 TOTP. Google Authenticator 등 인증 앱의 기본값(HMAC-SHA1, 6자리, 30초)에 맞춘다.
 *
 * 라이브러리 없이 직접 둔다. 필요한 것은 HMAC 한 번과 Base32 디코딩뿐이라
 * 의존성을 늘리는 것보다 RFC 테스트 벡터로 고정하는 편이 낫다(TotpVerifierTest).
 *
 * 서버 시계가 틀리면 맞는 코드도 거절된다. 앞뒤 한 칸(±30초)까지만 받아준다.
 */
@Component
public class TotpVerifier {

    static final int DIGITS = 6;
    static final long PERIOD_SECONDS = 30;
    static final int WINDOW = 1;

    /** 80비트. 이보다 짧은 시크릿은 받지 않는다. */
    static final int MIN_KEY_BYTES = 10;

    private static final Pattern CODE = Pattern.compile("^\\d{" + DIGITS + "}$");
    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private final Clock clock;

    public TotpVerifier() {
        this(Clock.systemUTC());
    }

    TotpVerifier(Clock clock) {
        this.clock = clock;
    }

    /**
     * 코드가 맞으면 맞은 시간 칸 번호를 돌려준다. 재사용을 막으려면 부르는 쪽이 이 번호를 기억해야 한다.
     */
    public OptionalLong verify(byte[] key, String code) {
        if (key == null || code == null || !CODE.matcher(code).matches()) {
            return OptionalLong.empty();
        }
        long current = clock.instant().getEpochSecond() / PERIOD_SECONDS;
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            byte[] expected = generate(key, step).getBytes(StandardCharsets.US_ASCII);
            // 앞자리부터 비교하다 멈추면 응답 시간으로 몇 자리가 맞았는지 새어 나간다.
            if (MessageDigest.isEqual(expected, given)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    public static String generate(byte[] key, long timeStep) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(timeStep).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % (int) Math.pow(10, DIGITS);
            return String.format("%0" + DIGITS + "d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1을 사용할 수 없습니다.", e);
        }
    }

    /**
     * 인증 앱에 입력하는 Base32 문자열을 바이트로 바꾼다.
     * 앱들이 보여주는 형식(소문자, 네 글자마다 공백, 끝의 '=')을 모두 받아준다.
     *
     * @throws IllegalArgumentException Base32가 아닌 글자가 있으면
     */
    public static byte[] decodeBase32(String secret) {
        String normalized = secret.replace(" ", "").replace("=", "").toUpperCase(Locale.ROOT);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : normalized.toCharArray()) {
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Base32가 아닌 문자가 있습니다.");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
