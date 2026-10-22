# walwang_BE

Spring Boot 4.1 / Java 17 / PostgreSQL / Redis 기반 API 서버.

## 실행 준비

저장소에는 비밀정보가 포함되어 있지 않다. clone 후 아래 두 가지를 직접 준비해야 실행된다.

### 1. RSA 키 생성

JWT를 RS256으로 서명하므로 키 쌍이 필요하다.

```bash
mkdir -p src/main/resources/keys
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out src/main/resources/keys/private_key.pem
openssl rsa -pubout -in src/main/resources/keys/private_key.pem -out src/main/resources/keys/public_key.pem
```

개인키는 PKCS#8(`BEGIN PRIVATE KEY`), 공개키는 X.509(`BEGIN PUBLIC KEY`) 형식이어야 한다.
위 명령은 그대로 맞는 형식으로 나온다.

로컬 개발은 각자 키를 써도 되지만, **배포 서버는 하나의 키를 공유해야 한다.**
키가 다르면 A가 발급한 토큰을 B의 서버가 검증하지 못한다.

### 2. application.yaml 작성

```bash
cp src/main/resources/application-example.yaml src/main/resources/application.yaml
```

복사한 뒤 DB / Redis 접속 정보를 채운다. 이 파일은 `.gitignore`에 있으므로 커밋되지 않는다.

### 3. Redis 실행

```bash
docker run -d -p 6379:6379 --name walwang-redis redis:7-alpine
```

### 실행

```bash
./gradlew bootRun
```

API 문서: http://localhost:8080/swagger-ui/index.html

## 인증 구조

| | 형태 | 수명 | 저장 위치 |
|---|---|---|---|
| Access Token | JWT (RS256) | 1시간 | 클라이언트만 |
| Refresh Token | 불투명한 UUID | 14일 | Redis |

Access Token payload는 `{sub, iat, exp}`이고 `sub`에 `user_id`가 들어간다.

Refresh Token을 JWT로 만들지 않은 이유는, 같은 키로 서명하면 Refresh Token을
`Authorization` 헤더에 그대로 넣어도 검증을 통과해버리기 때문이다.

Redis 키 구조:

```
refresh:{token}        -> user_id           조회용, TTL 14일
user_refresh:{userId}  -> Set{token, ...}   일괄 폐기용 역인덱스
blacklist:{sha256}     -> 1                 로그아웃된 access, TTL = 잔여 만료시간
```

동작상 알아둘 점:

- **재발급 시 회전.** 쓰인 Refresh Token은 즉시 폐기되고 새 쌍이 나온다.
- **로그아웃은 두 가지를 함께 한다.** Refresh 삭제(재발급 차단) + Access 블랙리스트(즉시 무효화).
- **비밀번호를 바꾸면 그 계정의 Refresh Token이 전부 폐기된다.** 다른 기기는 로그아웃되고,
  요청을 보낸 기기는 응답으로 받은 새 토큰 쌍으로 계속 쓸 수 있다.
  다만 다른 기기의 Access Token은 만료(최대 1시간)까지 유효하다.

## API

| 메서드 | 경로 | 인증 | 설명 |
|---|---|---|---|
| POST | `/user/signUp` | - | 회원가입 + 약관 동의 저장 |
| POST | `/user/login` | - | 200 + `Authorization` 헤더 + `{refreshToken}` |
| POST | `/user/reissue` | - | Access Token 재발급 |
| GET | `/user/check-email` | - | 중복 없으면 200, 있으면 409 |
| GET | `/user/check-nickname` | - | 중복 없으면 200, 있으면 409 |
| POST | `/user/logout` | 필요 | |
| PATCH | `/user/me` | 필요 | 회원 수정 |

로그인/재발급/회원수정은 모두 같은 형태로 응답한다 —
Access Token은 `Authorization: Bearer ...` 헤더, Refresh Token은 본문 `{refreshToken}`.

앱에서는 401을 받으면 `/user/reissue`로 재발급 후 원래 요청을 재시도하는
인터셉터를 두면 사용자가 만료를 인지하지 못한다.

## 관리자 페이지

`/admin` — 회원 조회와 접속기록 열람(서버 렌더링, 조회 전용). IP 허용목록 + 비밀번호 + OTP로 들어가고,
모든 회원 정보 조회가 `access_log`에 남는다. 설치·운영 규칙·설계 근거는 `docs/admin-access-log.md`.

## 약관

`entity/TermCode.java`의 enum이 약관 종류와 현재 버전을 관리한다.
동의 시점의 버전이 `user_agreements.term_version`에 기록되므로,
누가 어느 판본에 동의했는지는 그 행으로 가린다.

### 버전 표

| 버전 | 시행일 | 비고 |
|---|---|---|
| v1 | 2026-09-13 | 최초 |
| v2 | 2026-11-02 | 이용약관·개인정보 수집·이용 동의 개정. 만 14세 확인은 내용 변경 없이 v1 유지 |

### 개정 절차

1. 개정된 약관의 `TermCode` 상수 버전을 올린다(`v2` → `v3`). **내용이 바뀌지 않은 약관은 그대로 둔다** —
   버전 문자열 하나가 문서 한 판본을 가리켜야 구버전 동의자를 가려낼 수 있다.
2. 위 버전 표에 시행일을 적는다. 버전 값 자체는 `v1`, `v2` … 로 쓰고 시행일은 표로만 관리한다.
3. 프론트의 약관 문구 교체와 배포 시점을 맞춘다. 현재 약관 버전·본문을 내려주는 엔드포인트가 없어
   서버 기록과 이용자가 실제로 본 문구가 어긋날 수 있다.

버전 값은 `user_agreements.term_version`(varchar 20)에 들어가므로 20자를 넘기지 않는다.
이미 저장된 동의 이력은 수정하지 않는다 — 동의 시점의 기록이라 사후 변경하면 근거로 쓸 수 없다.

### 재동의

개정 후 기존 회원에게 다시 동의를 받는 경로는 아직 없다.
`UserAgreement` 를 저장하는 곳은 `UserService.signUp()` 하나뿐이라,
개정하면 그 뒤 가입자만 새 버전으로 기록되고 기존 회원은 구버전 행이 남는다.
대상자는 `UserAgreementRepository.findUserIdsNeedingReconsent(TermCode)` 로 뽑을 수 있다
(구버전에만 동의하고 현재 버전 동의 행이 없는 회원). 지금 호출하는 곳은 없다.
