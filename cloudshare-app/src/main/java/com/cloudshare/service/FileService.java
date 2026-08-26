package com.cloudshare.service;

import com.cloudshare.dto.FileResponse;
import com.cloudshare.dto.SharedFileResponse;
import com.cloudshare.exception.ResourceNotFoundException;
import com.cloudshare.exception.UnsupportedMediaTypeException;
import com.cloudshare.exception.VirusDetectedException;
import com.cloudshare.exception.ScanCapacityExceededException;
import com.cloudshare.exception.DownloadCapacityExceededException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import com.cloudshare.model.FileMetadata;
import com.cloudshare.model.FileShare;
import com.cloudshare.model.PermissionType;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.repository.FileShareRepository;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.MediaType;
import org.springframework.http.MediaTypeFactory;
import java.util.List;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;

/**
 * Service orchestrating file uploads, downloads, access authorization, and lifecycle soft-deletion/purges.
 * <p>
 * <b>Architecture & Pipeline Specifications:</b>
 * <ul>
 *   <li><b>Upload Pipeline Order:</b>
 *     <ol>
 *       <li>Disk Buffering (low RAM footprint)</li>
 *       <li>ClamAV Antivirus Daemon Scanning</li>
 *       <li>Apache Tika Magic-Byte MIME Detection & Extension Mismatch Blocking</li>
 *       <li>Polyglot Script Markup Detection</li>
 *       <li>On-the-Fly AES-256-GCM Encryption & SHA-256 Checksum Calculation</li>
 *       <li>Storage Service Upload (MinIO/Local)</li>
 *       <li>FEK Wrapping via KEK</li>
 *       <li>Database Metadata Persistence</li>
 *       <li>Compliance Audit Logging (Fail-Secure stance: audit failure aborts operation)</li>
 *     </ol>
 *   </li>
 *   <li><b>Permission Caching & Bypass Self-Healing:</b> {@link #verifyFileAccess} delegates all
 *   Redis cache mechanics — the {@code cache:permissions:<file_id>} hash, the self-healing
 *   {@code cache:permissions:bypass:<file_id>} marker, cache writes, and eviction — to
 *   {@link PermissionCacheService} (extracted in v2.0.0; also used by {@link ShareService} to
 *   evict on share mutation/revocation, so the two services can no longer drift on this pattern).
 *   This class owns only the orchestration: consult the cache, and on a miss/bypass, query
 *   PostgreSQL directly and warm the cache with the result.</li>
 *   <li><b>BOLA Safety:</b> All database lookups enforce ownership or active share joins via {@link FileRepository#findAccessibleFile}.</li>
 * </ul>
 * </p>
 */
@Service
@Slf4j
public class FileService {

    private final FileRepository fileRepository;
    private final StorageService storageService;
    private final ClamAvService clamAvService;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;
    private final FileShareRepository fileShareRepository;
    private final PermissionCacheService permissionCacheService;
    private final DownloadConcurrencyLimiter downloadConcurrencyLimiter;
    private final int decryptAcquireTimeoutSeconds;

    public FileService(
            FileRepository fileRepository,
            StorageService storageService,
            ClamAvService clamAvService,
            EncryptionService encryptionService,
            AuditLogService auditLogService,
            FileShareRepository fileShareRepository,
            PermissionCacheService permissionCacheService,
            DownloadConcurrencyLimiter downloadConcurrencyLimiter,
            @Value("${storage.decrypt-acquire-timeout-seconds:10}") int decryptAcquireTimeoutSeconds) {
        this.fileRepository = fileRepository;
        this.storageService = storageService;
        this.clamAvService = clamAvService;
        this.encryptionService = encryptionService;
        this.auditLogService = auditLogService;
        this.fileShareRepository = fileShareRepository;
        this.permissionCacheService = permissionCacheService;
        this.downloadConcurrencyLimiter = downloadConcurrencyLimiter;
        this.decryptAcquireTimeoutSeconds = decryptAcquireTimeoutSeconds;
    }

    private final Tika tika = new Tika();

    public FileResponse uploadFile(MultipartFile file, UUID ownerId, String ipAddress) {
        log.info("Processing file upload: user={}, filename={}, size={}", ownerId, file.getOriginalFilename(), file.getSize());

        Path tempFile = null;
        Path encryptedTempFile = null;
        try {
            // 1. Buffer upload to temporary file on disk (low memory JVM usage)
            tempFile = Files.createTempFile("upload-", ".tmp");
            try (InputStream in = file.getInputStream();
                 OutputStream out = Files.newOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }

            // 2. Scan temp file with ClamAV
            try (InputStream scanStream = Files.newInputStream(tempFile)) {
                if (!clamAvService.scan(scanStream)) {
                    auditLogService.log(ownerId, "FILE_UPLOAD_FAILED", null, ipAddress, 
                            "Malware detected in upload: " + file.getOriginalFilename());
                    throw new VirusDetectedException("Malware detected in uploaded file!");
                }
            }

            // 3. MIME magic bytes check
            String detectedMimeType;
            try (InputStream mimeStream = Files.newInputStream(tempFile)) {
                detectedMimeType = tika.detect(mimeStream, file.getOriginalFilename());
            }

            if (isDangerousMimeType(detectedMimeType) || isDisallowedExtension(file.getOriginalFilename())) {
                auditLogService.log(ownerId, "FILE_UPLOAD_FAILED", null, ipAddress, 
                        "Blocked upload of disallowed file type: " + file.getOriginalFilename());
                throw new UnsupportedMediaTypeException("This file type or media extension is not allowed");
            }

            if (!isMimeTypeCompatibleWithExtension(file.getOriginalFilename(), detectedMimeType)) {
                auditLogService.log(ownerId, "FILE_UPLOAD_FAILED", null, ipAddress, 
                        "Blocked upload of spoofed/mismatched file: " + file.getOriginalFilename() + " (detected MIME: " + detectedMimeType + ")");
                throw new UnsupportedMediaTypeException("File content does not match the file extension");
            }

            boolean isImageOrPdf = detectedMimeType.startsWith("image/") || detectedMimeType.equals("application/pdf");
            if (isImageOrPdf && containsDangerousMarkup(tempFile)) {
                auditLogService.log(ownerId, "FILE_UPLOAD_FAILED", null, ipAddress, 
                        "Blocked upload of polyglot file with embedded markup: " + file.getOriginalFilename());
                throw new UnsupportedMediaTypeException("File content contains invalid or dangerous markup");
            }

            // Sanitize filename to prevent HTTP Header Injection in Content-Disposition
            String safeFilename = "file";
            if (file.getOriginalFilename() != null && !file.getOriginalFilename().isEmpty()) {
                safeFilename = java.nio.file.Path.of(file.getOriginalFilename()).getFileName().toString()
                        .replaceAll("[^a-zA-Z0-9._\\- ]", "_");
            }

            // 4. Encrypt plaintext stream on-the-fly and calculate checksum
            SecretKey fek = encryptionService.generateFek();
            byte[] iv = encryptionService.generateIv();
            String storagePath = UUID.randomUUID().toString();

            MessageDigest sha256Digest = MessageDigest.getInstance("SHA-256");
            encryptedTempFile = Files.createTempFile("enc-", ".tmp");

            try (InputStream plaintextIn = Files.newInputStream(tempFile);
                 DigestInputStream digestIn = new DigestInputStream(plaintextIn, sha256Digest);
                 OutputStream encryptedOut = Files.newOutputStream(encryptedTempFile)) {
                
                encryptionService.encryptStream(digestIn, encryptedOut, fek, iv);
            }

            // Calculate plaintext hash
            byte[] checksumBytes = sha256Digest.digest();
            String checksumSha256 = bytesToHex(checksumBytes);

            // 5. Stream encrypted temp file to the pluggable storage backend
            try (InputStream encryptedIn = Files.newInputStream(encryptedTempFile)) {
                storageService.store(storagePath, encryptedIn);
            }

            // 6. Wrap FEK with KEK
            int currentKekVersion = encryptionService.getCurrentKekVersion();
            String encryptedFek = encryptionService.wrapFek(fek, currentKekVersion);
            String ivBase64 = Base64.getEncoder().encodeToString(iv);

            // 7. Persist metadata
            FileMetadata metadata = FileMetadata.builder()
                    .ownerId(ownerId)
                    .storagePath(storagePath)
                    .originalFilename(safeFilename)
                    .fileSizeBytes(file.getSize())
                    .mimeType(detectedMimeType)
                    .checksumSha256(checksumSha256)
                    .encryptedFek(encryptedFek)
                    .ivGcm(ivBase64)
                    .kekVersion(currentKekVersion)
                    .deleted(false)
                    .build();

            FileMetadata savedMetadata = persistMetadata(metadata);

            // 8. Log success audit event
            // Note: Audit failures intentionally abort/fail the operation for compliance (fail-secure stance)
            try {
                auditLogService.log(ownerId, "FILE_UPLOAD", savedMetadata.getId(), ipAddress, 
                        "Successfully uploaded file: " + safeFilename);
            } catch (Exception auditEx) {
                log.error("Audit log failed for file upload. Failing operation for compliance.", auditEx);
                throw new RuntimeException("A server-side compliance issue occurred: audit logging failed.", auditEx);
            }

            return mapToFileResponse(savedMetadata);

        } catch (VirusDetectedException | UnsupportedMediaTypeException | ScanCapacityExceededException e) {
            throw e;
        } catch (Exception e) {
            log.error("Internal error during file upload pipeline execution", e);
            throw new RuntimeException("An error occurred during file upload", e);
        } finally {
            // Guaranteed cleanup of temporary files to prevent disk leak
            cleanupTempFile(tempFile);
            cleanupTempFile(encryptedTempFile);
        }
    }

    public DecryptedFileStream downloadFile(UUID fileId, UUID userId, String ipAddress) {
        log.info("Processing file download request: user={}, fileId={}", userId, fileId);

        // 1. Verify access via cache-aside permissions check
        verifyFileAccess(fileId, userId, PermissionType.READ);

        // 2. Fetch file details with BOLA validation checking ownership or active share
        FileMetadata metadata = fileRepository.findAccessibleFile(fileId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found or access denied"));

        Semaphore limiter = this.downloadConcurrencyLimiter.getSemaphore();
        boolean acquired = false;
        Path decryptedTempFile = null;
        try {
            acquired = limiter.tryAcquire(decryptAcquireTimeoutSeconds, TimeUnit.SECONDS);
            if (!acquired) {
                throw new DownloadCapacityExceededException("Too many downloads in progress. Please try again shortly.");
            }

            // Unwrap FEK with KEK using AESWrap
            SecretKey fek = encryptionService.unwrapFek(metadata.getEncryptedFek(), metadata.getKekVersion());
            byte[] iv = Base64.getDecoder().decode(metadata.getIvGcm());

            // Decrypt fully to temporary file (validates GCM tag before streaming)
            decryptedTempFile = Files.createTempFile("download-", ".tmp");
            try (InputStream encryptedStream = storageService.retrieve(metadata.getStoragePath());
                 OutputStream decryptedOut = Files.newOutputStream(decryptedTempFile)) {
                encryptionService.decryptStreamFully(encryptedStream, decryptedOut, fek, iv);
            }
        } catch (DownloadCapacityExceededException e) {
            throw e;
        } catch (Exception e) {
            if (decryptedTempFile != null) {
                try {
                    Files.deleteIfExists(decryptedTempFile);
                } catch (IOException ioException) {
                    log.warn("Failed to delete temporary download file: {}", decryptedTempFile, ioException);
                }
            }
            log.error("Failed to retrieve or decrypt file for download: {}", fileId, e);
            throw new RuntimeException("Error occurred while processing file download", e);
        } finally {
            if (acquired) {
                limiter.release();
            }
        }

        try {
            InputStream decryptedStream = new DeleteOnCloseInputStream(
                    Files.newInputStream(decryptedTempFile), 
                    decryptedTempFile
            );

            // Log download audit event
            // Note: Audit failures intentionally abort/fail the operation for compliance (fail-secure stance)
            try {
                auditLogService.log(userId, "FILE_DOWNLOAD", fileId, ipAddress, 
                        "Successfully downloaded file: " + metadata.getOriginalFilename());
            } catch (Exception auditEx) {
                log.error("Audit log failed for file download. Failing operation for compliance.", auditEx);
                throw new RuntimeException("A server-side compliance issue occurred: audit logging failed.", auditEx);
            }

            return new DecryptedFileStream(
                    decryptedStream,
                    metadata.getOriginalFilename(),
                    metadata.getMimeType(),
                    metadata.getFileSizeBytes()
            );

        } catch (Exception e) {
            // Clean up the decrypted temp file if streaming setup fails
            if (decryptedTempFile != null) {
                try {
                    Files.deleteIfExists(decryptedTempFile);
                } catch (IOException ioException) {
                    log.warn("Failed to delete temporary download file: {}", decryptedTempFile, ioException);
                }
            }
            log.error("Failed to retrieve or decrypt file for download: {}", fileId, e);
            throw new RuntimeException("Error occurred while processing file download", e);
        }
    }

    public void verifyFileAccess(UUID fileId, UUID userId, PermissionType requiredPermission) {
        java.util.Optional<String> cachedPermission = permissionCacheService.getCachedPermission(fileId, userId);
        if (cachedPermission.isPresent()) {
            if (hasRequiredPermission(cachedPermission.get(), requiredPermission)) {
                return; // Access allowed
            }
            throw new ResourceNotFoundException("File not found or access denied");
        }

        // Cache Miss, Bypass Active, or Redis error: Query DB
        FileMetadata file = fileRepository.findByIdAndDeletedFalse(fileId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found or access denied"));
        
        // Build permission map
        java.util.Map<String, String> permissionMap = new java.util.HashMap<>();
        permissionMap.put(file.getOwnerId().toString(), "OWNER");
        
        // Query active shares
        java.util.List<com.cloudshare.model.FileShare> shares = fileShareRepository.findByFileId(fileId);
        for (com.cloudshare.model.FileShare share : shares) {
            permissionMap.put(share.getSharedWith().getId().toString(), share.getPermissionType().name());
        }
        
        permissionCacheService.cachePermissions(fileId, permissionMap);
        
        String userPermission = permissionMap.get(userId.toString());
        if (userPermission != null && hasRequiredPermission(userPermission, requiredPermission)) {
            return;
        }
        
        throw new ResourceNotFoundException("File not found or access denied");
    }

    private boolean hasRequiredPermission(String actualPermission, PermissionType requiredPermission) {
        if ("OWNER".equals(actualPermission)) {
            return true;
        }
        if (requiredPermission == PermissionType.READ) {
            return "READ".equals(actualPermission) || "WRITE".equals(actualPermission);
        }
        if (requiredPermission == PermissionType.WRITE) {
            return "WRITE".equals(actualPermission);
        }
        return false;
    }

    @Transactional
    public void deleteFile(UUID fileId, UUID userId, String ipAddress) {
        log.info("Processing file deletion request: user={}, fileId={}", userId, fileId);

        // Fetch file details with BOLA validation
        FileMetadata metadata = fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("File not found or access denied"));

        metadata.setDeleted(true);
        fileRepository.save(metadata);

        // Evict permissions cache key in Redis
        permissionCacheService.evict(fileId);

        // Log delete audit event
        // Audit failure rolls back the entire delete transaction (fail-secure).
        // The file remains visible to the user; they can retry the deletion.
        try {
            auditLogService.log(userId, "FILE_DELETE", fileId, ipAddress, 
                    "Successfully soft-deleted file: " + metadata.getOriginalFilename());
        } catch (Exception auditEx) {
            log.error("Audit log failed for file deletion. Failing operation for compliance.", auditEx);
            throw new RuntimeException("A server-side compliance issue occurred: audit logging failed.", auditEx);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void purgeSoftDeletedFile(FileMetadata file) {
        log.info("Permanently purging soft-deleted file: id={}, storagePath={}", file.getId(), file.getStoragePath());
        try {
            // Safe Deletion Order: physical delete first
            storageService.delete(file.getStoragePath());
            
            // Database delete
            fileRepository.delete(file);

            // Audit logging
            auditLogService.log(
                    null, 
                    "FILE_PURGE", 
                    file.getId(), 
                    "system", 
                    "Permanently purged file: " + file.getOriginalFilename() + " (Owner: " + file.getOwnerId() + ")"
            );
        } catch (IOException e) {
            log.error("Failed to delete physical file from storage for file id={}. DB record retained for retry.", file.getId(), e);
            throw new RuntimeException("Storage deletion failed, database delete skipped.", e);
        } catch (Exception e) {
            log.error("Failed to purge database metadata for file id={}", file.getId(), e);
            throw e;
        }
    }

    @Transactional(readOnly = true)
    public Page<FileResponse> listFiles(UUID userId, Pageable pageable) {
        return fileRepository.findByOwnerIdAndDeletedFalse(userId, pageable)
                .map(this::mapToFileResponse);
    }

    @Transactional(readOnly = true)
    public Page<SharedFileResponse> listSharedWithMe(UUID userId, Pageable pageable) {
        return fileShareRepository.findBySharedWithIdAndFileNotDeleted(userId, pageable)
                .map(this::mapToSharedFileResponse);
    }

    private SharedFileResponse mapToSharedFileResponse(FileShare share) {
        FileMetadata metadata = share.getFile();
        return SharedFileResponse.builder()
                .id(metadata.getId())
                .name(metadata.getOriginalFilename())
                .sizeBytes(metadata.getFileSizeBytes())
                .mimeType(metadata.getMimeType())
                .checksum(metadata.getChecksumSha256())
                .uploadedAt(metadata.getCreatedAt())
                .sharedByUsername(share.getSharedBy().getUsername())
                .permissionType(share.getPermissionType())
                .sharedAt(share.getCreatedAt())
                .build();
    }

    // Note: JpaRepository.save() is @Transactional by default
    public FileMetadata persistMetadata(FileMetadata metadata) {
        return fileRepository.save(metadata);
    }

    private FileResponse mapToFileResponse(FileMetadata metadata) {
        return FileResponse.builder()
                .id(metadata.getId())
                .name(metadata.getOriginalFilename())
                .sizeBytes(metadata.getFileSizeBytes())
                .mimeType(metadata.getMimeType())
                .checksum(metadata.getChecksumSha256())
                .uploadedAt(metadata.getCreatedAt())
                .build();
    }

    /**
     * Extensions permitted for upload. An allow-list is used deliberately instead
     * of a deny-list: a deny-list of "dangerous" extensions is inherently
     * incomplete against novel or less-common executable/script extensions
     * (e.g. .cgi, .pyc, .py, .rb, .wasm, .reg, .ps1, .config) that a fixed
     * blocklist won't anticipate. Extend this set per product requirements.
     */
    private static final java.util.Set<String> ALLOWED_EXTENSIONS = java.util.Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "csv", "md",
            "png", "jpg", "jpeg", "gif", "webp", "svg", "zip", "mp4", "mp3", "json", "xml"
    );

    private boolean isDisallowedExtension(String filename) {
        if (filename == null) return true;
        String lower = filename.toLowerCase(java.util.Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0 || dot == lower.length() - 1) {
            return true; // no extension at all — reject rather than guess
        }
        String ext = lower.substring(dot + 1);
        return !ALLOWED_EXTENSIONS.contains(ext);
    }

    private boolean isMimeTypeCompatibleWithExtension(String filename, String detectedMimeType) {
        if (filename == null || detectedMimeType == null) {
            return false;
        }

        List<MediaType> expectedMediaTypes = MediaTypeFactory.getMediaTypes(filename);
        if (expectedMediaTypes.isEmpty()) {
            // Extension is unknown/unmapped, default to allow for flexibility
            return true;
        }

        try {
            MediaType detected = MediaType.parseMediaType(detectedMimeType);
            for (MediaType expected : expectedMediaTypes) {
                if (expected.isCompatibleWith(detected)) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error parsing or checking media type compatibility: detected={}, filename={}", detectedMimeType, filename, e);
            return false;
        }

        return false;
    }

    private boolean containsDangerousMarkup(Path path) {
        try (InputStream in = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            // Maintain the sliding window already lowercased, instead of re-lowercasing
            // the entire (up to 5000 char) window from scratch on every chunk. Window
            // size (5000) and trim threshold (4000) are unchanged, so the boundary-overlap
            // behavior that catches markers split across chunk reads is preserved exactly.
            StringBuilder lowerSb = new StringBuilder();
            while ((read = in.read(buffer)) != -1) {
                String chunk = new String(buffer, 0, read, java.nio.charset.StandardCharsets.US_ASCII);
                lowerSb.append(chunk.toLowerCase(java.util.Locale.ROOT));
                if (lowerSb.length() > 5000) {
                    lowerSb.delete(0, 4000);
                }
                if (lowerSb.indexOf("<script") != -1 || lowerSb.indexOf("<?php") != -1 || lowerSb.indexOf("<html") != -1) {
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Error scanning file content for dangerous markup", e);
        }
        return false;
    }

    private boolean isDangerousMimeType(String mimeType) {
        if (mimeType == null) return true;
        String lower = mimeType.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("application/x-msdownload") || 
               lower.equals("application/x-sh") || 
               lower.equals("application/x-bash") || 
               lower.equals("application/x-msdos-program") ||
               lower.equals("application/javascript") ||
               lower.equals("text/html") ||
               lower.equals("application/x-executable") ||
               lower.equals("application/x-elf") ||
               lower.equals("text/x-python") ||
               lower.equals("application/x-httpd-php");
    }

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private void cleanupTempFile(Path path) {
        if (path != null) {
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                log.warn("Failed to delete temporary upload file: {}", path, e);
            }
        }
    }

    @lombok.Value
    public static class DecryptedFileStream {
        InputStream inputStream;
        String filename;
        String mimeType;
        long size;
    }
}

