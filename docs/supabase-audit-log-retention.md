# Supabase 접속기록 1년 보관

개인정보보호법 시행령 제30조 / 「개인정보의 안전성 확보조치 기준」 제8조는
개인정보취급자의 개인정보처리시스템 접속기록을 **최소 1년** 보관하도록 한다.
Supabase 무료 플랜은 로그를 **1일**만 남기므로, 매일 받아서 EC2 에 쌓는다.

- 스크립트: `scripts/supabase-audit-archive.sh`
- 설정 템플릿: `scripts/supabase-audit.config.example`
- 저장 위치: `/var/log/supabase-audit/YYYY-MM-DD.json.gz` (+ 같은 이름의 `.sha256`)

---

## ⚠️ 먼저 알아야 할 네 가지

### 1. 새벽 4시에 돌리면 매일 4시간치가 비어서 온다

무료 플랜의 보존은 **롤링 24시간**이다. 9월 12일 04:00 에 9월 11일 하루치를 요청하면
9월 11일 00:00~04:00 구간은 이미 28시간 전이라 Supabase 쪽에서 지워진 뒤다.

스크립트는 이 구간을 조회 실패와 구분해서 `보존 한계로 소실된 구간` 으로 남기고
**exit 3** 으로 끝낸다. 조용히 반쪽짜리 파일을 남기지 않는다.

| cron 시각(KST) | 하루치 중 손실 |
|---|---|
| `0 4 * * *` (04:00) | 약 4시간 |
| `5 0 * * *` (00:05) | 약 5분 |

**`5 0 * * *` 를 권한다.** 손실을 0 으로 만들려면 무료 플랜으로는 안 되고,
Pro 플랜의 Log Drains 로 실시간 내보내기를 쓰거나 하루 두 번 이상 돌려야 한다.

### 2. 구 엔드포인트는 2026-09-23 에 제거된다

`API_MODE` 로 고른다. **현재 기본값은 `legacy`** 다(운영 프로젝트에서 신 엔드포인트가
`Backend error` 를 내기 때문. 4번 참고).

| `API_MODE` | 경로 | SQL | 상태 |
|---|---|---|---|
| `legacy` | `/v1/projects/{ref}/analytics/endpoints/logs.all` | BigQuery | 2026-09-23 제거 |
| `clickhouse` | `/v1/projects/{ref}/analytics/endpoints/logs` | ClickHouse | 후속 |

전환 절차는 아래 "엔드포인트 전환" 절에 있다.

### 3. 대시보드 접속기록은 무료 플랜에서 API 로 받을 수 없다

Supabase 에서 "누가 대시보드에 로그인해서 무엇을 했는가"를 남기는 것은
**Platform Audit Logs** 인데, **Team / Enterprise 플랜 전용**이다. 무료 플랜에는 없다.

그래서 이 아카이브로 대신 잡는 것은 다음 두 가지다.

- `postgres_logs` — 대시보드 SQL Editor 로 실행한 쿼리와 DB 접속이 남는다.
  관리자가 테이블을 직접 열람한 흔적은 실질적으로 여기서 본다.
- `edge_logs` — 대시보드의 Table Editor 등이 거치는 API 게이트웨이 요청.

이것으로 "언제 어디서 접속했는가"는 상당 부분 재구성되지만,
**계정 단위로 딱 떨어지는 대시보드 감사기록은 무료 플랜에서 확보할 수 없다.**
이게 문제가 되면 플랜 상향 말고는 방법이 없다. 규정 대응상 이 한계는 문서로 남겨 두는 게 낫다.

### 4. Supabase 로그만으로 법정 항목이 다 채워지지는 않는다

제8조가 요구하는 항목은 계정 / 접속일시 / 접속지 정보 / **처리한 정보주체 정보** /
**수행업무** 다. Supabase 로그는 인프라 레벨이라 앞의 셋은 담기지만 뒤의 둘은
요청 경로에서 추정하는 수준이다. 이 아카이브는 필요조건이지 충분조건이 아니고,
"어떤 관리자가 누구의 개인정보를 왜 조회했는지"는 앱에서 따로 남겨야 한다.

---

## 수집 소스

`LOG_SOURCES` 에 공백으로 구분해 적는다. 프로젝트마다 존재하는 소스가 다르므로
**반드시 `--check-sources` 로 먼저 확인**하고 적는다. 없는 소스를 적으면 매일 exit 2 로 죽는다.

| 소스 | 내용 | 제8조 관점 |
|---|---|---|
| `edge_logs` | API 게이트웨이. 모든 REST/GraphQL 요청 | **필수** — 접속일시·접속지 IP·경로 |
| `postgres_logs` | DB 쿼리·접속. 대시보드 SQL Editor 포함 | **필수** — 관리자 직접 열람 |
| `auth_logs` | 로그인 / JWT / OAuth / 이메일 | **필수** — 계정 인증 |
| `storage_logs` | Storage API 업로드·객체 접근 | **필수** — 여권 이미지 등 접근 |
| `auth_audit_logs` | Auth 감사 이벤트 | 권장 — 프로젝트에 따라 없을 수 있다 |
| `postgrest_logs` | PostgREST 프로세스 로그 | 선택 — 저신호. 증거는 보통 위 둘에 있다 |
| `realtime_logs` | Realtime 채널·presence | 선택 |
| `function_edge_logs` / `function_logs` | Edge Function 요청 / 콘솔 출력 | 선택 |
| `supavisor_logs` / `pgbouncer_logs` | 커넥션 풀러 | 선택 |
| `pg_upgrade_logs` | DB 버전 업그레이드 | 선택 |

기본값은 `edge_logs postgres_logs auth_logs storage_logs` 다.

소스를 늘릴수록 요청 수가 (소스 수 × 24구간 × 페이지 수) 로 늘고 rate limit 에 가까워진다.
저신호 소스를 무작정 다 넣지 말고, 위 표의 "필수"부터 채워라.

---

## 동작 방식

1. KST 기준 전날 00:00~24:00 을 1시간짜리 24 구간으로 나눈다.
   (API 는 한 요청당 최대 24시간, 분 단위 반올림)
2. 소스마다 각 구간을 조회한다. 응답이 **1000행에서 잘리므로** 1000행이 꽉 차면
   `offset` 을 올려 이어 받는다.
3. 각 행에 `_source` 를 붙여 NDJSON(줄마다 JSON 객체 1개)으로 모아 gzip 한다.
   - 단일 JSON 배열이 아닌 이유는 하루치가 수만 줄이 되기 때문이다. `jq` 는 양쪽 다 읽는다.
   - `_source` 를 붙이는 이유는, 구 엔드포인트가 소스별 테이블을 따로 조회하므로
     응답만 봐서는 어느 소스에서 온 행인지 알 수 없기 때문이다.
4. 임시 파일에 쓴 뒤 원자적으로 옮긴다. 중간에 죽어도 반쪽 파일이 남지 않는다.
5. `sha256sum` 을 사이드카 파일로 남긴다(위·변조 방지).
6. 파일명 날짜 기준으로 365일 지난 파일을 지운다. mtime 이 아니라 파일명을 본다.

### SQL

`select *` 는 쓰지 않는다. 프로젝트에 따라 `Backend error` 가 난다.

```sql
-- legacy (BigQuery)
select id, timestamp, event_message, metadata
  from edge_logs order by timestamp asc, id asc limit 1000 offset 0

-- clickhouse
select id, timestamp, event_message, severity_text, source, log_attributes
  from logs where source = 'edge_logs' order by timestamp asc, id asc limit 1000 offset 0
```

`metadata` 를 붙인 쿼리만 실패하는 프로젝트가 있어서, legacy 모드에서는 실패하면
`metadata` 를 빼고 한 번 더 시도한다. 이 경우 접속지 IP 같은 구조화 필드는
`event_message` 안에만 남으므로 로그에 경고를 남긴다.

### 종료 코드

| 코드 | 뜻 |
|---|---|
| 0 | 정상 |
| 1 | 설정/환경 오류 (토큰 파일 권한, `jq` 없음, tzdata 없음, 잘못된 `API_MODE`) |
| 2 | API 호출 실패 (재시도 후에도 실패) |
| 3 | 응답이 비어 있음 / 보존 한계로 구간이 소실됨 |

0 이 아닌 코드로 끝나면 그 날짜 파일은 **만들지 않는다.** 기존 파일도 덮어쓰지 않는다.

---

## 설치

### 0. 의존성

```bash
# Amazon Linux 2023
sudo dnf install -y jq tzdata
# Ubuntu
sudo apt-get install -y jq tzdata
```

`tzdata` 가 없으면 `TZ=Asia/Seoul` 이 조용히 무시돼 UTC 로 계산된다.
그러면 9시간 어긋난 구간을 저장하게 되므로 스크립트가 시작할 때 막는다.

### 1. 토큰 발급

Supabase 대시보드 > 우상단 계정 > **Account Settings > Access Tokens** >
`Generate new token`.

- 이름: `ec2-audit-archive` 처럼 용도를 알 수 있게
- 권한을 고를 수 있으면 **`analytics_logs_read` (읽기)만** 준다.
  Management API 토큰은 기본적으로 프로젝트를 지울 수도 있는 물건이다.
- 토큰은 발급 직후 한 번만 보인다. 바로 복사한다.

### 2. 배치

```bash
sudo install -d -m 750 /etc/supabase-audit
sudo install -d -m 750 /var/log/supabase-audit
sudo install -m 750 scripts/supabase-audit-archive.sh /usr/local/bin/supabase-audit-archive
sudo install -m 640 scripts/supabase-audit.config.example /etc/supabase-audit/config
sudo vi /etc/supabase-audit/config      # PROJECT_REF 에 운영 프로젝트 ref 를 적는다
```

### 3. 토큰 파일 (권한 600)

셸 히스토리에 토큰이 남지 않게 리다이렉트 대신 붙여넣기로 만든다.

```bash
sudo install -m 600 /dev/null /etc/supabase-audit/token
sudo tee /etc/supabase-audit/token >/dev/null    # 토큰 붙여넣고 Enter, Ctrl-D
sudo chmod 600 /etc/supabase-audit/token
sudo chown root:root /etc/supabase-audit/token
stat -c '%a %U:%G %n' /etc/supabase-audit/token  # 600 root:root 확인
```

스크립트는 이 파일이 600 이 아니거나 실행 사용자 소유가 아니면 **실행을 거부한다.**

### 4. 연결 확인 (파일을 쓰지 않는다)

```bash
sudo /usr/local/bin/supabase-audit-archive --probe
```

최근 5분 로그 3건의 원본 JSON 이 나온다. 401 이면 토큰/권한, 404 면 `PROJECT_REF` 다.

### 5. 쓸 수 있는 소스 확인

```bash
sudo /usr/local/bin/supabase-audit-archive --check-sources
```

각 소스를 하나씩 조회해 보고, 마지막에 `LOG_SOURCES="..."` 줄을 출력한다.
그 줄을 그대로 `/etc/supabase-audit/config` 에 붙여 넣는다.

플랜을 올리거나 서비스를 켠 뒤에는 다시 돌려서 새로 생긴 소스를 반영한다.

### 6. 하루치 수동 실행

```bash
sudo /usr/local/bin/supabase-audit-archive --date "$(TZ=Asia/Seoul date -d yesterday +%F)"
echo "exit=$?"
ls -l /var/log/supabase-audit/
zcat /var/log/supabase-audit/*.json.gz | head -3 | jq .
zcat /var/log/supabase-audit/*.json.gz | jq -r ._source | sort | uniq -c
```

### 7. cron 등록

```bash
sudo tee /etc/cron.d/supabase-audit >/dev/null <<'EOF'
SHELL=/bin/bash
PATH=/usr/local/sbin:/usr/local/bin:/sbin:/bin:/usr/sbin:/usr/bin
CRON_TZ=Asia/Seoul
MAILTO=""
5 0 * * * root /usr/local/bin/supabase-audit-archive >> /var/log/supabase-audit/archive.log 2>&1
EOF
sudo chmod 644 /etc/cron.d/supabase-audit
```

- `CRON_TZ=Asia/Seoul` 이 있어야 서버가 UTC 여도 KST 00:05 에 돈다.
  (구형 cron 이라 `CRON_TZ` 를 모르면 서버 시계 기준으로 환산해 적어야 한다 — UTC 서버면 `5 15 * * *`)
- 굳이 04:00 로 하려면 `0 4 * * *` 로 바꾼다. 위 "⚠️ 1번"의 손실을 감수하는 것이다.
- `archive.log` 는 `*.json.gz` 가 아니라서 365일 정리 대상에 걸리지 않는다.
  따로 `/etc/logrotate.d` 에 넣어 두면 좋다.

### 8. 실패 감시

cron 은 0 이 아닌 코드로 끝나도 아무도 안 알려준다. 최소한 이 정도는 걸어 둔다.

```bash
zcat /var/log/supabase-audit/$(TZ=Asia/Seoul date -d yesterday +%F).json.gz | wc -l
grep -E '\[ERROR\]' /var/log/supabase-audit/archive.log | tail -20
```

---

## 엔드포인트 전환 (2026-09-23 전에)

`logs.all` 은 2026-09-23 에 제거된다. 그날 이후 `API_MODE="legacy"` 로 두면
아카이브가 통째로 멈춘다. **그 전에 아래를 끝내야 한다.**

### 무엇이 바뀌는가

| | legacy | clickhouse |
|---|---|---|
| 경로 | `analytics/endpoints/logs.all` | `analytics/endpoints/logs` |
| SQL 방언 | BigQuery | ClickHouse |
| 테이블 | 소스별 (`edge_logs`, `postgres_logs` …) | 단일 `logs` + `source` 컬럼 |
| 중첩 필드 | `cross join unnest(metadata)` | `log_attributes['request.method']` |
| 저장되는 컬럼 | `id, timestamp, event_message, metadata` | `id, timestamp, event_message, severity_text, source, log_attributes` |

스크립트는 두 방언의 SQL 을 이미 다 들고 있다. **`API_MODE` 만 바꾸면 된다.**

### 절차

```bash
# 1) 신 엔드포인트가 이 프로젝트에서 응답하는지 확인 (설정은 그대로 두고 1회성 확인)
sudo API_MODE=clickhouse /usr/local/bin/supabase-audit-archive --probe

# 2) 소스 이름이 그대로 통하는지 확인
sudo API_MODE=clickhouse /usr/local/bin/supabase-audit-archive --check-sources

# 3) 하루치를 실제로 받아 본다 (기존 파일을 덮어쓰므로 날짜에 주의)
sudo API_MODE=clickhouse /usr/local/bin/supabase-audit-archive \
     --date "$(TZ=Asia/Seoul date -d yesterday +%F)" --no-prune

# 4) 다 되면 설정을 바꾼다
sudo sed -i 's/^API_MODE=.*/API_MODE="clickhouse"/' /etc/supabase-audit/config
```

### 전환 후 달라지는 것

- 아카이브 행의 모양이 바뀐다. `metadata`(중첩 배열) 대신
  `log_attributes`(평평한 맵)와 `severity_text`, `source` 가 들어온다.
- **과거 파일은 바뀌지 않는다.** 즉 아카이브 안에 두 가지 스키마가 공존하게 된다.
  나중에 뒤질 때 `_source` 는 양쪽 다 있으니 그걸 기준으로 잡고,
  세부 필드는 `.metadata` 와 `.log_attributes` 를 모두 보도록 쿼리를 짜라.
- 전환일을 이 문서에 적어 두면 나중에 헤매지 않는다. 전환일: (전환 후 여기 기록)

---

## 신 엔드포인트 `Backend error` 조사

운영 프로젝트에서 `/analytics/endpoints/logs` 가 `Backend error` 를 냈다.
확인된 원인 후보는 둘이고, 둘 다 실재한다.

**(a) 프로젝트가 아직 BigQuery 백엔드다.**
Supabase 는 로그 저장소를 BigQuery 에서 ClickHouse 로 옮기는 중이고,
**ClickHouse 가 기본이 된 것은 2026년 6월부터**다. 그 전에 만들어진 프로젝트는
여전히 BigQuery 를 쓴다. 신 엔드포인트는 ClickHouse 를 읽으므로
아직 안 옮겨진 프로젝트에서는 정상 동작하지 않는다.

**(b) 쿼리가 문제다.**
신 엔드포인트에서 `describe logs` 나 `select … from system.columns` 같은
스키마 조회가 `Backend error! Retry your query` 를 낸다는 보고가 있다.
처음 작성한 `select *` 도 같은 부류일 수 있다.

**(c) 덤 — 문서가 틀렸다.**
공식 마이그레이션 문서는 `source_name` 으로 필터하라고 하지만,
실제 엔드포인트는 `source` 만 받는다 (`source_name` 을 쓰면
`Field "source_name" does not exist`). 스크립트는 `source` 를 쓴다.

### 어느 쪽인지 가르는 방법

컬럼을 명시한 쿼리로 신 엔드포인트를 한 번 때려 본다.

```bash
sudo API_MODE=clickhouse /usr/local/bin/supabase-audit-archive --probe
```

- **행이 나온다** → (b) 였다. `select *` 가 문제였을 뿐이고, 지금 스크립트로는 신 엔드포인트가 쓸 수 있다.
  `API_MODE="clickhouse"` 로 바꿔도 된다.
- **여전히 `Backend error`** → (a) 다. 이 프로젝트는 아직 ClickHouse 로 안 옮겨졌다.
  `legacy` 로 버티면서 9/23 전까지 주기적으로 위 명령을 다시 돌려 보고,
  9월 중순까지도 안 열리면 Supabase 지원에 프로젝트 ref 를 대고 마이그레이션 일정을 물어야 한다.
  **9/23 에 logs.all 이 사라지는데 ClickHouse 도 안 열려 있으면 수집이 끊긴다.**

---

## 아카이브 조회

```bash
# 소스별 건수
zcat /var/log/supabase-audit/2026-09-11.json.gz | jq -r ._source | sort | uniq -c

# 인증 로그만
zcat /var/log/supabase-audit/2026-09-11.json.gz | jq -c 'select(._source == "auth_logs")'

# 무결성 확인
cd /var/log/supabase-audit && sha256sum -c 2026-09-11.json.gz.sha256
```

---

## 검증 상태

로직(모드별 경로/SQL 전환, 소스 × 구간 순회, 1000행 페이지네이션, `_source` 주입,
metadata 폴백, 없는 소스 판별, gzip/체크섬, 빈 응답 → exit 3, HTTP 500 → exit 2,
401 즉시 실패, 365일 파일명 기준 정리)은 API 를 흉내 낸 stub 으로 확인했다.

**실제 Supabase API 응답으로는 검증하지 않았다.** 그래서 `--probe` 와 `--check-sources`
가 cron 등록보다 앞에 있다. 순서대로 돌려서 눈으로 확인하고 넘어가라.
