package com.cloudshare.controller;

import com.cloudshare.dto.AdminUserResponse;
import com.cloudshare.dto.ApiResponse;
import com.cloudshare.model.AuditLog;
import com.cloudshare.repository.UserRepository;
import com.cloudshare.service.AuditLogService;
import com.cloudshare.scheduler.AuditPartitionScheduler;
import com.cloudshare.service.ClamAvService;
import com.cloudshare.service.DownloadConcurrencyLimiter;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminController {

    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final AuditPartitionScheduler auditPartitionScheduler;
    private final ClamAvService clamAvService;
    private final DownloadConcurrencyLimiter downloadConcurrencyLimiter;

    // Bounds mirror DownloadConcurrencyLimiter.MAX_ALLOWED_CONCURRENCY /
    // ClamAvService.MAX_ALLOWED_CONCURRENCY (both 1-200). Validated here too so a bad
    // value gets a clean 400 with field-level detail instead of only failing one layer
    // down; the service-layer check remains as the authoritative backstop.
    @PostMapping("/clamav/limit")
    public ResponseEntity<ApiResponse<String>> updateClamavConcurrencyLimit(
            @RequestParam @Min(1) @Max(200) int limit) {
        clamAvService.setMaxConcurrentScans(limit);
        return ResponseEntity.ok(ApiResponse.success("ClamAV scan concurrency limit updated to " + limit + " successfully."));
    }

    @PostMapping("/downloads/limit")
    public ResponseEntity<ApiResponse<String>> updateDownloadConcurrencyLimit(
            @RequestParam @Min(1) @Max(200) int limit) {
        downloadConcurrencyLimiter.setMaxConcurrentDownloads(limit);
        return ResponseEntity.ok(ApiResponse.success("Download concurrency limit updated to " + limit + " successfully."));
    }

    @PostMapping("/audit-logs/partitions")
    public ResponseEntity<ApiResponse<String>> triggerPartitionMaintenance() {
        auditPartitionScheduler.maintainPartitions();
        return ResponseEntity.ok(ApiResponse.success("Audit log partition maintenance executed successfully."));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponse<Page<AdminUserResponse>>> listUsers(
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AdminUserResponse> response = userRepository.findAll(pageable)
                .map(user -> AdminUserResponse.builder()
                        .id(user.getId())
                        .username(user.getUsername())
                        .email(user.getEmail())
                        .roles(user.getRoles().stream().map(role -> role.getName()).collect(Collectors.toList()))
                        .mfaEnabled(user.isMfaEnabled())
                        .createdAt(user.getCreatedAt())
                        .build());
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/audit-logs")
    public ResponseEntity<ApiResponse<Page<AuditLog>>> getAuditLogs(
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String action,
            @PageableDefault(size = 25, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        Page<AuditLog> logs = auditLogService.getAuditLogs(userId, action, pageable);
        return ResponseEntity.ok(ApiResponse.success(logs));
    }
}
