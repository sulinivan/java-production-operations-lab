package com.cloudshare.service;

import com.cloudshare.dto.FileResponse;
import com.cloudshare.exception.ResourceNotFoundException;
import com.cloudshare.exception.UnsupportedMediaTypeException;
import com.cloudshare.exception.VirusDetectedException;
import com.cloudshare.model.FileMetadata;
import com.cloudshare.repository.FileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import org.springframework.test.util.ReflectionTestUtils;
import com.cloudshare.repository.FileShareRepository;

@ExtendWith(MockitoExtension.class)
class FileServiceTest {

    @Mock
    private FileRepository fileRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private ClamAvService clamAvService;

    @Mock
    private EncryptionService encryptionService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private FileShareRepository fileShareRepository;

    @Mock
    private PermissionCacheService permissionCacheService;

    @Mock
    private DownloadConcurrencyLimiter downloadConcurrencyLimiter;

    private FileService fileService;

    @BeforeEach
    void setUp() {
        lenient().when(downloadConcurrencyLimiter.getSemaphore()).thenReturn(new java.util.concurrent.Semaphore(20, true));
        fileService = new FileService(
            fileRepository, 
            storageService, 
            clamAvService, 
            encryptionService, 
            auditLogService,
            fileShareRepository,
            permissionCacheService,
            downloadConcurrencyLimiter,
            10
        );
    }

    @Test
    void uploadFile_success() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "clean_report.pdf",
                "application/pdf",
                "%PDF-1.4\nPlaintext report content".getBytes(StandardCharsets.UTF_8)
        );

        // Stub antivirus scanner as clean
        when(clamAvService.scan(any(InputStream.class))).thenReturn(true);

        // Stub encryption services
        SecretKey mockFek = new SecretKeySpec(new byte[32], "AES");
        byte[] mockIv = new byte[12];
        when(encryptionService.generateFek()).thenReturn(mockFek);
        when(encryptionService.generateIv()).thenReturn(mockIv);
        when(encryptionService.getCurrentKekVersion()).thenReturn(1);
        when(encryptionService.wrapFek(mockFek, 1)).thenReturn("wrapped_fek_base64");

        // Stub repository save
        FileMetadata savedMetadata = FileMetadata.builder()
                .id(UUID.randomUUID())
                .ownerId(ownerId)
                .originalFilename("clean_report.pdf")
                .fileSizeBytes((long) file.getBytes().length)
                .mimeType("application/pdf")
                .checksumSha256("plaintext_checksum")
                .build();
        when(fileRepository.save(any(FileMetadata.class))).thenReturn(savedMetadata);

        FileResponse response = fileService.uploadFile(file, ownerId, ipAddress);

        assertNotNull(response);
        assertEquals("clean_report.pdf", response.getName());
        assertEquals("application/pdf", response.getMimeType());

        // Verify storage write and audit logging occurred
        verify(storageService).store(any(String.class), any(InputStream.class));
        verify(auditLogService).log(eq(ownerId), eq("FILE_UPLOAD"), eq(savedMetadata.getId()), eq(ipAddress), any(String.class));
    }

    @Test
    void uploadFile_virusDetected_throwsException() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "eicar_virus.txt",
                "text/plain",
                "dummy infected file content".getBytes(StandardCharsets.UTF_8)
        );

        // Stub antivirus scanner as infected
        when(clamAvService.scan(any(InputStream.class))).thenReturn(false);

        assertThrows(VirusDetectedException.class, () -> {
            fileService.uploadFile(file, ownerId, ipAddress);
        });

        // Verify audit logging reflects failure and storage write is skipped
        verify(auditLogService).log(eq(ownerId), eq("FILE_UPLOAD_FAILED"), any(), eq(ipAddress), any(String.class));
        verify(storageService, never()).store(any(String.class), any(InputStream.class));
    }

    @Test
    void uploadFile_dangerousExtension_throwsException() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "malicious_script.sh",
                "application/x-sh",
                "rm -rf /".getBytes(StandardCharsets.UTF_8)
        );

        // Stub antivirus scanner as clean
        when(clamAvService.scan(any(InputStream.class))).thenReturn(true);

        assertThrows(UnsupportedMediaTypeException.class, () -> {
            fileService.uploadFile(file, ownerId, ipAddress);
        });

        // Verify audit logging reflects type block and storage write is skipped
        verify(auditLogService).log(eq(ownerId), eq("FILE_UPLOAD_FAILED"), any(), eq(ipAddress), any(String.class));
        verify(storageService, never()).store(any(String.class), any(InputStream.class));
    }

    @Test
    void downloadFile_success() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .ownerId(userId)
                .storagePath("storage_uuid")
                .originalFilename("sensitive_report.pdf")
                .fileSizeBytes(1234L)
                .mimeType("application/pdf")
                .encryptedFek("wrapped_fek")
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .deleted(false)
                .build();

        // Stub cache-aside lookup as OWNER
        when(permissionCacheService.getCachedPermission(fileId, userId)).thenReturn(Optional.of("OWNER"));

        // Stub repository to return file
        when(fileRepository.findAccessibleFile(fileId, userId)).thenReturn(Optional.of(metadata));

        // Stub decryption keys
        SecretKey mockFek = new SecretKeySpec(new byte[32], "AES");
        when(encryptionService.unwrapFek("wrapped_fek", 1)).thenReturn(mockFek);
        
        ByteArrayInputStream mockEncryptedStream = new ByteArrayInputStream("Encrypted Data".getBytes(StandardCharsets.UTF_8));
        when(storageService.retrieve("storage_uuid")).thenReturn(mockEncryptedStream);

        FileService.DecryptedFileStream result = fileService.downloadFile(fileId, userId, ipAddress);

        assertNotNull(result);
        assertEquals("sensitive_report.pdf", result.getFilename());
        assertEquals("application/pdf", result.getMimeType());
        assertEquals(1234L, result.getSize());

        // Verify decryption and audit logging
        verify(encryptionService).decryptStreamFully(eq(mockEncryptedStream), any(OutputStream.class), eq(mockFek), any(byte[].class));
        verify(auditLogService).log(eq(userId), eq("FILE_DOWNLOAD"), eq(fileId), eq(ipAddress), any(String.class));
    }

    @Test
    void downloadFile_notFoundOrBOLA_throwsException() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        // Stub cache-aside miss
        when(permissionCacheService.getCachedPermission(fileId, userId)).thenReturn(Optional.empty());

        // Stub database lookup fail
        when(fileRepository.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            fileService.downloadFile(fileId, userId, ipAddress);
        });

        // Ensure key processing & storage retrieval never run
        verifyNoInteractions(encryptionService);
        verifyNoInteractions(storageService);
    }

    @Test
    void deleteFile_success() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .ownerId(userId)
                .originalFilename("temp.txt")
                .deleted(false)
                .build();

        when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, userId)).thenReturn(Optional.of(metadata));

        fileService.deleteFile(fileId, userId, ipAddress);

        // Verify soft-delete update is persisted
        assertTrue(metadata.isDeleted());
        verify(fileRepository).save(metadata);

        // Verify cache eviction
        verify(permissionCacheService).evict(fileId);

        // Verify audit logging
        verify(auditLogService).log(eq(userId), eq("FILE_DELETE"), eq(fileId), eq(ipAddress), any(String.class));
    }

    @Test
    void downloadFile_cacheHitInsufficientPermission_throwsException() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        // Stub cache-aside lookup as NONE (insufficient)
        when(permissionCacheService.getCachedPermission(fileId, userId)).thenReturn(Optional.of("NONE"));

        assertThrows(ResourceNotFoundException.class, () -> {
            fileService.downloadFile(fileId, userId, ipAddress);
        });

        // Ensure key processing & storage retrieval never run
        verifyNoInteractions(encryptionService);
        verifyNoInteractions(storageService);
    }

    @Test
    void downloadFile_cacheKeyExistsButUserMissing_throwsException() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        // Stub cache key exists but user entry is null: PermissionCacheService throws directly
        // (fast-path deny), matching its documented contract.
        when(permissionCacheService.getCachedPermission(fileId, userId))
                .thenThrow(new ResourceNotFoundException("File not found or access denied"));

        assertThrows(ResourceNotFoundException.class, () -> {
            fileService.downloadFile(fileId, userId, ipAddress);
        });

        // Ensure key processing & storage retrieval never run
        verifyNoInteractions(encryptionService);
        verifyNoInteractions(storageService);
    }

    @Test
    void listSharedWithMe_success() {
        UUID userId = UUID.randomUUID();
        org.springframework.data.domain.Pageable pageable = org.springframework.data.domain.PageRequest.of(0, 10);

        com.cloudshare.model.User owner = com.cloudshare.model.User.builder()
                .id(UUID.randomUUID())
                .username("sharerUser")
                .build();

        FileMetadata file = FileMetadata.builder()
                .id(UUID.randomUUID())
                .originalFilename("shared.pdf")
                .fileSizeBytes(5000L)
                .mimeType("application/pdf")
                .checksumSha256("sha256checksum")
                .createdAt(java.time.Instant.now())
                .build();

        com.cloudshare.model.FileShare share = com.cloudshare.model.FileShare.builder()
                .id(UUID.randomUUID())
                .file(file)
                .sharedBy(owner)
                .permissionType(com.cloudshare.model.PermissionType.READ)
                .createdAt(java.time.Instant.now())
                .build();

        org.springframework.data.domain.Page<com.cloudshare.model.FileShare> sharePage = 
                new org.springframework.data.domain.PageImpl<>(java.util.List.of(share), pageable, 1);

        when(fileShareRepository.findBySharedWithIdAndFileNotDeleted(userId, pageable)).thenReturn(sharePage);

        org.springframework.data.domain.Page<com.cloudshare.dto.SharedFileResponse> result = 
                fileService.listSharedWithMe(userId, pageable);

        assertNotNull(result);
        assertEquals(1, result.getTotalElements());
        com.cloudshare.dto.SharedFileResponse response = result.getContent().get(0);
        assertEquals(file.getId(), response.getId());
        assertEquals(file.getOriginalFilename(), response.getName());
        assertEquals(file.getFileSizeBytes(), response.getSizeBytes());
        assertEquals("sharerUser", response.getSharedByUsername());
        assertEquals(com.cloudshare.model.PermissionType.READ, response.getPermissionType());
    }

    // Fine-grained bypass-marker self-healing mechanics (the actual Redis calls) are now
    // internal to PermissionCacheService and covered by PermissionCacheServiceTest. From
    // FileService's perspective, a bypass-active outcome and a genuine cache miss are
    // indistinguishable — both surface as Optional.empty() from getCachedPermission — so these
    // tests only assert the orchestration: DB fallback occurs, and cachePermissions is only
    // called when the DB fetch actually succeeds.
    @Test
    void downloadFile_cacheReturnsEmpty_dbLookupFails_throwsException_noCacheWrite() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        when(permissionCacheService.getCachedPermission(fileId, userId)).thenReturn(Optional.empty());
        when(fileRepository.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            fileService.downloadFile(fileId, userId, ipAddress);
        });

        // DB fetch failed before a permission map could be built, so there is nothing to cache.
        verify(permissionCacheService, never()).cachePermissions(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void downloadFile_cacheReturnsEmpty_dbLookupSucceeds_cachesResultAndSucceeds() throws Exception {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .ownerId(userId)
                .storagePath("storage_uuid")
                .originalFilename("sensitive_report.pdf")
                .fileSizeBytes(1234L)
                .mimeType("application/pdf")
                .encryptedFek("wrapped_fek")
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .deleted(false)
                .build();

        when(permissionCacheService.getCachedPermission(fileId, userId)).thenReturn(Optional.empty());
        when(fileRepository.findByIdAndDeletedFalse(fileId)).thenReturn(Optional.of(metadata));
        when(fileRepository.findAccessibleFile(fileId, userId)).thenReturn(Optional.of(metadata));

        SecretKey mockFek = new SecretKeySpec(new byte[32], "AES");
        when(encryptionService.unwrapFek("wrapped_fek", 1)).thenReturn(mockFek);

        ByteArrayInputStream mockEncryptedStream = new ByteArrayInputStream("Encrypted Data".getBytes(StandardCharsets.UTF_8));
        when(storageService.retrieve("storage_uuid")).thenReturn(mockEncryptedStream);

        FileService.DecryptedFileStream result = fileService.downloadFile(fileId, userId, ipAddress);

        assertNotNull(result);
        assertEquals("sensitive_report.pdf", result.getFilename());

        // Verify the DB-resolved permission map was handed to the cache service to warm the cache.
        verify(permissionCacheService).cachePermissions(eq(fileId), any(java.util.Map.class));
    }

    @Test
    void deleteFile_evictionDelegatesToPermissionCacheService() {
        UUID fileId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";

        FileMetadata metadata = FileMetadata.builder()
                .id(fileId)
                .ownerId(userId)
                .originalFilename("temp.txt")
                .deleted(false)
                .build();

        when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, userId)).thenReturn(Optional.of(metadata));

        fileService.deleteFile(fileId, userId, ipAddress);

        assertTrue(metadata.isDeleted());
        verify(fileRepository).save(metadata);
        // The bypass-marker-on-failure behavior is PermissionCacheService's own concern now
        // (covered by PermissionCacheServiceTest); FileService just needs to delegate.
        verify(permissionCacheService).evict(fileId);
        verify(auditLogService).log(eq(userId), eq("FILE_DELETE"), eq(fileId), eq(ipAddress), any(String.class));
    }

    @Test
    void uploadFile_dangerousExtensionPhp_throwsException() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fixtures/php_masquerading_as_png.php")) {
            assertNotNull(is, "Fixture file not found");
            byte[] fileContent = is.readAllBytes();
            
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "php_masquerading_as_png.php",
                    "image/png",
                    fileContent
            );

            // Mock ClamAV as clean
            when(clamAvService.scan(any(InputStream.class))).thenReturn(true);

            assertThrows(UnsupportedMediaTypeException.class, () -> {
                fileService.uploadFile(file, ownerId, ipAddress);
            });

            // Verify storage write is skipped
            verify(storageService, never()).store(any(String.class), any(InputStream.class));
        }
    }

    @Test
    void uploadFile_extensionMimeMismatch_textAsJpg_throwsException() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fixtures/text_masquerading_as_jpg.jpg")) {
            assertNotNull(is, "Fixture file not found");
            byte[] fileContent = is.readAllBytes();
            
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "text_masquerading_as_jpg.jpg",
                    "image/jpeg",
                    fileContent
            );

            // Mock ClamAV as clean
            when(clamAvService.scan(any(InputStream.class))).thenReturn(true);

            assertThrows(UnsupportedMediaTypeException.class, () -> {
                fileService.uploadFile(file, ownerId, ipAddress);
            });

            // Verify storage write is skipped
            verify(storageService, never()).store(any(String.class), any(InputStream.class));
        }
    }

    @Test
    void uploadFile_polyglotJpegWithScript_throwsException() throws Exception {
        UUID ownerId = UUID.randomUUID();
        String ipAddress = "192.168.1.10";
        
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("fixtures/jpeg_with_script_payload.jpg")) {
            assertNotNull(is, "Fixture file not found");
            byte[] fileContent = is.readAllBytes();
            
            MockMultipartFile file = new MockMultipartFile(
                    "file",
                    "jpeg_with_script_payload.jpg",
                    "image/jpeg",
                    fileContent
            );

            // Mock ClamAV as clean
            when(clamAvService.scan(any(InputStream.class))).thenReturn(true);

            assertThrows(UnsupportedMediaTypeException.class, () -> {
                fileService.uploadFile(file, ownerId, ipAddress);
            });

            // Verify storage write is skipped
            verify(storageService, never()).store(any(String.class), any(InputStream.class));
        }
    }

    // isDangerousMimeType is invoked directly via reflection here (rather than through a full
    // uploadFile() call with a real fixture) because it isolates the deny-list logic itself from
    // Apache Tika's exact content-sniffing output, which cannot be reliably asserted for these
    // formats without a live Tika run against crafted binaries.
    @Test
    void isDangerousMimeType_executableAndElfBinaries_returnsTrue() {
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "application/x-executable"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "APPLICATION/X-EXECUTABLE"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "application/x-elf"));
    }

    @Test
    void isDangerousMimeType_scriptInterpreterTypes_returnsTrue() {
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "text/x-python"));
        assertTrue((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "application/x-httpd-php"));
    }

    @Test
    void isDangerousMimeType_safeTypes_returnsFalse() {
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "application/pdf"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "image/png"));
        assertFalse((Boolean) ReflectionTestUtils.invokeMethod(fileService, "isDangerousMimeType", "text/plain"));
    }
}
