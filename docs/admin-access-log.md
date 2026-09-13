# 관리자 페이지와 접속기록 (`/admin`)

「개인정보의 안전성 확보조치 기준」(개인정보보호위원회 고시) 제8조의 접속기록을 남기기 위한
관리자 전용 조회 페이지다. 안드로이드 앱과 무관하고, 서버가 HTML을 그린다(Mustache).

그전까지 운영 DB에 들어가는 길은 Supabase 대시보드뿐이었고, 대시보드는
"누가 어떤 회원의 정보를 왜 봤는지"를 남기지 않는다(`docs/supabase-audit-log-retention.md` "3." 참고).
이 페이지는 그 기록이 남는 경로를 만든다.

> 조문 번호와 요지는 2023-09-22 시행 고시를 기준으로 적었다.
> 점검을 받기 전에 국가법령정보센터에서 현행 원문과 대조한다.

| 제8조 접속기록 항목 | `access_log` 컬럼 | 채우는 곳 |
|---|---|---|
| 계정 | `operator` | 관리자 아이디 (로그인 실패 행은 입력된 문자열) |
| 접속일시 | `accessed_at` | 기록 시각 (timestamptz) |
| 접속지 정보 | `ip` | nginx가 붙인 `X-Forwarded-For`의 오른쪽 끝 값 |
| 처리한 정보주체 정보 | `target_user_id` | 회원 상세면 그 회원 id. 목록·검색·로그인은 비어 있다 |
| 수행업무 | `action` + `detail` | 무엇을 했는지 + 어느 화면에서 어떤 조건으로 했는지(검색어, 페이지) |

| 화면 | 경로 | 기록 |
|---|---|---|
| 로그인 | `GET/POST /admin/login` | `LOGIN_SUCCESS` / `LOGIN_FAILURE` / `TOTP_FAILURE` / `LOGIN_LOCKED` |
| 회원 목록·검색 | `GET /admin/users?q=` | `LIST_USERS` / `SEARCH_USERS` (검색어 포함) |
| 회원 상세 | `GET /admin/users/{userId}` | `VIEW_USER` + 대상 회원 |
| 접속기록 열람 | `GET /admin/access-logs` | `VIEW_ACCESS_LOG` (+ 대상 회원으로 걸렀으면 그 회원) |
| 로그아웃 | `POST /admin/logout` | `LOGOUT` |

조회 전용이다. 수정·삭제 기능은 없다.

---

## ⚠️ 운영 규칙 — 이 규칙이 없으면 이 페이지는 의미가 없다

기록이 남는 길을 만들어도, 기록이 안 남는 길(대시보드)을 계속 쓰면 제8조를 채우지 못한다.

1. **회원 개인정보 조회는 관리자 페이지로만 한다.**
   Supabase 대시보드(Table Editor, SQL Editor)에서 회원 데이터 테이블
   (`users`, `user_agreements`, `reviews`, `review_report`, `saved_stores`, `saved_courses`,
   `saved_course_stores`, `passports`)을 직접 조회하지 않는다.
2. **대시보드를 써야만 하는 작업은 예외로 기록한다.**
   관리자 페이지에 없는 작업(`docs/admin-runbook.md`의 블라인드 처리, 장애 대응, 스키마 변경)에 한한다.
   - 하기 전에: 작업자 / 사유 / 대상(회원 id·리뷰 id) / 실행할 쿼리를 팀 채널이나 작업 대장에 남긴다.
   - 할 때: 필요한 컬럼만 고른다. `SELECT * FROM users` 처럼 통째로 읽지 않는다.
   - 한 뒤에: 실제로 한 일을 같은 자리에 남긴다.
3. **1인 1계정. 공유하지 않는다.** 비밀번호와 TOTP 시크릿도 공유하지 않는다.
   공용 계정은 접속기록의 "계정" 항목을 무의미하게 만든다.
4. **계정을 만들거나 지우거나 바꾸면 대장에 남긴다.**
   누가 / 언제 / 누구에게 / 무엇을(발급·변경·말소). 고시 제5조는 권한 부여·변경·말소 내역의 보관(3년)을 요구한다.
   환경변수에는 이력이 남지 않으므로 대장이 유일한 근거다. 서버 기동 로그에도 계정 이름 목록이 찍힌다.
5. **퇴사하거나 역할이 바뀌면 그날 계정을 뺀다.** 환경변수에서 지우고 재시작한다(아래 "계정 추가·삭제").
6. **Supabase 대시보드 멤버는 최소 인원으로 둔다.** 각자 개인 계정, 2단계 인증.
7. **월 1회 접속기록을 점검하고, 점검했다는 사실을 남긴다**(제8조). 아래 "월 1회 점검".
8. **업무 목적이 있을 때만 조회한다.** 문의 대응, 신고 처리 등. 화면 상단에도 같은 안내가 나온다.

---

## 설계 판단 근거

### 1. 외부 접속 보호: VPN 대신 IP 허용목록 + TOTP

**요구.** 고시 제6조는 개인정보취급자가 외부에서 개인정보처리시스템에 접속할 때
안전한 접속수단(가상사설망 등) 또는 안전한 인증수단(인증서, 보안토큰, 일회용 비밀번호 등)을 적용하고,
IP 주소 등으로 접속 권한을 제한하도록 한다.

**택한 것.** 세 겹이 모두 맞아야 들어온다.

| 겹 | 무엇 | 대응 |
|---|---|---|
| IP 허용목록 | 등록된 고정 IP에서만. 밖이면 로그인 화면도 404 | 제6조 IP 주소 등에 의한 접속 제한 |
| 비밀번호 | BCrypt 해시로 비교 | 지식 요소 |
| TOTP 6자리 | 인증 앱의 일회용 비밀번호. 같은 코드 재사용 불가 | 제6조가 예로 드는 "일회용 비밀번호" — 소유 요소 |

비밀번호가 새도 인증 앱이 없으면 못 들어오고, 허용 IP 안에 있어도 계정 정보가 없으면 못 들어온다.

**VPN을 택하지 않은 이유.**

- **SSH 터널은 구조상 불가능하다.** nginx가 같은 호스트에서 `127.0.0.1:8080`으로 넘기므로,
  앱이 보는 접속 주소는 인터넷에서 온 요청이든 SSH 터널로 온 요청이든 똑같이 루프백이다.
  "루프백에서 온 요청만 받는다"로는 둘을 구분하지 못한다.
- **VPN 서버를 따로 운영할 여력이 없다.** 관리자 1~2명 규모에 WireGuard/OpenVPN을 세우면
  키 발급·회수, 패치, 서버 감시가 새로 생긴다. 단일 EC2에 올리면 그 VPN 데몬 자체가 새 공격면이 된다.
- **OTP는 고시가 명시한 인증수단이다.** VPN만이 유일한 답이 아니라, 고시가 인증수단을 대안으로 함께 열어 두었다.

**한계와 보완.**

- 허용 IP가 공유 NAT(카페, 통신사 모바일망)이면 같은 IP를 쓰는 다른 사람도 로그인 화면까지는 온다.
  → **고정 IP만 등록한다.** 휴대폰 테더링·공용 와이파이 IP는 넣지 않는다.
- TOTP 시크릿이 서버 환경변수에 있으므로, 서버를 통째로 빼앗기면 이 겹은 의미가 없다.
  그 경우는 DB 접속 정보도 이미 빼앗긴 상황이라, 이 페이지만의 문제가 아니다.
- IP 허용목록은 환경변수라 바꾸려면 재시작이 필요하다(아래 "IP 허용목록 바꾸기").

### 2. `access_log.target_user_id`에 FK를 걸지 않은 이유

탈퇴는 행을 실제로 지운다(개인정보 처리방침에 탈퇴 시 삭제로 명시). FK를 걸면 어느 쪽이든 문제가 된다.

| FK 옵션 | 탈퇴할 때 벌어지는 일 | 문제 |
|---|---|---|
| `RESTRICT` (기본) | 관리자가 한 번이라도 조회한 회원은 탈퇴가 실패한다 | 파기 의무를 못 지킨다 |
| `CASCADE` | 그 회원에 대한 접속기록이 함께 지워진다 | 1년 보관을 못 지킨다 |
| `SET NULL` | 누구를 조회했는지가 지워진다 | "처리한 정보주체" 항목이 사라진다 |

탈퇴 직전에 누군가 그 회원 정보를 대량으로 봤다면, 그 기록이야말로 나중에 필요한 기록이다.
그래서 FK 없이 id만 남긴다.

- 대가: 탈퇴한 회원의 id가 접속기록에 남는다. `users` 행이 사라진 뒤에는 그 id로 이메일·닉네임을 되짚을 수 없다.
- 없는 id로 상세를 조회해도 `VIEW_USER`가 남는다(화면은 404). 시도 자체도 기록 대상이다.
- `detail`에는 검색어가 들어가므로 이메일 일부가 남을 수 있다. 이것도 1년 뒤 함께 파기된다.

### 3. 기록 방식: AOP 대신 핸들러에서 직접 기록 (+ 인터셉터 안전망)

**AOP(`@AccessLogged` 같은 어노테이션)를 쓰지 않은 이유.**

- 프록시에 기대는 방식이라 **조용히 빠질 수 있다.** 같은 빈 안에서 부르거나(자기 호출) private/final 메서드면
  어드바이스가 걸리지 않고, 에러도 나지 않는다. 이 프로젝트는 같은 종류의 함정(`@Transactional`이 자기 호출에서
  무시돼 여권이 PENDING에 영원히 남던 사고, `StampGenerator` 주석)을 이미 겪었다.
  접속기록은 빠져도 아무도 모르는 종류라 더 위험하다.
- "누구의 무엇을 봤는지"를 어노테이션 SpEL로 끌어내야 한다. 기록 내용이 코드에서 안 보이게 된다.

**인터셉터만으로 하지 않은 이유.**

- 경로와 파라미터는 알지만, 그 요청이 **어떤 정보주체의 무엇을** 처리했는지는 모른다.
- `afterCompletion`에서 쓰면 응답이 이미 나간 뒤다. 기록이 실패해도 화면은 나가 있다.

**택한 것.**

1. 개인정보를 보여주는 핸들러는 **데이터를 읽기 전에** `AccessLogService.record(...)`를 부른다.
   - 다른 빈(컨트롤러 → 서비스)으로 부르므로 프록시를 반드시 거친다.
   - 동기, `REQUIRES_NEW`로 먼저 커밋한다. 뒤이은 조회가 실패해도 "조회 시도" 기록은 남는다.
   - **기록이 실패하면 예외로 끝나고 화면을 보여주지 않는다.**
   - `@Async`는 쓰지 않는다. 비동기면 기록이 사라져도 화면은 이미 나가 있다.
2. `AccessLogEnforcementInterceptor`가 `postHandle`(뷰 렌더링 직전)에서 기록 여부를 확인한다.
   나중에 화면을 추가하면서 기록을 빠뜨리면 **렌더링 전에 막힌다.**

### 4. 인증 방식: 폼 로그인 + 세션 (Basic 인증 아님)

- Basic은 로그아웃이 없고 브라우저가 자격증명을 계속 기억한다. 매 요청마다 비밀번호가 오간다.
- Basic으로는 OTP를 한 번만 받는 흐름을 만들 수 없다(코드는 30초마다 바뀐다).
- 세션이면 유휴 시간 자동 차단(30분, 고시 제6조), 계정당 동시 세션 1개, 로그인 시 세션 id 교체를 걸 수 있다.
- 쿠키: `HttpOnly`, `Secure`(TLS가 nginx에서 끝나 자동 판단에 맡기면 빠지므로 강제), `SameSite=Strict`, `Path=/admin`.
- CSRF 토큰 사용. CSP로 인라인 스크립트 차단, 프레임 금지, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`.

앱 API 체인(JWT, stateless)과 관리자 체인은 `securityMatcher("/admin/**")`로 완전히 분리했다.
분리하지 않으면 앱 체인의 `anyRequest().authenticated()`가 `/admin`에도 걸려 **앱 회원 누구나** 들어온다.

### 5. 인증 실패를 어디까지 남기는가

| 사건 | `access_log` | 서버 로그 | 이유 |
|---|---|---|---|
| 아이디·비밀번호 틀림 | `LOGIN_FAILURE` | | 무차별 대입 추적. 허용 IP 안에서만 일어나고 잠금이 걸려 양이 제한된다 |
| 비밀번호 맞고 OTP 틀림/재사용 | `TOTP_FAILURE` | | **비밀번호 유출 신호.** 점검 때 따로 봐야 해서 나눴다 |
| 잠긴 상태에서 시도 | `LOGIN_LOCKED` | | 잠금이 실제로 작동했는지 |
| 허용목록 밖 IP | 남기지 않음 | `관리자 페이지 IP 차단` | 인터넷 스캐너가 `/admin`을 수시로 두드린다. DB에 쓰면 누구나 행을 무한히 늘릴 수 있고, 트리거 때문에 1년간 지우지도 못한다 |

- 화면 메시지는 실패 이유와 상관없이 같다. 아이디가 있는지, 잠겼는지를 알려주지 않는다.
- 없는 아이디도 BCrypt 비교를 한 번 해서, 응답 시간으로 아이디가 있는지 알 수 없게 했다.
- 잠금: 아이디 기준 5회, IP 기준 20회(같은 사무실 IP를 두 관리자가 쓸 수 있어 넉넉히). 첫 실패부터 15분.
  Redis에 두므로 재시작해도 풀리지 않는다.

### 6. 화면에 내보내는 정보를 줄인 이유

- **목록의 이메일은 가린다**(`ab***@gmail.com`). 목록은 여러 사람이 한꺼번에 보이는데
  `target_user_id`가 비어 있어, 누구의 이메일이 노출됐는지 기록되지 않는다. 원문은 대상이 기록되는 상세에서만 보인다.
- **상세에서 리뷰 본문과 사진은 보여주지 않는다.** 요약(개수, 상태, 가게명, 작성일)으로 충분하다.

### 7. 위·변조 방지 트리거

고시 제8조는 접속기록이 위·변조되거나 도난·분실되지 않도록 안전하게 보관하게 한다.

- `UPDATE` 금지, `TRUNCATE` 금지, `DELETE`는 **1년이 지난 행만** 허용(`db/schema-access-log.sql`).
- 앱 엔티티도 `@Immutable`이라 UPDATE를 내지 않는다.
- Supabase REST(PostgREST)의 `anon`, `authenticated` 권한을 회수했다. 그대로 두면 anon 키만으로 접속기록을 읽을 수 있다.
  `access_log`만이 아니라 public 스키마 전체와 앞으로 만드는 테이블까지 회수했다("알려진 한계" 아래 회수 기록 참고).

**한계.** DB 소유자(대시보드의 `postgres`)는 트리거를 지우거나 끌 수 있다. 완전한 방어가 아니라,
조용히 고치는 것을 막고 **고치려면 트리거를 지우는 DDL을 먼저 실행하게** 만드는 장치다.
그 DDL은 Supabase 로그에 남고, 그 로그는 `docs/supabase-audit-log-retention.md`에 따라 1년 보관된다.

### 8. JwtAuthenticationFilter 이중 등록 수정

`JwtAuthenticationFilter`는 `@Component` 필터라 스프링 부트가 보안 체인과 별개로 **모든 요청에 걸리는 서블릿 필터**로도 등록했다.

- 앱 API에서는 `OncePerRequestFilter`가 두 번째 실행을 건너뛰어 티가 나지 않았다.
- JWT 필터가 없는 관리자 체인에서는 보안 체인이 끝난 **뒤에** 이 필터가 돌아,
  `Authorization: Bearer <앱 회원 토큰>`이 붙어 오면 SecurityContext를 그 회원으로 덮어썼다.
  관리자 세션 요청에 앱 JWT를 붙이면 컨트롤러가 보는 사용자가 앱 회원으로 바뀐다.

두 겹으로 막았다.

1. `SecurityConfig`에서 서블릿 필터 등록을 끈다(`FilterRegistrationBean.setEnabled(false)`). 원인을 없앤다.
2. `AdminOperator`는 `ROLE_ADMIN`이 붙은 인증에서만 operator를 꺼낸다.
   원인이 되살아나도 다른 사람 이름으로 기록되지 않고 화면이 403으로 닫힌다.

`AdminJwtIsolationTest`가 이 동작을 고정한다. 1번을 되돌리면 테스트가 실패하는 것을 확인했다.

같은 이유로 IP 허용목록 필터(`AdminIpAllowlistFilter`)는 빈으로 등록하지 않고 관리자 체인 안에서만 만든다.

### 9. 접속 IP를 정하는 방법

- `getRemoteAddr()`가 루프백(같은 호스트의 nginx)일 때만 `X-Forwarded-For`를 믿는다.
- **오른쪽 끝 값을 쓴다.** nginx의 `$proxy_add_x_forwarded_for`는 클라이언트가 보낸 헤더 뒤에
  자기가 본 주소를 붙인다. 왼쪽 끝을 쓰면 `X-Forwarded-For: <허용IP>` 한 줄로 허용목록이 뚫린다.
- 오른쪽 끝이 IP 모양이 아니면 `unknown`으로 두고 차단한다.
- `server.forward-headers-strategy=framework`이면 관리자 페이지를 닫는다.
  스프링의 `ForwardedHeaderFilter`는 왼쪽 끝을 쓰기 때문이다(`native`는 괜찮다).
- **nginx 앞에 ALB나 Cloudflare를 더 두면** 오른쪽 끝이 그쪽 주소가 되어 아무도 못 들어온다.
  열리는 쪽이 아니라 닫히는 쪽으로 틀리지만, 그때는 `ClientIpResolver`를 고쳐야 한다.

---

## 설치 (처음 한 번)

### 1. SQL을 먼저 돌린다

**앱을 배포하기 전에** Supabase SQL Editor에서 `src/main/resources/db/schema-access-log.sql` 전체를 실행한다.
**운영·개발 두 프로젝트에 각각** 돌린다. 로컬은 개발 프로젝트를 보므로 개발에 먼저 돌려 로컬에서 확인한 뒤 운영에 돌린다.

> 적용 현황 (2026-09-13): 운영·개발 모두 SQL 실행 완료.
> 두 프로젝트 모두 public 스키마 전체 테이블과 기본 권한(앞으로 만드는 테이블)에서 `anon`/`authenticated` 권한 회수 완료.
먼저 배포하면 `ddl-auto: update`가 CHECK 제약 없는 테이블을 만들고, 이 파일의 `CREATE TABLE`은 건너뛰어진다.

확인:

```sql
SELECT tgname FROM pg_trigger WHERE tgrelid = 'access_log'::regclass AND NOT tgisinternal;
-- trg_access_log_guard_row, trg_access_log_guard_truncate

SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE table_name = 'access_log';
-- anon, authenticated 가 없어야 한다
```

같은 파일을 다시 돌려도 안전하다(`IF NOT EXISTS`, `DROP TRIGGER IF EXISTS`).

### 2. 관리자 계정 재료 만들기 (EC2에서)

**비밀번호 해시.** 비밀번호는 16자 이상 무작위로, 비밀번호 관리자에 보관한다.

```bash
sudo apt-get install -y apache2-utils
htpasswd -nBC 12 "" | tr -d ':\n'; echo
# 비밀번호를 두 번 묻는다. 셸 히스토리에 남지 않는다.
# $2y$12$... 가 나온다.
```

**TOTP 시크릿.**

```bash
head -c 20 /dev/urandom | base32
# 32글자 (160비트)
```

인증 앱(Google Authenticator, 1Password 등)에서 **설정 키 직접 입력**을 고르고:
- 계정 이름: `walwang-admin-<아이디>`
- 키: 위 32글자
- 유형: 시간 기준 (기본값 SHA1 / 6자리 / 30초 그대로)

시크릿은 인증 앱과 서버 환경변수 파일 두 곳에만 둔다. 메신저·메일로 보내지 않는다.

**서버 시계.** TOTP는 시계가 맞아야 한다(±30초까지만 받는다).

```bash
timedatectl | grep -i synchronized   # System clock synchronized: yes
```

### 3. 환경변수 파일

```bash
sudo install -d -m 700 /etc/walwang
sudo install -m 600 /dev/null /etc/walwang/admin.env
sudo vi /etc/walwang/admin.env
```

```bash
# 관리자 IP 허용목록. 쉼표로 구분, CIDR 가능. 고정 IP만.
ADMIN_ALLOWLIST=203.0.113.10

# 관리자 계정 (번호는 0부터 빈칸 없이)
ADMIN_ACCOUNTS_0_USERNAME=alice
ADMIN_ACCOUNTS_0_BCRYPT='$2y$12$여기에해시'
ADMIN_ACCOUNTS_0_TOTP=여기에32글자시크릿
```

- **해시는 반드시 작은따옴표로 감싼다.** `$`가 변수로 해석돼 잘리면 그 계정은 무시된다(기동 로그에 에러로 나온다).
- 환경변수 이름에 `PASSWORD_HASH`처럼 밑줄 든 단어를 쓰지 않은 것은 의도다. 스프링 바인딩에서 헷갈려 조용히 빈 값이 된다.
  위 이름은 `AdminAccountConfigTest`가 실제 바인딩을 확인한다.

서비스에 연결한다. 기존 설정을 먼저 본다.

```bash
sudo systemctl cat walwang          # 이미 EnvironmentFile 이 있으면 그 파일에 위 줄을 추가해도 된다
sudo systemctl edit walwang
```

```ini
[Service]
EnvironmentFile=/etc/walwang/admin.env
```

```bash
sudo systemctl restart walwang
sudo journalctl -u walwang -b --no-pager | grep -E '관리자 (계정|IP)'
```

기대:

```
관리자 계정 1개를 불러왔습니다: [alice]
관리자 IP 허용목록 1개를 불러왔습니다.
```

에러가 보이면:

| 로그 | 원인 |
|---|---|
| `BCRYPT가 BCrypt 해시 형식이 아닙니다` | 작은따옴표 누락으로 `$`가 잘렸다 |
| `TOTP가 Base32 형식이 아닙니다` / `너무 짧습니다` | 시크릿 복사 실수 |
| `관리자 계정이 없습니다` | 파일이 연결되지 않았거나 번호가 0부터 시작하지 않는다 |
| `IP 허용목록(ADMIN_ALLOWLIST)이 비어 있습니다` | 변수 이름 오타 |
| `forward-headers-strategy=framework ... 닫습니다` | `application.yaml`의 해당 설정을 지우거나 `native`로 |

배포 워크플로(`.github/workflows/deploy.yml`)는 `systemctl restart walwang`만 하므로 이 파일은 배포해도 유지된다.

### 4. IP 허용목록 바꾸기

```bash
sudo vi /etc/walwang/admin.env      # ADMIN_ALLOWLIST 줄만 고친다
sudo systemctl restart walwang
sudo journalctl -u walwang -b --no-pager | grep '관리자 IP'
```

- 환경변수라 **재시작이 필요하다.** 재시작하는 몇 초 동안 앱 API도 멈추고, 열려 있던 관리자 세션도 끊긴다.
  사용자가 적은 시간에 한다.
- `/0`, `127.0.0.1`, `::1`은 운영에 넣지 않는다(기동 로그에 경고가 나온다).

### 5. 계정 추가·삭제

- 추가: `ADMIN_ACCOUNTS_1_USERNAME` / `_BCRYPT` / `_TOTP`를 넣고 재시작.
- 삭제: 해당 번호의 세 줄을 지우고, **뒤 번호를 당겨서 0부터 빈칸이 없게** 한 뒤 재시작.
  번호가 비면 그 뒤 계정이 무시될 수 있다. 기동 로그의 계정 목록으로 확인한다.
- 둘 다 **운영 규칙 4번의 대장에 남긴다.**

---

## nginx 헤더 설정 확인

앱이 쓰는 헤더는 `X-Forwarded-For` 하나다. `X-Real-IP`는 쓰지 않지만, 과거처럼 오타가 있으면 요청 전체가 깨질 수 있으니 같이 본다.

### 1. 설정 보기

```bash
sudo nginx -T 2>/dev/null | grep -n -i -E 'server_name|location|proxy_pass|proxy_set_header|include +proxy_params'
cat /etc/nginx/proxy_params
```

기대(`proxy_params` 기본값):

```nginx
proxy_set_header Host $http_host;
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
proxy_set_header X-Forwarded-Proto $scheme;
```

이름 오타 찾기. 비슷한 이름을 넓게 걸러서 **눈으로** 정확한 철자인지 본다.

```bash
sudo nginx -T 2>/dev/null | grep -n -i -E 'x.?real.?ip|x.?forwarded.?(for|proto)'
```

- `X-Real-IP`, `X-Forwarded-For` — 하이픈 위치까지 정확해야 한다. `X-RealIP`, `X-Real_IP`는 틀린 것이다.
- `X-Forwarded-For`의 값은 `$proxy_add_x_forwarded_for`(권장) 또는 `$remote_addr`.
- **`proxy_pass`가 있는 `location` 블록 안에서 확인한다.** nginx는 `location` 안에 `proxy_set_header`가
  하나라도 있으면 바깥(`server`) 블록의 `proxy_set_header`와 `include proxy_params`를 **상속하지 않는다.**

고친 뒤:

```bash
sudo nginx -t && sudo systemctl reload nginx
```

`nginx -t`는 문법만 본다. **헤더 이름 오타는 문법상 맞아서 통과한다.** 반드시 아래 실제 요청으로 확인한다.

### 2. 실제 요청으로 확인

1. 내 PC의 공인 IP: `curl -s https://checkip.amazonaws.com`
2. 그 IP를 `ADMIN_ALLOWLIST`에 넣고 재시작.
3. 브라우저로 `https://<도메인>/admin/login` → 아래쪽 **"서버가 인식한 접속 IP"** 가 1번과 같아야 한다.
4. 404가 나오면 서버 로그를 본다.

   ```bash
   sudo journalctl -u walwang --since '10 min ago' --no-pager | grep '관리자 페이지 IP 차단'
   ```

   | 로그 | 뜻 |
   |---|---|
   | `ip=127.0.0.1 remoteAddr=127.0.0.1 xff=null` | nginx가 `X-Forwarded-For`를 넘기지 않는다(오타 또는 상속 누락) |
   | `ip=unknown` | `X-Forwarded-For`의 마지막 값이 IP가 아니다 |
   | `ip=<내 IP와 다른 값>` | 허용목록 오타이거나, nginx 앞에 다른 프록시가 있다 |
   | `ip=<내 IP>` | 허용목록 값을 확인한다(재시작했는지) |

5. **사칭 확인 (허용된 PC에서).** 가짜 헤더를 붙여도 내 IP로 인식돼야 한다.

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: 198.51.100.99' https://<도메인>/admin/login
   # 200
   ```

6. **사칭 확인 (허용되지 않은 네트워크, 예: 휴대폰 테더링).** 허용 IP를 헤더에 넣어도 막혀야 한다.

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'X-Forwarded-For: <허용IP>' https://<도메인>/admin/login
   # 404. 서버 로그에는 ip=<테더링 IP> xff=<허용IP>, <테더링 IP>
   ```

7. **8080 직접 접속이 막혀 있는지.**

   ```bash
   curl -m 5 -s -o /dev/null -w '%{http_code}\n' http://<EC2 공인 IP>:8080/admin/login
   # 000 (타임아웃/거부)
   ```

   열려 있어도 앱은 루프백이 아닌 요청의 `X-Forwarded-For`를 무시하므로 사칭은 안 된다.
   하지만 평문 HTTP로 로그인하게 되므로 보안 그룹에서 막는다.

8. 로그인한 뒤 `접속기록` 화면의 IP가 1번과 같은지 본다.

---

## 보관과 정리 (1년)

- `AccessLogRetentionScheduler`가 매일 **04:30 KST**에 1년이 지난 행을 지운다.
- 수동으로 할 때:

  ```sql
  DELETE FROM access_log WHERE accessed_at < now() - interval '1 year';
  ```

- 트리거 때문에 1년이 안 된 행은 수동으로도 지워지지 않는다. 의도다.
- **보관 기간을 바꾸면 두 곳을 함께 고친다.** `AccessLogRepository.deleteExpired`의 interval과
  `schema-access-log.sql` 트리거의 interval. 트리거 쪽이 더 길면 정리가 트리거에 막혀 통째로 실패한다.
- 정보주체가 5만 명을 넘거나 고유식별정보·민감정보를 처리하게 되면 법정 보관 기간이 **2년 이상**이 된다(제8조).

## 월 1회 점검

`접속기록` 화면에서 계정·대상으로 거르거나, `schema-access-log.sql` 끝의 점검 쿼리를 쓴다.

- 관리자별 수행업무 건수: 평소보다 튀는 계정이 있는가
- `LOGIN_FAILURE` / `TOTP_FAILURE` / `LOGIN_LOCKED`: 특히 **`TOTP_FAILURE`는 비밀번호가 샜다는 신호**다. 보이면 그 계정 비밀번호를 바꾼다
- 업무 시간 외 조회
- 한 사람이 짧은 시간에 여러 회원 상세를 연달아 본 경우

점검한 날짜, 점검자, 결과(이상 없음 / 확인한 내용과 조치)를 남긴다.
`접속기록` 화면을 여는 것 자체도 `VIEW_ACCESS_LOG`로 남으므로, 점검했다는 흔적도 함께 남는다.

## 새 관리자 화면을 추가할 때

1. 개인정보를 읽기 **전에** `audit(...)`를 부른다. 한 명이 대상이면 `targetUserId`를 채운다.
2. 뷰 이름을 돌려준다. `@ResponseBody`나 `ResponseEntity`를 쓰지 않는다
   (인터셉터가 막기 전에 본문이 나간다).
3. `AccessAction`에 값을 추가하면 **`schema-access-log.sql`의 CHECK 제약도 고치고 운영·개발 두 DB에 각각 `ALTER`** 한다.
   안 하면 그 화면은 기록이 실패해 열리지 않는다.
4. 수정·삭제 기능을 넣으려면 이 문서의 전제(조회 전용)부터 다시 검토한다.

## 로컬 개발

```bash
ADMIN_ALLOWLIST=127.0.0.1,::1
ADMIN_ACCOUNTS_0_USERNAME=dev
ADMIN_ACCOUNTS_0_BCRYPT='$2y$...'
ADMIN_ACCOUNTS_0_TOTP=...
```

- 브라우저는 `localhost`에서 `Secure` 쿠키를 http로도 보내므로 `http://localhost:8080/admin/login`으로 된다.
- 로컬은 **개발** Supabase 프로젝트를 본다(운영과 분리). 로컬에서 조회한 기록은 **개발 DB의 `access_log`** 에 남고,
  운영 접속기록과 섞이지 않는다.
- 개발 DB에도 `schema-access-log.sql`이 적용돼 있어 같은 트리거가 걸린다. 로컬에서 남긴 기록도 1년이 지나기 전에는 지워지지 않는다.
- 개발 DB에 운영에서 옮겨 온 회원 데이터가 있으면(`docs/demo-data-migration.md`, `docs/prod-data-wipe.md`)
  그 조회도 개인정보 처리다. 운영과 똑같이 업무 목적으로만 조회한다.

테스트:

| 테스트 | 무엇 | DB |
|---|---|---|
| `AdminSecurityTest` | IP 허용목록·사칭, 로그인/실패/잠금/OTP 재사용, CSRF, 헤더 | 없음(목) |
| `AdminJwtIsolationTest` | 관리자 세션 + 앱 JWT에서 operator 불변, JWT 필터 등록 해제 | 없음(목) |
| `AdminControllerTest` | 화면마다 기록 → 조회 순서, 기록 실패 시 화면 차단, 마스킹·이스케이프 | 없음(목) |
| `ClientIpResolverTest`, `TotpVerifierTest`(RFC 6238 벡터), `AdminAccountConfigTest`, `AccessLogEnforcementInterceptorTest` | 단위 | 없음 |
| `AdminPostgresTest` | SQL 파일, CHECK, 트리거, 정리 쿼리, 조회 쿼리, `ddl-auto: update` 호환 | **로컬 컨테이너 전용** |

`AdminPostgresTest`는 시작할 때 public 스키마를 지운다. 평소에는 꺼져 있고, URL이 localhost일 때만 켜진다.

```bash
docker run -d --name walwang-accesslog-verify -e POSTGRES_PASSWORD=verify \
    -p 127.0.0.1:55432:5432 postgis/postgis:16-3.4-alpine
WALWANG_LOCAL_PG_URL=jdbc:postgresql://127.0.0.1:55432/postgres WALWANG_LOCAL_PG_PASSWORD=verify \
    ./gradlew test --tests '*AdminPostgresTest'
docker rm -f walwang-accesslog-verify
```

## 알려진 한계

- **Supabase 대시보드 경로는 여전히 기록되지 않는다.** 운영 규칙 1·2번과 Supabase 로그 아카이브로 보완한다.
- IP 허용목록을 바꾸려면 재시작해야 한다.
- DB 소유자는 트리거를 지울 수 있다(7번).
- `/internal/**`은 여전히 앱 로그인만으로 부를 수 있다(`docs/admin-runbook.md` "권한"). 관리자 체인이 생겼으니 옮길 수 있다.
- ~~`access_log`만 Supabase REST 권한을 회수했다. 다른 테이블도 열려 있는지 확인이 필요하다.~~
  **완료 (2026-09-13, 운영·개발 두 프로젝트).** 아래 "Supabase REST 권한 회수 기록" 참고.

### Supabase REST 권한 회수 기록 (2026-09-13)

**발견.** 회수 전에는 public 스키마 테이블이 Supabase REST(PostgREST)의 `anon`/`authenticated` 롤에 열려 있었다.

| 테이블 | RLS | `anon` 권한 | anon 키로 조회(curl) |
|---|---|---|---|
| `stores` | false | SELECT | **실제 행이 조회됨** |
| `users` | true | 있음 | `[]` (RLS 정책이 없어 행이 안 나옴) |

`users`는 RLS 덕분에 비어서 나왔을 뿐, 권한 자체는 열려 있었다.

**조치.** 운영·개발 두 프로젝트에서 실행했다.

```sql
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM anon, authenticated;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM anon, authenticated;
```

- 첫 줄: **지금 있는** public 스키마 테이블 전부에서 회수한다.
- 둘째 줄: **앞으로 만드는** 테이블에도 `anon`/`authenticated` 권한이 자동으로 붙지 않게 한다.
  Supabase는 새 테이블에 두 롤의 권한을 기본으로 주는데, 그 기본값을 걷어낸 것이다.
  `ddl-auto: update`가 새 엔티티 테이블을 만들거나 `db/*.sql`로 테이블을 추가해도 따로 REVOKE하지 않아도 된다.

**결과.**
- `stores`, `users` 모두 anon 키 요청이 `permission denied`로 바뀐 것을 확인했다.
- 앱은 정상 동작한다. 앱은 PostgREST가 아니라 JDBC로 `postgres` 계정에 접속하므로 두 롤의 권한을 쓰지 않는다.

**알아둘 범위.**
- `ALTER DEFAULT PRIVILEGES`는 `FOR ROLE`을 붙이지 않으면 **실행한 롤(`postgres`)이 만드는 테이블에만** 적용된다.
  앱(JDBC)과 SQL Editor가 모두 `postgres`라 지금 테이블을 만드는 경로는 다 걸린다.
  다른 롤(예: `supabase_admin`)이 만드는 테이블은 대상이 아니므로 아래 쿼리로 확인한다.
- `ON TABLES`는 테이블·뷰가 대상이다. 시퀀스(`ON SEQUENCES`)와 함수(`ON FUNCTIONS`)는 따로다.
- 새 Supabase 프로젝트를 만들면(`docs/new-supabase-project.md`) 위 두 줄을 다시 실행한다.
- `access_log`의 개별 REVOKE는 `schema-access-log.sql`에 그대로 둔다. 스키마 전체 회수를 빼먹은 프로젝트에서도 접속기록만큼은 닫히게 하려는 것이다.

**재확인.** 스키마를 바꾼 뒤나 월 1회 점검 때 돌린다.

```sql
-- 테이블 권한: 0행이어야 한다
SELECT table_name, grantee, string_agg(privilege_type, ',')
  FROM information_schema.role_table_grants
 WHERE table_schema = 'public' AND grantee IN ('anon', 'authenticated')
 GROUP BY table_name, grantee ORDER BY table_name;

-- 기본 권한: public 스키마의 테이블(r) 줄에 anon / authenticated 가 없어야 한다
SELECT pg_get_userbyid(d.defaclrole) AS 만드는_롤, d.defaclobjtype AS 종류, d.defaclacl
  FROM pg_default_acl d
  JOIN pg_namespace n ON n.oid = d.defaclnamespace
 WHERE n.nspname = 'public';
```

```bash
# anon 키로 직접 찔러 본다. permission denied(42501)가 나와야 한다.
curl -s "https://<프로젝트 ref>.supabase.co/rest/v1/stores?select=store_id&limit=1" \
  -H "apikey: <anon 키>" -H "Authorization: Bearer <anon 키>"
```
