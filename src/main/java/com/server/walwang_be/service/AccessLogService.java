package com.server.walwang_be.service;

import com.server.walwang_be.dto.AdminAccessLogRow;
import com.server.walwang_be.entity.AccessAction;
import com.server.walwang_be.entity.AccessLog;
import com.server.walwang_be.repo.AccessLogRepository;
import com.server.walwang_be.security.ClientIpResolver;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 관리자 페이지 접속기록.
 *
 * 기록은 동기로, 자기 트랜잭션에서 커밋한다.
 *   - @Async를 쓰지 않는다. 비동기면 기록이 실패하거나 서버가 내려갈 때 조용히 사라지는데,
 *     그 사이 화면은 이미 나가 있다. 기록이 남아야 화면을 보여준다는 순서가 깨진다.
 *   - REQUIRES_NEW: 부르는 쪽이 읽기 전용 트랜잭션 안이어도 INSERT가 막히지 않고,
 *     뒤이은 조회가 실패해 롤백돼도 "조회를 시도했다"는 기록은 남는다.
 *
 * 개인정보를 바꾸는 작업은 이 순서를 쓸 수 없다. 기록이 먼저 커밋되면 뒤이은 변경이 실패해도
 * 기록만 남아 "했다고 적혀 있는데 안 된" 상태가 된다. 그런 작업은
 * recordInCurrentTransaction 으로 같은 트랜잭션에 담는다.
 *
 * 두 메서드 모두 반드시 다른 빈(컨트롤러, 로그인 핸들러, 해지 서비스)에서 부른다.
 * 같은 빈 안에서 부르면 프록시를 거치지 않아 @Transactional이 통째로 무시된다(StampGenerator 주석 참고).
 */
@Service
@RequiredArgsConstructor
public class AccessLogService {

    static final int PAGE_SIZE = 50;

    private final AccessLogRepository accessLogRepository;

    /** 조회용. 부르는 쪽과 상관없이 자기 트랜잭션에서 먼저 커밋한다. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String operator, String ip, AccessAction action, UUID targetUserId, String detail) {
        accessLogRepository.save(row(operator, ip, action, targetUserId, detail));
    }

    /**
     * 개인정보를 바꾸는 작업용. 부르는 쪽 트랜잭션에 그대로 올라탄다 —
     * 작업이 롤백되면 이 기록도 함께 사라지고, 기록이 실패하면 작업도 함께 무효가 된다.
     *
     * MANDATORY: 트랜잭션 없이 부르면 예외로 끝난다. REQUIRED로 두면 트랜잭션을 잊은 호출부에서
     * 조용히 따로 커밋돼 원자성이 깨지는데, 그건 아무도 알아차리지 못한다.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordInCurrentTransaction(String operator, String ip, AccessAction action,
                                           UUID targetUserId, String detail) {
        accessLogRepository.save(row(operator, ip, action, targetUserId, detail));
    }

    private static AccessLog row(String operator, String ip, AccessAction action,
                                 UUID targetUserId, String detail) {
        return AccessLog.builder()
                .operator(truncate(operator == null ? "" : operator, AccessLog.OPERATOR_MAX))
                .ip(truncate(ip == null ? ClientIpResolver.UNKNOWN : ip, AccessLog.IP_MAX))
                .targetUserId(targetUserId)
                .action(action)
                .detail(detail == null ? null : truncate(detail, AccessLog.DETAIL_MAX))
                .build();
    }

    /**
     * 접속기록 열람. 셋 다 비어 있으면 전체를 최신순으로 본다.
     *
     * 같은 시각이 겹쳐도 순서가 흔들리지 않게 log_id로 한 번 더 줄 세운다.
     *
     * @param operator     빈 문자열이거나 null이면 계정으로 거르지 않는다
     * @param targetUserId null이면 정보주체로 거르지 않는다
     * @param action       null이면 수행업무로 거르지 않는다
     */
    @Transactional(readOnly = true)
    public Page<AdminAccessLogRow> find(String operator, UUID targetUserId, AccessAction action, int page) {
        Pageable pageable = PageRequest.of(page, PAGE_SIZE,
                Sort.by(Sort.Order.desc("accessedAt"), Sort.Order.desc("logId")));

        Page<AccessLog> logs = accessLogRepository.findAll(
                filter(operator, targetUserId, action), pageable);
        return logs.map(log -> new AdminAccessLogRow(
                log.getLogId(),
                AdminViewFormats.dateTime(log.getAccessedAt()),
                log.getOperator(),
                log.getIp(),
                log.getTargetUserId() != null,
                log.getTargetUserId() == null ? "" : log.getTargetUserId().toString(),
                log.getAction().name(),
                log.getAction().label(),
                log.getDetail() == null ? "" : log.getDetail()));
    }

    /**
     * 채워진 조건만 and로 엮는다.
     *
     * 조건이 하나도 없으면 where 절이 붙지 않아 전체 조회가 된다 —
     * conjunction()은 항상 참인 조건이고, Specification은 그 경우 SQL에서 생략된다.
     */
    private static Specification<AccessLog> filter(String operator, UUID targetUserId, AccessAction action) {
        return (root, query, builder) -> {
            List<Predicate> conditions = new ArrayList<>(3);
            if (operator != null && !operator.isEmpty()) {
                conditions.add(builder.equal(root.get("operator"), operator));
            }
            if (targetUserId != null) {
                conditions.add(builder.equal(root.get("targetUserId"), targetUserId));
            }
            if (action != null) {
                conditions.add(builder.equal(root.get("action"), action));
            }
            return conditions.isEmpty()
                    ? builder.conjunction()
                    : builder.and(conditions.toArray(new Predicate[0]));
        };
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
