package com.cloudshare.controller;

import com.cloudshare.model.AuditLog;
import com.cloudshare.model.Role;
import com.cloudshare.model.User;
import com.cloudshare.repository.UserRepository;
import com.cloudshare.scheduler.AuditPartitionScheduler;
import com.cloudshare.security.CustomUserDetailsService;
import com.cloudshare.security.JwtTokenProvider;
import com.cloudshare.security.ClientIpResolver;
import com.cloudshare.service.AuditLogService;
import com.cloudshare.service.ClamAvService;
import com.cloudshare.service.DownloadConcurrencyLimiter;
import com.cloudshare.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private RateLimiterService rateLimiterService;

    @MockitoBean(name = "securityRedisTemplate")
    private org.springframework.data.redis.core.StringRedisTemplate securityRedisTemplate;

    @MockitoBean
    private AuditPartitionScheduler auditPartitionScheduler;

    @MockitoBean
    private ClamAvService clamAvService;

    @MockitoBean
    private DownloadConcurrencyLimiter downloadConcurrencyLimiter;

    @org.junit.jupiter.api.BeforeEach
    @SuppressWarnings("unchecked")
    void setUpMocks() {
        when(rateLimiterService.isAllowed(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(clientIpResolver.resolveIp(any())).thenReturn("127.0.0.1");

        io.jsonwebtoken.Claims mockClaims = org.mockito.Mockito.mock(io.jsonwebtoken.Claims.class);
        when(mockClaims.getId()).thenReturn("test-jti");
        when(mockClaims.getExpiration()).thenReturn(new java.util.Date(System.currentTimeMillis() + 60000));
        when(mockClaims.get("orig_iat", Long.class)).thenReturn(System.currentTimeMillis());

        when(jwtTokenProvider.parseAndValidateStepUpToken(any(), any())).thenReturn(mockClaims);
        when(jwtTokenProvider.getStepUpSessionMaxSeconds()).thenReturn(300L);

        org.springframework.data.redis.core.ValueOperations<String, String> mockValueOps = org.mockito.Mockito.mock(org.springframework.data.redis.core.ValueOperations.class);
        when(securityRedisTemplate.opsForValue()).thenReturn(mockValueOps);
        when(mockValueOps.setIfAbsent(any(), any(), any(java.time.Duration.class))).thenReturn(true);
    }

    private com.cloudshare.security.UserPrincipal getMockAdminPrincipal() {
        User userEntity = User.builder()
                .id(UUID.randomUUID())
                .username("adminuser")
                .email("adminuser@example.com")
                .passwordHash("hashed")
                .roles(Collections.singleton(new Role(2L, "ROLE_ADMIN")))
                .build();
        return new com.cloudshare.security.UserPrincipal(userEntity);
    }

    @Test
    void listUsers_defaultPageSize() throws Exception {
        User userEntity = User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("testuser@example.com")
                .roles(Collections.singleton(new Role(1L, "ROLE_USER")))
                .createdAt(Instant.now())
                .build();
        Page<User> page = new PageImpl<>(Collections.singletonList(userEntity), PageRequest.of(0, 25), 1);
        
        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(25, pageable.getPageSize());
            return page;
        });

        mockMvc.perform(get("/api/v1/admin/users")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void listUsers_maxPageSizeClamped() throws Exception {
        User userEntity = User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("testuser@example.com")
                .roles(Collections.singleton(new Role(1L, "ROLE_USER")))
                .createdAt(Instant.now())
                .build();
        Page<User> page = new PageImpl<>(Collections.singletonList(userEntity), PageRequest.of(0, 100), 1);

        when(userRepository.findAll(any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals(100, pageable.getPageSize());
            return page;
        });

        mockMvc.perform(get("/api/v1/admin/users")
                        .param("size", "1000")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void getAuditLogs_defaultPageSize() throws Exception {
        AuditLog log = AuditLog.builder()
                .id(1L)
                .action("LOGIN_SUCCESS")
                .ipAddress("127.0.0.1")
                .createdAt(Instant.now())
                .build();
        Page<AuditLog> page = new PageImpl<>(Collections.singletonList(log), PageRequest.of(0, 25), 1);

        when(auditLogService.getAuditLogs(any(), any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            org.junit.jupiter.api.Assertions.assertEquals(25, pageable.getPageSize());
            return page;
        });

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void getAuditLogs_maxPageSizeClamped() throws Exception {
        AuditLog log = AuditLog.builder()
                .id(1L)
                .action("LOGIN_SUCCESS")
                .ipAddress("127.0.0.1")
                .createdAt(Instant.now())
                .build();
        Page<AuditLog> page = new PageImpl<>(Collections.singletonList(log), PageRequest.of(0, 100), 1);

        when(auditLogService.getAuditLogs(any(), any(), any(Pageable.class))).thenAnswer(invocation -> {
            Pageable pageable = invocation.getArgument(2);
            org.junit.jupiter.api.Assertions.assertEquals(100, pageable.getPageSize());
            return page;
        });

        mockMvc.perform(get("/api/v1/admin/audit-logs")
                        .param("size", "1000")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void triggerPartitionMaintenance_success() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/audit-logs/partitions")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Audit log partition maintenance executed successfully."));

        verify(auditPartitionScheduler).maintainPartitions();
    }

    @Test
    void updateClamavConcurrencyLimit_success() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/clamav/limit")
                        .param("limit", "5")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("ClamAV scan concurrency limit updated to 5 successfully."));

        verify(clamAvService).setMaxConcurrentScans(5);
    }

    @Test
    void updateDownloadConcurrencyLimit_success() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/downloads/limit")
                        .param("limit", "15")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal()))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("Download concurrency limit updated to 15 successfully."));

        verify(downloadConcurrencyLimiter).setMaxConcurrentDownloads(15);
    }

    // Confirms Spring's native (Spring Framework 6.1+) method-validation path fires for
    // @RequestParam @Min/@Max — no class-level @Validated is used here, so a failure
    // raises HandlerMethodValidationException, which GlobalExceptionHandler maps to the
    // same VALIDATION_FAILED shape used for @Valid @RequestBody failures.
    @Test
    void updateClamavConcurrencyLimit_belowMin_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/clamav/limit")
                        .param("limit", "0")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal()))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verify(clamAvService, org.mockito.Mockito.never()).setMaxConcurrentScans(org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void updateDownloadConcurrencyLimit_aboveMax_returns400() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/admin/downloads/limit")
                        .param("limit", "201")
                        .header("X-StepUp-Token", "valid-token")
                        .with(user(getMockAdminPrincipal()))
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        verify(downloadConcurrencyLimiter, org.mockito.Mockito.never()).setMaxConcurrentDownloads(org.mockito.ArgumentMatchers.anyInt());
    }
}
