package com.cloudshare.controller;

import com.cloudshare.dto.*;
import com.cloudshare.security.CustomUserDetailsService;
import com.cloudshare.security.JwtTokenProvider;
import com.cloudshare.security.UserPrincipal;
import com.cloudshare.security.ClientIpResolver;
import com.cloudshare.service.FileService;
import com.cloudshare.service.RateLimiterService;
import com.cloudshare.service.ShareService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import com.cloudshare.config.SecurityConfig;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ShareController.class)
@Import(SecurityConfig.class)
class ShareControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ShareService shareService;

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

    @org.junit.jupiter.api.BeforeEach
    void setUpRateLimiter() {
        when(rateLimiterService.isAllowed(any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(true);
        when(clientIpResolver.resolveIp(any())).thenReturn("127.0.0.1");
    }

    private UserPrincipal getMockPrincipal() {
        com.cloudshare.model.User userEntity = com.cloudshare.model.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("testuser@example.com")
                .passwordHash("hashed")
                .roles(Collections.singleton(new com.cloudshare.model.Role(1L, "ROLE_USER")))
                .build();
        return new UserPrincipal(userEntity);
    }

    @Test
    void shareFileInternally_success() throws Exception {
        InternalShareRequest request = InternalShareRequest.builder()
                .fileId(UUID.randomUUID())
                .targetUsernameOrEmail("janedoe@example.com")
                .permissionType("READ")
                .build();

        InternalShareResponse response = InternalShareResponse.builder()
                .shareId(UUID.randomUUID())
                .fileId(request.getFileId())
                .sharedWith("janedoe@example.com")
                .permission("READ")
                .build();

        UserPrincipal principal = getMockPrincipal();
        when(shareService.shareFileInternally(any(InternalShareRequest.class), eq(principal.getId()), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/shares/internal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.sharedWith").value("janedoe@example.com"))
                .andExpect(jsonPath("$.data.permission").value("READ"));
    }

    @Test
    void createPublicLink_success() throws Exception {
        CreatePublicLinkRequest request = CreatePublicLinkRequest.builder()
                .fileId(UUID.randomUUID())
                .expiresInSeconds(3600L)
                .password("LinkPass")
                .downloadLimit(5)
                .build();

        PublicLinkResponse response = PublicLinkResponse.builder()
                .shareCode("XYZ12345")
                .shareUrl("https://cloudshare.app/share/XYZ12345")
                .expiresAt(Instant.now().plusSeconds(3600))
                .passwordProtected(true)
                .build();

        UserPrincipal principal = getMockPrincipal();
        when(shareService.createPublicLink(any(CreatePublicLinkRequest.class), eq(principal.getId()), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/shares/link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request))
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.shareCode").value("XYZ12345"))
                .andExpect(jsonPath("$.data.passwordProtected").value(true));
    }

    @Test
    void downloadPublicLink_success() throws Exception {
        String shareCode = "XYZ12345";
        FileService.DecryptedFileStream fileStream = new FileService.DecryptedFileStream(
                new ByteArrayInputStream("plaintext".getBytes()),
                "doc.txt",
                "text/plain",
                9L
        );

        when(shareService.downloadPublicLink(eq(shareCode), eq("LinkPass"), any())).thenReturn(fileStream);

        mockMvc.perform(get("/api/v1/shares/link/" + shareCode + "/download")
                        .header("X-Share-Password", "LinkPass")
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"doc.txt\""))
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().bytes("plaintext".getBytes()));
    }

    @Test
    void revokeInternalShare_success() throws Exception {
        UUID shareId = UUID.randomUUID();
        UserPrincipal principal = getMockPrincipal();

        mockMvc.perform(delete("/api/v1/shares/internal/" + shareId)
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(shareService).revokeInternalShare(eq(shareId), eq(principal.getId()), any());
    }

    @Test
    void revokePublicLink_success() throws Exception {
        String shareCode = "XYZ12345";
        UserPrincipal principal = getMockPrincipal();

        mockMvc.perform(delete("/api/v1/shares/link/" + shareCode)
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(shareService).revokePublicLink(eq(shareCode), eq(principal.getId()), any());
    }
}
