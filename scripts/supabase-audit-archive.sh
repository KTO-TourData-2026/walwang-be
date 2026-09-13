#!/usr/bin/env bash
#
# Supabase 접속기록(로그) 1년 보관 아카이버
#
# 개인정보보호법 시행령 제30조 / 「개인정보의 안전성 확보조치 기준」 제8조에 따른
# 개인정보취급자의 개인정보처리시스템 접속기록 보관(1년)을 위해,
# Supabase 무료 플랜의 1일 로그 보존 한계를 넘겨 EC2 로컬 디스크에 매일 적재한다.
#
# 저장 형식: /var/log/supabase-audit/YYYY-MM-DD.json.gz
#   - 내용은 NDJSON(줄마다 JSON 객체 1개)을 gzip 한 것이다.
#     하루치가 수만 줄이 될 수 있어 단일 JSON 배열 대신 줄 단위로 쌓는다.
#     `zcat 2026-09-11.json.gz | jq .` 로 그대로 읽힌다.
#   - 각 행에 `_source` 키를 덧붙인다. 어느 로그 소스에서 온 행인지는
#     구 엔드포인트 응답만 보고는 알 수 없기 때문이다.
#   - 같은 이름의 .sha256 파일을 함께 남긴다(위·변조 방지 조치).
#
# 엔드포인트는 API_MODE 로 고른다.
#   legacy     : /analytics/endpoints/logs.all  + BigQuery SQL  (2026-09-23 제거 예정)
#   clickhouse : /analytics/endpoints/logs      + ClickHouse SQL (후속)
# 전환 방법은 docs/supabase-audit-log-retention.md 의 "엔드포인트 전환" 절에 있다.
#
# 의존성: bash 4+, curl, jq, gzip, GNU coreutils(date, stat, find), sha256sum
#
# 종료 코드
#   0  정상
#   1  설정/환경 오류 (토큰 파일 권한, 필수 명령 없음 등)
#   2  API 호출 실패
#   3  응답이 비어 있음 / 보존 한계로 구간이 소실됨
#
set -euo pipefail

umask 077

# ---------------------------------------------------------------------------
# 설정 — /etc/supabase-audit/config 에서 덮어쓸 수 있다
# ---------------------------------------------------------------------------
CONFIG_FILE="${SUPABASE_AUDIT_CONFIG:-/etc/supabase-audit/config}"

# 설정 파일은 값을 무조건 대입하므로, 그냥 두면 환경변수가 config 에 먹힌다.
# 일회성 확인(`API_MODE=clickhouse ... --probe`)이 통하도록
# 설정을 읽기 전에 환경변수를 기억해 뒀다가 읽은 뒤에 다시 씌운다.
OVERRIDABLE_VARS="PROJECT_REF TOKEN_FILE ARCHIVE_DIR RETENTION_DAYS API_MODE LOG_SOURCES
                  SOURCE_RETENTION_HOURS API_BASE PAGE_SIZE CHUNK_HOURS MAX_RETRIES REQUEST_INTERVAL"
declare -A ENV_OVERRIDE=()
for _v in $OVERRIDABLE_VARS; do
  if [ -n "${!_v+set}" ]; then ENV_OVERRIDE["$_v"]="${!_v}"; fi
done
unset _v

# 운영 프로젝트 ref 하나만 적는다. 개발 프로젝트는 여기 넣지 않는다.
PROJECT_REF="${PROJECT_REF:-}"
TOKEN_FILE="${TOKEN_FILE:-/etc/supabase-audit/token}"
ARCHIVE_DIR="${ARCHIVE_DIR:-/var/log/supabase-audit}"
RETENTION_DAYS="${RETENTION_DAYS:-365}"

# legacy | clickhouse
API_MODE="${API_MODE:-legacy}"

# 수집할 로그 소스. 공백으로 구분한다.
# 프로젝트마다 존재하는 소스가 다르므로 --check-sources 로 먼저 확인하고 적는다.
LOG_SOURCES="${LOG_SOURCES:-edge_logs postgres_logs auth_logs storage_logs}"

# Supabase 무료 플랜의 로그 보존 시간(롤링). 이 값보다 오래된 구간은
# 조회해도 이미 지워져 있으므로 "보존 한계로 인한 공백"으로 구분해 기록한다.
SOURCE_RETENTION_HOURS="${SOURCE_RETENTION_HOURS:-24}"

API_BASE="${API_BASE:-https://api.supabase.com}"
# 한 번에 받을 수 있는 최대 행 수. Supabase 로그 쿼리는 1000행에서 잘린다.
PAGE_SIZE="${PAGE_SIZE:-1000}"
# 하루를 몇 시간짜리 구간으로 쪼개 조회할지. API 는 한 요청당 최대 24시간을 허용하고
# 분 단위로 반올림한다. 1시간이면 페이지네이션 깊이가 얕게 유지된다.
CHUNK_HOURS="${CHUNK_HOURS:-1}"
MAX_RETRIES="${MAX_RETRIES:-3}"
REQUEST_INTERVAL="${REQUEST_INTERVAL:-1}"   # analytics 엔드포인트는 rate limit 이 빡빡하다

if [ -r "$CONFIG_FILE" ]; then
  # shellcheck source=/dev/null
  . "$CONFIG_FILE"
fi

# 환경변수로 들어온 값을 config 위에 다시 씌운다.
for _v in "${!ENV_OVERRIDE[@]}"; do
  printf -v "$_v" '%s' "${ENV_OVERRIDE[$_v]}"
done
unset _v

TARGET_DATE=""
PROBE=0
CHECK_SOURCES=0
PRUNE=1

# ---------------------------------------------------------------------------
# 로깅
# ---------------------------------------------------------------------------
log()  { printf '%s [%s] %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "${*:2}"; }
info() { log INFO "$@"; }
warn() { log WARN "$@" >&2; }
err()  { log ERROR "$@" >&2; }
die()  { err "$2"; exit "$1"; }

usage() {
  cat <<'USAGE'
사용법: supabase-audit-archive.sh [옵션]

  --date YYYY-MM-DD   이 날짜(KST 기준)를 아카이브한다. 기본값은 어제.
  --probe             최근 5분 로그를 조회해 원본 응답을 출력하고 끝낸다.
                      토큰/엔드포인트/스키마 확인용. 파일을 쓰지 않는다.
  --check-sources     LOG_SOURCES 의 각 소스를 하나씩 조회해 보고,
                      이 프로젝트에서 쓸 수 있는 소스만 골라 출력한다. 파일을 쓰지 않는다.
  --no-prune          오래된 파일 삭제를 건너뛴다.
  -h, --help          이 도움말.
USAGE
}

while [ $# -gt 0 ]; do
  case "$1" in
    --date)          TARGET_DATE="${2:-}"; shift 2 ;;
    --probe)         PROBE=1; shift ;;
    --check-sources) CHECK_SOURCES=1; shift ;;
    --no-prune)      PRUNE=0; shift ;;
    -h|--help)       usage; exit 0 ;;
    *)               usage >&2; die 1 "알 수 없는 인자: $1" ;;
  esac
done

# ---------------------------------------------------------------------------
# 사전 점검
# ---------------------------------------------------------------------------
for cmd in curl jq gzip date stat find sha256sum mktemp; do
  command -v "$cmd" >/dev/null 2>&1 || die 1 "필수 명령이 없다: $cmd"
done

# tzdata 가 없으면 TZ=Asia/Seoul 이 조용히 무시되고 UTC 로 계산된다.
# 그러면 9시간 어긋난 구간을 아카이브하게 되므로 실행 전에 막는다.
if [ "$(TZ=Asia/Seoul date -d '2026-01-01 00:00:00 UTC' +%z)" != "+0900" ]; then
  die 1 "Asia/Seoul 시간대를 쓸 수 없다. tzdata 를 설치해라 (dnf install tzdata / apt install tzdata)."
fi

[ -n "$PROJECT_REF" ] || die 1 "PROJECT_REF 가 비어 있다. $CONFIG_FILE 에 운영 프로젝트 ref 를 적어라."

case "$API_MODE" in
  legacy)     API_PATH="analytics/endpoints/logs.all" ;;
  clickhouse) API_PATH="analytics/endpoints/logs" ;;
  *)          die 1 "API_MODE 는 legacy 또는 clickhouse 여야 한다: $API_MODE" ;;
esac

# 토큰 파일은 반드시 600 이어야 한다. 그룹/기타 권한이 열려 있으면 실행을 거부한다.
[ -f "$TOKEN_FILE" ] || die 1 "토큰 파일이 없다: $TOKEN_FILE"

token_perm="$(stat -c '%a' "$TOKEN_FILE")"
if [ "$token_perm" != "600" ]; then
  die 1 "토큰 파일 권한이 $token_perm 이다. 600 이어야 한다: chmod 600 $TOKEN_FILE"
fi

token_owner="$(stat -c '%u' "$TOKEN_FILE")"
if [ "$token_owner" != "$(id -u)" ]; then
  die 1 "토큰 파일 소유자(uid $token_owner)가 실행 사용자(uid $(id -u))와 다르다: $TOKEN_FILE"
fi

SUPABASE_TOKEN="$(tr -d ' \t\r\n' < "$TOKEN_FILE")"
[ -n "$SUPABASE_TOKEN" ] || die 1 "토큰 파일이 비어 있다: $TOKEN_FILE"

# api_query / fetch_page 는 커맨드 치환(서브셸) 안에서 호출되므로 셸 변수로는
# 마지막 오류 메시지나 metadata 폴백 여부를 부모로 되돌릴 수 없다. 파일로 주고받는다.
META_STATE_DIR="$(mktemp -d)"
trap 'rm -rf "$META_STATE_DIR"' EXIT

# ---------------------------------------------------------------------------
# SQL 생성
#
# legacy(BigQuery): 소스마다 테이블이 따로 있다. metadata 는 중첩 RECORD 다.
#   `select *` 는 프로젝트에 따라 Backend error 를 내므로 컬럼을 명시한다.
#   metadata 까지 받으면 접속지 IP 같은 구조화 필드가 함께 보존된다.
#   metadata 를 못 받는 프로젝트를 위해 실패 시 한 번 빼고 재시도한다(아래 fetch_page).
#
# clickhouse: 단일 logs 테이블 + source 컬럼. 문서는 source_name 이라 적었지만
#   실제 구현은 source 만 받는다(공식 문서와 어긋난다는 보고가 있다).
# ---------------------------------------------------------------------------
build_sql() {
  local source="$1" offset="$2" with_metadata="$3"
  if [ "$API_MODE" = "legacy" ]; then
    if [ "$with_metadata" = "1" ]; then
      printf 'select id, timestamp, event_message, metadata from %s order by timestamp asc, id asc limit %s offset %s' \
        "$source" "$PAGE_SIZE" "$offset"
    else
      printf 'select id, timestamp, event_message from %s order by timestamp asc, id asc limit %s offset %s' \
        "$source" "$PAGE_SIZE" "$offset"
    fi
  else
    printf "select id, timestamp, event_message, severity_text, source, log_attributes from logs where source = '%s' order by timestamp asc, id asc limit %s offset %s" \
      "$source" "$PAGE_SIZE" "$offset"
  fi
}

# ---------------------------------------------------------------------------
# API 호출 — 실패 시 재시도(429/5xx/네트워크 오류)
#
# GET /v1/projects/{ref}/{API_PATH}
#   sql                 SQL (API_MODE 에 따라 BigQuery 또는 ClickHouse 방언)
#   iso_timestamp_start 조회 시작 (ISO8601, 분 단위로 반올림됨)
#   iso_timestamp_end   조회 끝. start~end 간격은 24시간을 넘길 수 없다.
# 응답: {"result": [...], "error": ...}
#
# 성공 시 본문을 stdout 으로 낸다. 실패하면 1(재시도 무의미) 을 반환한다.
#
# 실패 사유는 파일에 적는다. api_query 는 커맨드 치환(서브셸) 안에서 호출되므로
# 셸 변수에 담으면 부모로 돌아오지 않는다.
# ---------------------------------------------------------------------------
set_api_err() { printf '%s' "$1" > "$META_STATE_DIR/.last_error"; }
last_api_err() { cat "$META_STATE_DIR/.last_error" 2>/dev/null || true; }

api_query() {
  local sql="$1" iso_start="$2" iso_end="$3"
  local attempt=1 http_code body tmp curl_rc backoff api_err

  set_api_err ""
  tmp="$(mktemp)"
  while :; do
    set +e
    http_code="$(curl -sS -G "$API_BASE/v1/projects/$PROJECT_REF/$API_PATH" \
      -H "Authorization: Bearer $SUPABASE_TOKEN" \
      -H "Accept: application/json" \
      --data-urlencode "sql=$sql" \
      --data-urlencode "iso_timestamp_start=$iso_start" \
      --data-urlencode "iso_timestamp_end=$iso_end" \
      --max-time 120 \
      -o "$tmp" -w '%{http_code}')"
    curl_rc=$?
    set -e

    if [ "$curl_rc" -eq 0 ] && [ "$http_code" = "200" ]; then
      body="$(cat "$tmp")"

      # 200 인데 본문이 JSON 이 아닌 경우가 있다(프록시 오류 페이지, 잘린 응답).
      # 그대로 흘려보내면 뒤의 jq 가 죽으면서 set -e 로 원인 없이 종료한다.
      if ! printf '%s' "$body" | jq -e 'type == "object"' >/dev/null 2>&1; then
        set_api_err "200 인데 JSON 객체가 아닌 응답이 왔다: $(printf '%s' "$body" | head -c 300)"
        rm -f "$tmp"
        return 1
      fi

      # 200 이어도 본문에 error 가 실려 오는 경우가 있다.
      api_err="$(printf '%s' "$body" | jq -r '.error // empty')"
      if [ -n "$api_err" ]; then
        set_api_err "$api_err"
        rm -f "$tmp"
        return 1
      fi
      rm -f "$tmp"
      printf '%s' "$body"
      return 0
    fi

    # 인증 오류는 재시도해도 소용없다.
    if [ "$http_code" = "401" ] || [ "$http_code" = "403" ]; then
      set_api_err "인증 실패(HTTP $http_code). 토큰이 만료됐거나 analytics 읽기 권한이 없다: $(head -c 300 "$tmp")"
      rm -f "$tmp"
      return 1
    fi

    if [ "$attempt" -ge "$MAX_RETRIES" ]; then
      set_api_err "HTTP ${http_code:-curl-$curl_rc}, ${MAX_RETRIES}회 시도 후 포기: $(head -c 300 "$tmp")"
      rm -f "$tmp"
      return 1
    fi

    backoff=$(( 5 * attempt * attempt ))
    warn "API 호출 실패 (HTTP ${http_code:-curl-$curl_rc}), ${backoff}초 후 재시도 ($attempt/$MAX_RETRIES)"
    sleep "$backoff"
    attempt=$(( attempt + 1 ))
  done
}

# 한 페이지를 받는다. legacy 에서 metadata 때문에 실패하면 metadata 없이 한 번 더 시도한다.
# 성공하면 응답 본문을 stdout 으로 낸다.
#
# metadata 폴백 여부는 META_STATE_DIR 안의 파일로 기억한다.
metadata_enabled() {
  [ ! -f "$META_STATE_DIR/$1" ]
}

fetch_page() {
  local source="$1" offset="$2" iso_start="$3" iso_end="$4"
  local with_meta=1 resp
  metadata_enabled "$source" || with_meta=0

  if resp="$(api_query "$(build_sql "$source" "$offset" "$with_meta")" "$iso_start" "$iso_end")"; then
    printf '%s' "$resp"
    return 0
  fi

  # metadata 를 붙인 쿼리만 실패하는 프로젝트가 있다. 한 번 빼고 확인한다.
  if [ "$API_MODE" = "legacy" ] && [ "$with_meta" = "1" ]; then
    warn "[$source] metadata 포함 쿼리 실패($(last_api_err)). metadata 없이 재시도한다."
    if resp="$(api_query "$(build_sql "$source" "$offset" 0)" "$iso_start" "$iso_end")"; then
      : > "$META_STATE_DIR/$source"
      warn "[$source] 이후 metadata 없이 수집한다. 구조화 필드(접속지 IP 등)는 event_message 안에만 남는다."
      printf '%s' "$resp"
      return 0
    fi
  fi
  return 1
}

# ---------------------------------------------------------------------------
# probe — 엔드포인트/토큰/스키마 확인용
# ---------------------------------------------------------------------------
if [ "$PROBE" = "1" ]; then
  p_end="$(date -u +%Y-%m-%dT%H:%M:00Z)"
  p_start="$(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:00Z)"
  first_source="${LOG_SOURCES%% *}"
  info "probe: mode=$API_MODE path=$API_PATH source=$first_source $p_start ~ $p_end (project=$PROJECT_REF)"
  if [ "$API_MODE" = "legacy" ]; then
    p_sql="select id, timestamp, event_message, metadata from $first_source order by timestamp desc limit 3"
  else
    p_sql="select id, timestamp, event_message, severity_text, source, log_attributes from logs where source = '$first_source' order by timestamp desc limit 3"
  fi
  if ! resp="$(api_query "$p_sql" "$p_start" "$p_end")"; then
    err "probe 실패: $(last_api_err)"
    die 2 "토큰/프로젝트 ref/API_MODE 를 확인해라."
  fi
  printf '%s\n' "$resp" | jq .
  info "probe 성공."
  exit 0
fi

# ---------------------------------------------------------------------------
# --check-sources — 이 프로젝트에서 실제로 조회되는 소스만 골라낸다
#
# 소스 목록은 프로젝트가 쓰는 백엔드(BigQuery/ClickHouse)와 활성화된 서비스에 따라 다르다.
# 없는 소스를 LOG_SOURCES 에 두면 매일 exit 2 로 죽으므로 먼저 걸러 낸다.
# ---------------------------------------------------------------------------
if [ "$CHECK_SOURCES" = "1" ]; then
  c_end="$(date -u +%Y-%m-%dT%H:%M:00Z)"
  c_start="$(date -u -d '60 minutes ago' +%Y-%m-%dT%H:%M:00Z)"
  info "소스 점검: mode=$API_MODE $c_start ~ $c_end"
  ok_list=""
  for src in $LOG_SOURCES; do
    if resp="$(fetch_page "$src" 0 "$c_start" "$c_end")"; then
      n="$(printf '%s' "$resp" | jq '.result | length')"
      meta_note=""
      metadata_enabled "$src" || meta_note=" (metadata 제외)"
      info "  OK    $src — 최근 1시간 ${n}건$meta_note"
      ok_list="$ok_list $src"
    else
      warn "  실패  $src — $(last_api_err)"
    fi
    sleep "$REQUEST_INTERVAL"
  done
  echo
  echo "아래 줄을 $CONFIG_FILE 에 넣어라:"
  echo "LOG_SOURCES=\"${ok_list# }\""
  exit 0
fi

# ---------------------------------------------------------------------------
# 대상 날짜 결정 (KST 기준 전날)
# ---------------------------------------------------------------------------
if [ -z "$TARGET_DATE" ]; then
  TARGET_DATE="$(TZ=Asia/Seoul date -d 'yesterday' +%Y-%m-%d)"
fi
if ! date -d "$TARGET_DATE" >/dev/null 2>&1; then
  die 1 "날짜 형식이 잘못됐다: $TARGET_DATE (YYYY-MM-DD)"
fi

mkdir -p "$ARCHIVE_DIR"
chmod 750 "$ARCHIVE_DIR"

OUT_FILE="$ARCHIVE_DIR/$TARGET_DATE.json.gz"
SUM_FILE="$ARCHIVE_DIR/$TARGET_DATE.json.gz.sha256"

info "아카이브 시작: $TARGET_DATE (KST), project=$PROJECT_REF, mode=$API_MODE"
info "수집 소스: $LOG_SOURCES"

if [ -s "$OUT_FILE" ]; then
  warn "$OUT_FILE 가 이미 있다. 덮어쓴다."
fi

# 시각 계산은 전부 epoch 정수 연산으로 한다.
# `date -d 'TZ="Asia/Seoul" ... + N hours'` 형태는 상대 시간 항목과 섞이면
# GNU date 가 엉뚱하게(때로는 반대 방향으로) 계산하므로 쓰지 않는다.
day_start_epoch="$(TZ=Asia/Seoul date -d "$TARGET_DATE 00:00:00" +%s)"

# 원본 보존 한계를 넘는 구간이 있는지 미리 알린다.
retention_floor_epoch="$(( $(date +%s) - SOURCE_RETENTION_HOURS * 3600 ))"
if [ "$day_start_epoch" -lt "$retention_floor_epoch" ]; then
  gap_h=$(( (retention_floor_epoch - day_start_epoch + 3599) / 3600 ))
  warn "원본 보존 한계(${SOURCE_RETENTION_HOURS}시간)를 넘은 구간이 약 ${gap_h}시간 있다."
  warn "해당 구간은 Supabase 에서 이미 삭제되어 받을 수 없다. cron 실행 시각을 앞당겨야 한다."
fi

# ---------------------------------------------------------------------------
# 소스 × 시간 구간으로 수집
# ---------------------------------------------------------------------------
TMP_NDJSON="$(mktemp)"
# META_STATE_DIR 정리도 같이 걸어야 한다. trap 은 덮어쓰기라서 따로 걸면 앞의 것이 사라진다.
trap 'rm -f "$TMP_NDJSON"; rm -rf "$META_STATE_DIR"' EXIT

total_rows=0
failed_chunks=0
gap_chunks=0
chunks=$(( 24 / CHUNK_HOURS ))

for src in $LOG_SOURCES; do
  src_rows=0

  for (( i = 0; i < chunks; i++ )); do
    c_start_epoch=$(( day_start_epoch + i * CHUNK_HOURS * 3600 ))
    c_end_epoch=$(( c_start_epoch + CHUNK_HOURS * 3600 ))
    c_start="$(date -u -d "@$c_start_epoch" +%Y-%m-%dT%H:%M:%SZ)"
    c_end="$(date -u -d "@$c_end_epoch" +%Y-%m-%dT%H:%M:%SZ)"

    chunk_rows=0
    page_offset=0
    chunk_failed=0

    while :; do
      # 페이지네이션: 응답이 1000행에서 잘리므로 OFFSET 으로 이어 받는다.
      # timestamp 만으로는 동시각 행의 순서가 흔들릴 수 있어 id 를 보조 정렬키로 쓴다.
      if ! resp="$(fetch_page "$src" "$page_offset" "$c_start" "$c_end")"; then
        err "[$src] 구간 조회 실패: $c_start ~ $c_end (offset=$page_offset) — $(last_api_err)"
        failed_chunks=$(( failed_chunks + 1 ))
        chunk_failed=1
        break
      fi

      n="$(printf '%s' "$resp" | jq '.result | length')"
      if [ "$n" -gt 0 ]; then
        # 어느 소스에서 온 행인지 _source 로 남긴다.
        # 구 엔드포인트는 소스별 테이블을 따로 조회하므로 응답만 봐서는 알 수 없다.
        printf '%s' "$resp" | jq -c --arg s "$src" '.result[] | . + {_source: $s}' >> "$TMP_NDJSON"
        chunk_rows=$(( chunk_rows + n ))
      fi

      if [ "$n" -lt "$PAGE_SIZE" ]; then
        break
      fi
      page_offset=$(( page_offset + PAGE_SIZE ))
      sleep "$REQUEST_INTERVAL"
    done

    src_rows=$(( src_rows + chunk_rows ))

    # 조회에 실패한 구간은 보존 한계 공백으로 중복 보고하지 않는다. 원인이 다르다.
    if [ "$chunk_failed" -eq 0 ] && [ "$chunk_rows" -eq 0 ] && [ "$c_start_epoch" -lt "$retention_floor_epoch" ]; then
      # 보존 한계로 이미 지워진 구간. 조회 실패와 구분해서 남긴다.
      err "[$src] 보존 한계로 소실된 구간: $c_start ~ $c_end (무료 플랜 ${SOURCE_RETENTION_HOURS}시간 초과)"
      gap_chunks=$(( gap_chunks + 1 ))
    fi

    sleep "$REQUEST_INTERVAL"
  done

  total_rows=$(( total_rows + src_rows ))
  if [ "$src_rows" -eq 0 ]; then
    warn "[$src] $TARGET_DATE : 0건"
  else
    info "[$src] $TARGET_DATE : ${src_rows}건"
  fi
done

# ---------------------------------------------------------------------------
# 결과 판정 및 저장
# ---------------------------------------------------------------------------
if [ "$failed_chunks" -gt 0 ]; then
  err "$TARGET_DATE : ${failed_chunks}개 구간 조회 실패. 아카이브를 남기지 않는다."
  exit 2
fi

if [ "$total_rows" -eq 0 ]; then
  err "$TARGET_DATE : 응답이 비어 있다(0건). 아카이브를 남기지 않는다."
  exit 3
fi

# 임시 파일에 먼저 쓰고 원자적으로 옮긴다. 중간에 죽어도 반쪽짜리 파일이 남지 않는다.
TMP_GZ="$(mktemp "$ARCHIVE_DIR/.$TARGET_DATE.XXXXXX.gz")"
gzip -9 -c "$TMP_NDJSON" > "$TMP_GZ"
chmod 640 "$TMP_GZ"
mv -f "$TMP_GZ" "$OUT_FILE"

# 위·변조 방지: 체크섬을 함께 남긴다.
( cd "$ARCHIVE_DIR" && sha256sum "$(basename "$OUT_FILE")" > "$(basename "$SUM_FILE")" )
chmod 640 "$SUM_FILE"

info "$TARGET_DATE : 총 ${total_rows}건 저장 -> $OUT_FILE ($(stat -c '%s' "$OUT_FILE") bytes)"

if [ "$gap_chunks" -gt 0 ]; then
  err "$TARGET_DATE : ${gap_chunks}개 구간이 보존 한계로 비어 있다. 이 날짜 아카이브는 불완전하다."
fi

# ---------------------------------------------------------------------------
# RETENTION_DAYS 지난 파일 삭제 — 파일명의 날짜 기준(mtime 이 아니라)
# ---------------------------------------------------------------------------
if [ "$PRUNE" = "1" ]; then
  cutoff_epoch="$(date -d "$RETENTION_DAYS days ago" +%s)"
  pruned=0
  while IFS= read -r f; do
    base="$(basename "$f")"
    fdate="${base%%.json.gz*}"
    date -d "$fdate" >/dev/null 2>&1 || continue
    f_epoch="$(date -d "$fdate" +%s)"
    if [ "$f_epoch" -lt "$cutoff_epoch" ]; then
      rm -f "$f"
      pruned=$(( pruned + 1 ))
    fi
  done < <(find "$ARCHIVE_DIR" -maxdepth 1 -type f \( -name '*.json.gz' -o -name '*.json.gz.sha256' \))
  if [ "$pruned" -gt 0 ]; then
    info "${RETENTION_DAYS}일 경과 파일 ${pruned}개 삭제"
  fi
fi

# 보존 한계로 인한 공백이 있었으면 정상 종료로 치지 않는다.
if [ "$gap_chunks" -gt 0 ]; then
  exit 3
fi

exit 0
