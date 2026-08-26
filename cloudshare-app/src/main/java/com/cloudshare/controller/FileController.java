package com.cloudshare.controller;

import com.cloudshare.dto.ApiResponse;
import com.cloudshare.dto.FileResponse;
import com.cloudshare.dto.SharedFileResponse;
import com.cloudshare.security.UserPrincipal;
import com.cloudshare.security.ClientIpResolver;
import com.cloudshare.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
@Slf4j
public class FileController {

    private final FileService fileService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<FileResponse>> uploadFile(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {
        
        FileResponse response = fileService.uploadFile(file, principal.getId(), clientIpResolver.resolveIp(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<Page<FileResponse>>> listFiles(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {
        
        Page<FileResponse> files = fileService.listFiles(principal.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(files));
    }

    @GetMapping("/shared-with-me")
    public ResponseEntity<ApiResponse<Page<SharedFileResponse>>> listSharedWithMe(
            @AuthenticationPrincipal UserPrincipal principal,
            @PageableDefault(size = 10, sort = "createdAt") Pageable pageable) {
        
        Page<SharedFileResponse> files = fileService.listSharedWithMe(principal.getId(), pageable);
        return ResponseEntity.ok(ApiResponse.success(files));
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> downloadFile(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {
        
        FileService.DecryptedFileStream fileStream = fileService.downloadFile(id, principal.getId(), clientIpResolver.resolveIp(request));
        
        HttpHeaders headers = new HttpHeaders();
        // Force the browser to download the file as attachment instead of rendering inline
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileStream.getFilename() + "\"");
        
        // Prevent inline scripts or HTML injection by setting content type to application/octet-stream if text/html
        String contentType = fileStream.getMimeType();
        if (contentType == null || contentType.toLowerCase(Locale.ROOT).contains("text/html")) {
            contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
        }
        
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.parseMediaType(contentType))
                .contentLength(fileStream.getSize())
                .body(new InputStreamResource(fileStream.getInputStream()));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFile(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest request) {
        
        fileService.deleteFile(id, principal.getId(), clientIpResolver.resolveIp(request));
        return ResponseEntity.noContent().build();
    }}
