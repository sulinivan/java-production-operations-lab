package com.cloudshare.controller;

import com.cloudshare.dto.*;
import com.cloudshare.security.UserPrincipal;
import com.cloudshare.security.ClientIpResolver;
import com.cloudshare.service.FileService;
import com.cloudshare.service.ShareService;
import java.util.Locale;
import java.util.UUID;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/shares")
@RequiredArgsConstructor
@Slf4j
public class ShareController {

    private final ShareService shareService;
    private final ClientIpResolver clientIpResolver;

    @PostMapping("/internal")
    public ResponseEntity<ApiResponse<InternalShareResponse>> shareFileInternally(
            @Valid @RequestBody InternalShareRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest servletRequest) {

        InternalShareResponse response = shareService.shareFileInternally(request, principal.getId(),
                clientIpResolver.resolveIp(servletRequest));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @PostMapping("/link")
    public ResponseEntity<ApiResponse<PublicLinkResponse>> createPublicLink(
            @Valid @RequestBody CreatePublicLinkRequest request,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest servletRequest) {

        PublicLinkResponse response = shareService.createPublicLink(request, principal.getId(),
                clientIpResolver.resolveIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/link/{shareCode}/info")
    public ResponseEntity<ApiResponse<PublicLinkInfoResponse>> getPublicLinkInfo(
            @PathVariable("shareCode") String shareCode) {
        PublicLinkInfoResponse response = shareService.getPublicLinkInfo(shareCode);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    @GetMapping("/link/{shareCode}/download")
    public ResponseEntity<InputStreamResource> downloadPublicLink(
            @PathVariable("shareCode") String shareCode,
            @RequestHeader(value = "X-Share-Password", required = false) String password,
            HttpServletRequest servletRequest) {

        FileService.DecryptedFileStream fileStream = shareService.downloadPublicLink(shareCode, password,
                clientIpResolver.resolveIp(servletRequest));

        HttpHeaders headers = new HttpHeaders();
        // Force browser download as attachment
        headers.add(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileStream.getFilename() + "\"");

        // Prevent inline scripts or html injection
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

    @DeleteMapping("/internal/{shareId}")
    public ResponseEntity<ApiResponse<Void>> revokeInternalShare(
            @PathVariable("shareId") UUID shareId,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest servletRequest) {

        shareService.revokeInternalShare(shareId, principal.getId(), clientIpResolver.resolveIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.success(null));
    }

    @DeleteMapping("/link/{shareCode}")
    public ResponseEntity<ApiResponse<Void>> revokePublicLink(
            @PathVariable("shareCode") String shareCode,
            @AuthenticationPrincipal UserPrincipal principal,
            HttpServletRequest servletRequest) {

        shareService.revokePublicLink(shareCode, principal.getId(), clientIpResolver.resolveIp(servletRequest));
        return ResponseEntity.ok(ApiResponse.success(null));
    }
}
