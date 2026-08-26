package com.cloudshare.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.cloudshare.model.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditLogService {

    private final JdbcTemplate jdbcTemplate;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void log(UUID userId, String action, UUID fileId, String ipAddress, String details) {
        log.debug("Writing audit log: user={}, action={}, ip={}", userId, action, ipAddress);
        String sql = "INSERT INTO audit_logs (user_id, action, file_id, ip_address, details, created_at) " +
                     "VALUES (?, ?, ?, ?, ?, ?)";
        try {
            jdbcTemplate.update(sql, 
                userId, 
                action, 
                fileId, 
                ipAddress, 
                details, 
                java.sql.Timestamp.from(Instant.now())
            );
        } catch (Exception e) {
            log.error("Failed to write audit log event {} for user {}", action, userId, e);
            throw new RuntimeException("Audit logging failed, transaction rolled back for security compliance", e);
        }
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> getAuditLogs(UUID userId, String action, Pageable pageable) {
        StringBuilder sql = new StringBuilder("SELECT id, user_id, action, file_id, ip_address, details, created_at FROM audit_logs WHERE 1=1");
        List<Object> params = new ArrayList<>();

        if (userId != null) {
            sql.append(" AND user_id = ?");
            params.add(userId);
        }
        if (action != null && !action.isEmpty()) {
            sql.append(" AND action = ?");
            params.add(action);
        }

        // Count query with same filters
        String countSql = "SELECT COUNT(*) FROM (" + sql.toString() + ") as tmp";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        if (total == null) {
            total = 0L;
        }

        // Add sorting and pagination
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(pageable.getPageSize());
        params.add(pageable.getOffset());

        List<AuditLog> logs = jdbcTemplate.query(sql.toString(), (rs, rowNum) -> AuditLog.builder()
                .id(rs.getLong("id"))
                .userId(rs.getObject("user_id") != null ? (UUID) rs.getObject("user_id") : null)
                .action(rs.getString("action"))
                .fileId(rs.getObject("file_id") != null ? (UUID) rs.getObject("file_id") : null)
                .ipAddress(rs.getString("ip_address"))
                .details(rs.getString("details"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .build(),
                params.toArray()
        );

        return new PageImpl<>(logs, pageable, total);
    }
}
