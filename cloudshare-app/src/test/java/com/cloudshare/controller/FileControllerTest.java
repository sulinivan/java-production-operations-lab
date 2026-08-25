package com.cloudshare.controller;

import com.cloudshare.dto.FileResponse;
import com.cloudshare.security.CustomUserDetailsService;
import com.cloudshare.security.JwtTokenProvider;
import com.cloudshare.security.ClientIpResolver;
import com.cloudshare.service.FileService;
import com.cloudshare.service.RateLimiterService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.springframework.context.annotation.Import;
import com.cloudshare.config.SecurityConfig;

@WebMvcTest(FileController.class)
@Import(SecurityConfig.class)
class FileControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FileService fileService;

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

    private com.cloudshare.security.UserPrincipal getMockPrincipal() {
        com.cloudshare.model.User userEntity = com.cloudshare.model.User.builder()
                .id(UUID.randomUUID())
                .username("testuser")
                .email("testuser@example.com")
                .passwordHash("hashed")
                .roles(Collections.singleton(new com.cloudshare.model.Role(1L, "ROLE_USER")))
                .build();
        return new com.cloudshare.security.UserPrincipal(userEntity);
    }

    @Test
    void uploadFile_success() throws Exception {
        MockMultipartFile mockFile = new MockMultipartFile(
                "file",
                "test.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "some content".getBytes()
        );

        FileResponse responseDto = FileResponse.builder()
                .id(UUID.randomUUID())
                .name("test.txt")
                .sizeBytes(12L)
                .mimeType(MediaType.TEXT_PLAIN_VALUE)
                .checksum("sha256")
                .uploadedAt(Instant.now())
                .build();

        com.cloudshare.security.UserPrincipal principal = getMockPrincipal();
        when(fileService.uploadFile(any(), eq(principal.getId()), any())).thenReturn(responseDto);

        mockMvc.perform(multipart("/api/v1/files/upload")
                        .file(mockFile)
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("test.txt"));
    }

    @Test
    void listFiles_success() throws Exception {
        FileResponse responseDto = FileResponse.builder()
                .id(UUID.randomUUID())
                .name("test.txt")
                .sizeBytes(12L)
                .mimeType(MediaType.TEXT_PLAIN_VALUE)
                .checksum("sha256")
                .uploadedAt(Instant.now())
                .build();

        PageImpl<FileResponse> page = new PageImpl<>(Collections.singletonList(responseDto), PageRequest.of(0, 10), 1);
        com.cloudshare.security.UserPrincipal principal = getMockPrincipal();
        when(fileService.listFiles(eq(principal.getId()), any())).thenReturn(page);

        mockMvc.perform(get("/api/v1/files")
                        .param("page", "0")
                        .param("size", "10")
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].name").value("test.txt"));
    }

    @Test
    void downloadFile_success() throws Exception {
        UUID fileId = UUID.randomUUID();
        ByteArrayInputStream mockStream = new ByteArrayInputStream("plaintext content".getBytes());
        FileService.DecryptedFileStream fileStream = new FileService.DecryptedFileStream(
                mockStream, "test.txt", MediaType.TEXT_PLAIN_VALUE, 17L
        );

        com.cloudshare.security.UserPrincipal principal = getMockPrincipal();
        when(fileService.downloadFile(eq(fileId), eq(principal.getId()), any())).thenReturn(fileStream);

        mockMvc.perform(get("/api/v1/files/{id}/download", fileId)
                        .with(user(principal)))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"test.txt\""))
                .andExpect(content().contentType(MediaType.TEXT_PLAIN))
                .andExpect(content().string("plaintext content"));
    }

    @Test
    void deleteFile_success() throws Exception {
        UUID fileId = UUID.randomUUID();
        com.cloudshare.security.UserPrincipal principal = getMockPrincipal();
        doNothing().when(fileService).deleteFile(eq(fileId), eq(principal.getId()), any());

        mockMvc.perform(delete("/api/v1/files/{id}", fileId)
                        .with(csrf())
                        .with(user(principal)))
                .andExpect(status().isNoContent());
    }

    @Test
    void endpoints_requireAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/files"))
                .andExpect(status().isUnauthorized());
    }
}
