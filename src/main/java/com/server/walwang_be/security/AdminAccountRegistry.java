package com.server.walwang_be.security;

import com.server.walwang_be.config.AdminProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 환경변수로 받은 관리자 계정을 검증해 들고 있는다.
 *
 * 형식이 틀린 계정은 건너뛰고 로그를 남긴다(앱은 뜬다). 그 계정으로는 로그인이 안 될 뿐이다.
 * 흔한 사고는 BCrypt 해시의 '$'가 셸이나 설정 파일에서 변수로 해석돼 잘려 들어오는 것인데,
 * 해시 형식 검사에서 걸러져 기동 로그에 드러난다.
 *
 * 기동할 때 계정 이름 목록을 로그에 남긴다. 계정이 언제 추가·삭제됐는지 서버 로그로 되짚을 수 있다.
 */
@Slf4j
@Component
public class AdminAccountRegistry {

    private static final Pattern USERNAME = Pattern.compile("^[A-Za-z0-9._-]{1,50}$");
    private static final Pattern BCRYPT = Pattern.compile("^\\$2[aby]?\\$\\d{2}\\$[./0-9A-Za-z]{53}$");

    private final Map<String, AdminAccount> accounts;

    public AdminAccountRegistry(AdminProperties properties) {
        Map<String, AdminAccount> loaded = new LinkedHashMap<>();
        int index = 0;
        for (AdminProperties.Account account : properties.accounts()) {
            String label = "ADMIN_ACCOUNTS_" + index++;
            String username = account.username() == null ? "" : account.username().trim();
            if (!USERNAME.matcher(username).matches()) {
                log.error("관리자 계정 {}을 무시합니다: USERNAME은 영문·숫자·._- 50자 이내여야 합니다.", label);
                continue;
            }
            if (loaded.containsKey(username)) {
                log.error("관리자 계정 {}을 무시합니다: '{}'가 이미 있습니다.", label, username);
                continue;
            }
            if (account.bcrypt() == null || !BCRYPT.matcher(account.bcrypt().trim()).matches()) {
                log.error("관리자 계정 {}('{}')을 무시합니다: BCRYPT가 BCrypt 해시 형식이 아닙니다. "
                        + "'$'가 변수로 해석돼 잘리지 않았는지 확인하세요.", label, username);
                continue;
            }
            byte[] key;
            try {
                key = account.totp() == null ? new byte[0] : TotpVerifier.decodeBase32(account.totp());
            } catch (IllegalArgumentException e) {
                log.error("관리자 계정 {}('{}')을 무시합니다: TOTP가 Base32 형식이 아닙니다.", label, username);
                continue;
            }
            if (key.length < TotpVerifier.MIN_KEY_BYTES) {
                log.error("관리자 계정 {}('{}')을 무시합니다: TOTP 시크릿이 너무 짧습니다(80비트 이상).",
                        label, username);
                continue;
            }
            loaded.put(username, new AdminAccount(username, account.bcrypt().trim(), key));
        }
        this.accounts = Map.copyOf(loaded);

        if (accounts.isEmpty()) {
            log.warn("관리자 계정이 없습니다. /admin 에는 아무도 로그인할 수 없습니다.");
        } else {
            log.info("관리자 계정 {}개를 불러왔습니다: {}", accounts.size(), loaded.keySet());
        }
    }

    public Optional<AdminAccount> find(String username) {
        return Optional.ofNullable(accounts.get(username));
    }

    public int size() {
        return accounts.size();
    }

    public static final class AdminAccount {
        private final String username;
        private final String bcrypt;
        private final byte[] totpKey;

        AdminAccount(String username, String bcrypt, byte[] totpKey) {
            this.username = username;
            this.bcrypt = bcrypt;
            this.totpKey = totpKey.clone();
        }

        public String username() {
            return username;
        }

        public String bcrypt() {
            return bcrypt;
        }

        public byte[] totpKey() {
            return totpKey.clone();
        }

        @Override
        public String toString() {
            return "AdminAccount[" + username + "]";
        }
    }
}
