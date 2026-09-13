package com.server.walwang_be.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 관리자 페이지(/admin/**) 설정. 값은 DB나 yaml이 아니라 환경변수로만 넣는다.
 *
 *   ADMIN_ALLOWLIST=203.0.113.10,198.51.100.0/24
 *   ADMIN_ACCOUNTS_0_USERNAME=alice
 *   ADMIN_ACCOUNTS_0_BCRYPT='$2y$12$...'
 *   ADMIN_ACCOUNTS_0_TOTP=JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP
 *
 * 필드 이름을 한 단어로만 둔 이유: 환경변수에서 password-hash 같은 이름은
 * PASSWORDHASH 로 적어야 바인딩된다. 헷갈려서 잘못 적으면 조용히 빈 값이 된다.
 *
 * 값이 비어 있으면 관리자 페이지는 아무에게도 열리지 않는다 — docs/admin-access-log.md 참고.
 */
@ConfigurationProperties(prefix = "admin")
public record AdminProperties(
        List<Account> accounts,
        List<String> allowlist
) {
    public AdminProperties {
        accounts = accounts == null ? List.of() : List.copyOf(accounts);
        allowlist = allowlist == null ? List.of() : List.copyOf(allowlist);
    }

    /**
     * 관리자 한 명. 1인 1계정이다 — 공용 계정은 접속기록의 "계정" 항목을 무의미하게 만든다.
     *
     * @param username 접속기록에 operator로 남는 이름
     * @param bcrypt   비밀번호의 BCrypt 해시. 평문을 넣지 않는다
     * @param totp     인증 앱에 입력한 것과 같은 Base32 시크릿
     */
    public record Account(String username, String bcrypt, String totp) {

        /** 시크릿이 로그에 찍히지 않게 한다. */
        @Override
        public String toString() {
            return "Account[username=" + username + "]";
        }
    }
}
