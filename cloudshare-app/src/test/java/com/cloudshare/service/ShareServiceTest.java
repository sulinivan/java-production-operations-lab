package com.cloudshare.service;

import com.cloudshare.dto.*;
import com.cloudshare.exception.AccessDeniedException;
import com.cloudshare.exception.ResourceNotFoundException;
import com.cloudshare.model.*;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.repository.FileShareRepository;
import com.cloudshare.repository.ShareLinkRepository;
import com.cloudshare.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ShareServiceTest {

        @Mock
        private FileShareRepository fileShareRepository;
        @Mock
        private ShareLinkRepository shareLinkRepository;
        @Mock
        private FileRepository fileRepository;
        @Mock
        private UserRepository userRepository;
        @Mock
        private PasswordEncoder passwordEncoder;
        @Mock
        private AuditLogService auditLogService;
        @Mock
        private EncryptionService encryptionService;
        @Mock
        private StorageService storageService;
        @Mock
        private PermissionCacheService permissionCacheService;
        @Mock
        private DownloadConcurrencyLimiter downloadConcurrencyLimiter;

        private ShareService shareService;

        @BeforeEach
        void setUp() {
                lenient().when(downloadConcurrencyLimiter.getSemaphore()).thenReturn(new java.util.concurrent.Semaphore(20, true));
                shareService = new ShareService(
                                fileShareRepository, shareLinkRepository, fileRepository, userRepository,
                                passwordEncoder, auditLogService, encryptionService, storageService,
                                permissionCacheService, downloadConcurrencyLimiter, 10);
        }

        @Test
        void shareFileInternally_success() {
                UUID ownerId = UUID.randomUUID();
                UUID targetId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                String ipAddress = "192.168.1.10";

                InternalShareRequest request = InternalShareRequest.builder()
                                .fileId(fileId)
                                .targetUsernameOrEmail("janedoe")
                                .permissionType("READ")
                                .build();

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("report.pdf")
                                .deleted(false).build();
                User targetUser = User.builder().id(targetId).username("janedoe").email("janedoe@example.com").build();
                User ownerUser = User.builder().id(ownerId).username("john").email("john@example.com").build();

                when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, ownerId)).thenReturn(Optional.of(file));
                when(userRepository.findByUsernameOrEmail("janedoe", "janedoe")).thenReturn(Optional.of(targetUser));
                when(userRepository.findById(ownerId)).thenReturn(Optional.of(ownerUser));
                when(fileShareRepository.findByFileIdAndSharedWithId(fileId, targetId)).thenReturn(Optional.empty());

                FileShare savedShare = FileShare.builder()
                                .id(UUID.randomUUID())
                                .file(file)
                                .sharedBy(ownerUser)
                                .sharedWith(targetUser)
                                .permissionType(PermissionType.READ)
                                .build();
                when(fileShareRepository.save(any(FileShare.class))).thenReturn(savedShare);

                InternalShareResponse response = shareService.shareFileInternally(request, ownerId, ipAddress);

                assertNotNull(response);
                assertEquals(fileId, response.getFileId());
                assertEquals("janedoe@example.com", response.getSharedWith());
                assertEquals("READ", response.getPermission());

                verify(permissionCacheService).evict(fileId);
                verify(auditLogService).log(eq(ownerId), eq("SHARE_CREATED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void shareFileInternally_shareWithSelf_throwsException() {
                UUID ownerId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();

                InternalShareRequest request = InternalShareRequest.builder()
                                .fileId(fileId)
                                .targetUsernameOrEmail("john")
                                .permissionType("READ")
                                .build();

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).deleted(false).build();
                User selfUser = User.builder().id(ownerId).username("john").build();

                when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, ownerId)).thenReturn(Optional.of(file));
                when(userRepository.findByUsernameOrEmail("john", "john")).thenReturn(Optional.of(selfUser));

                assertThrows(IllegalArgumentException.class,
                                () -> shareService.shareFileInternally(request, ownerId, "127.0.0.1"));
        }

        @Test
        void createPublicLink_success() {
                UUID ownerId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                String ipAddress = "192.168.1.10";

                CreatePublicLinkRequest request = CreatePublicLinkRequest.builder()
                                .fileId(fileId)
                                .expiresInSeconds(3600L)
                                .password("LinkSecret")
                                .downloadLimit(3)
                                .build();

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("doc.pdf")
                                .deleted(false).build();

                when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, ownerId)).thenReturn(Optional.of(file));
                when(passwordEncoder.encode("LinkSecret")).thenReturn("hashed_password");
                when(shareLinkRepository.existsByShareCode(any(String.class))).thenReturn(false);

                ShareLink savedLink = ShareLink.builder()
                                .shareCode("ABCDEFGH")
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .passwordHash("hashed_password")
                                .build();
                when(shareLinkRepository.save(any(ShareLink.class))).thenReturn(savedLink);

                PublicLinkResponse response = shareService.createPublicLink(request, ownerId, ipAddress);

                assertNotNull(response);
                assertEquals("ABCDEFGH", response.getShareCode());
                assertTrue(response.isPasswordProtected());
                verify(auditLogService).log(eq(ownerId), eq("SHARE_CREATED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void downloadPublicLink_success() throws Exception {
                UUID fileId = UUID.randomUUID();
                String shareCode = "ABCDEFGH";
                String ipAddress = "192.168.1.10";

                FileMetadata file = FileMetadata.builder()
                                .id(fileId)
                                .storagePath("store/123")
                                .originalFilename("pic.png")
                                .mimeType("image/png")
                                .fileSizeBytes(100L)
                                .encryptedFek("fek_wrapped")
                                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                                .deleted(false)
                                .build();

                UUID linkId = UUID.randomUUID();
                ShareLink shareLink = ShareLink.builder()
                                .id(linkId)
                                .file(file)
                                .shareCode(shareCode)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .downloadLimit(5)
                                .downloadCount(1)
                                .passwordHash("hashed_pass")
                                .build();

                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));
                when(passwordEncoder.matches("my_pass", "hashed_pass")).thenReturn(true);
                when(shareLinkRepository.incrementDownloadCountConditional(linkId)).thenReturn(1);

                SecretKey mockFek = new SecretKeySpec(new byte[32], "AES");
                when(encryptionService.unwrapFek("fek_wrapped", 1)).thenReturn(mockFek);
                when(storageService.retrieve("store/123"))
                                .thenReturn(new ByteArrayInputStream("encrypted_data".getBytes()));

                FileService.DecryptedFileStream stream = shareService.downloadPublicLink(shareCode, "my_pass",
                                ipAddress);

                assertNotNull(stream);
                assertEquals("pic.png", stream.getFilename());
                assertEquals("image/png", stream.getMimeType());

                verify(shareLinkRepository).incrementDownloadCountConditional(linkId);
                verify(auditLogService).log(isNull(), eq("GUEST_DOWNLOAD"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void downloadPublicLink_expired_throwsException() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCode, null, "127.0.0.1"));
                assertEquals("Link not found or no longer available", ex.getMessage());
        }

        @Test
        void downloadPublicLink_limitReached_throwsException() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .downloadLimit(2)
                                .downloadCount(2)
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                assertThrows(AccessDeniedException.class,
                                () -> shareService.downloadPublicLink(shareCode, null, "127.0.0.1"));
        }

        @Test
        void downloadPublicLink_concurrentAtomicIncrementReturnsZero_throwsException() {
                UUID linkId = UUID.randomUUID();
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .id(linkId)
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .downloadLimit(2)
                                .downloadCount(1)
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));
                when(shareLinkRepository.incrementDownloadCountConditional(linkId)).thenReturn(0);

                assertThrows(AccessDeniedException.class,
                                () -> shareService.downloadPublicLink(shareCode, null, "127.0.0.1"));
                verify(shareLinkRepository).incrementDownloadCountConditional(linkId);
                verifyNoInteractions(storageService);
                verifyNoInteractions(auditLogService);
        }

        @Test
        void downloadPublicLink_passwordRequired_throwsException() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .passwordHash("hashed_pass")
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));
                when(passwordEncoder.matches(any(String.class), eq("hashed_pass"))).thenReturn(false);

                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCode, "wrong_pass", "127.0.0.1"));
                assertEquals("Link not found or no longer available", ex.getMessage());
        }

        @Test
        void downloadPublicLink_passwordMissing_throwsException() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .passwordHash("hashed_pass")
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCode, null, "127.0.0.1"));
                assertEquals("Link not found or no longer available", ex.getMessage());
        }

        @Test
        void downloadPublicLink_nonexistentCode_throwsResourceNotFoundException() {
                String shareCode = "ABCDEFGH";
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.empty());

                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCode, null, "127.0.0.1"));
                assertEquals("Link not found or no longer available", ex.getMessage());
        }

        @Test
        void downloadPublicLink_nonexistentVsWrongPassword_throwsIdenticalException() {
                String shareCodeNonexistent = "NONEXIST";
                String shareCodeExisting = "EXISTING";

                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .passwordHash("hashed_pass")
                                .build();

                when(shareLinkRepository.findByShareCode(shareCodeNonexistent)).thenReturn(Optional.empty());
                when(shareLinkRepository.findByShareCode(shareCodeExisting)).thenReturn(Optional.of(shareLink));
                when(passwordEncoder.matches(any(String.class), eq("hashed_pass"))).thenReturn(false);

                ResourceNotFoundException ex1 = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCodeNonexistent, null, "127.0.0.1"));
                ResourceNotFoundException ex2 = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.downloadPublicLink(shareCodeExisting, "wrong", "127.0.0.1"));

                assertEquals(ex1.getClass(), ex2.getClass());
                assertEquals(ex1.getMessage(), ex2.getMessage());
                assertEquals("Link not found or no longer available", ex1.getMessage());
        }

        @Test
        void getPublicLinkInfo_success() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().plus(1, ChronoUnit.HOURS))
                                .passwordHash("hashed_pass")
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                PublicLinkInfoResponse res = shareService.getPublicLinkInfo(shareCode);
                assertNotNull(res);
                assertTrue(res.isPasswordProtected());
        }

        @Test
        void getPublicLinkInfo_invalidOrExpiredCode_throwsException() {
                String shareCode = "ABCDEFGH";
                FileMetadata file = FileMetadata.builder().deleted(false).build();
                ShareLink shareLink = ShareLink.builder()
                                .file(file)
                                .expiresAt(Instant.now().minus(1, ChronoUnit.HOURS))
                                .build();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                ResourceNotFoundException ex = assertThrows(ResourceNotFoundException.class,
                                () -> shareService.getPublicLinkInfo(shareCode));
                assertEquals("Link not found or no longer available", ex.getMessage());
        }

        @Test
        void revokeInternalShare_successByOwner() {
                UUID ownerId = UUID.randomUUID();
                UUID targetId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                UUID shareId = UUID.randomUUID();
                String ipAddress = "192.168.1.10";

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("report.pdf")
                                .deleted(false).build();
                User targetUser = User.builder().id(targetId).username("janedoe").email("janedoe@example.com").build();
                User ownerUser = User.builder().id(ownerId).username("john").email("john@example.com").build();

                FileShare fileShare = FileShare.builder()
                                .id(shareId)
                                .file(file)
                                .sharedBy(ownerUser)
                                .sharedWith(targetUser)
                                .permissionType(PermissionType.READ)
                                .build();

                when(fileShareRepository.findById(shareId)).thenReturn(Optional.of(fileShare));

                shareService.revokeInternalShare(shareId, ownerId, ipAddress);

                verify(fileShareRepository).delete(fileShare);
                verify(permissionCacheService).evict(fileId);
                verify(auditLogService).log(eq(ownerId), eq("SHARE_REVOKED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void revokeInternalShare_successBySharedBy() {
                UUID ownerId = UUID.randomUUID();
                UUID sharedById = UUID.randomUUID();
                UUID targetId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                UUID shareId = UUID.randomUUID();
                String ipAddress = "192.168.1.10";

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("report.pdf")
                                .deleted(false).build();
                User targetUser = User.builder().id(targetId).username("janedoe").email("janedoe@example.com").build();
                User sharingUser = User.builder().id(sharedById).username("john").email("john@example.com").build();

                FileShare fileShare = FileShare.builder()
                                .id(shareId)
                                .file(file)
                                .sharedBy(sharingUser)
                                .sharedWith(targetUser)
                                .permissionType(PermissionType.READ)
                                .build();

                when(fileShareRepository.findById(shareId)).thenReturn(Optional.of(fileShare));

                shareService.revokeInternalShare(shareId, sharedById, ipAddress);

                verify(fileShareRepository).delete(fileShare);
                verify(permissionCacheService).evict(fileId);
                verify(auditLogService).log(eq(sharedById), eq("SHARE_REVOKED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void revokeInternalShare_nonOwner_throwsException() {
                UUID ownerId = UUID.randomUUID();
                UUID targetId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                UUID shareId = UUID.randomUUID();
                UUID randomUserId = UUID.randomUUID();

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("report.pdf")
                                .deleted(false).build();
                User targetUser = User.builder().id(targetId).username("janedoe").build();
                User ownerUser = User.builder().id(ownerId).username("john").build();

                FileShare fileShare = FileShare.builder()
                                .id(shareId)
                                .file(file)
                                .sharedBy(ownerUser)
                                .sharedWith(targetUser)
                                .build();

                when(fileShareRepository.findById(shareId)).thenReturn(Optional.of(fileShare));

                assertThrows(com.cloudshare.exception.ResourceNotFoundException.class,
                                () -> shareService.revokeInternalShare(shareId, randomUserId, "127.0.0.1"));

                verify(fileShareRepository, never()).delete(any());
                verify(permissionCacheService, never()).evict(any());
                verify(auditLogService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        void revokeInternalShare_notExist_throwsException() {
                UUID shareId = UUID.randomUUID();
                UUID callerId = UUID.randomUUID();
                when(fileShareRepository.findById(shareId)).thenReturn(Optional.empty());

                assertThrows(com.cloudshare.exception.ResourceNotFoundException.class,
                                () -> shareService.revokeInternalShare(shareId, callerId, "127.0.0.1"));
        }

        @Test
        void revokePublicLink_success() {
                UUID ownerId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                String shareCode = "XYZ12345";
                String ipAddress = "192.168.1.10";

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("doc.pdf")
                                .deleted(false).build();
                ShareLink shareLink = ShareLink.builder().file(file).shareCode(shareCode).build();

                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                shareService.revokePublicLink(shareCode, ownerId, ipAddress);

                verify(shareLinkRepository).delete(shareLink);
                verify(auditLogService).log(eq(ownerId), eq("SHARE_LINK_REVOKED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }

        @Test
        void revokePublicLink_nonOwner_throwsException() {
                UUID ownerId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                UUID randomUserId = UUID.randomUUID();
                String shareCode = "XYZ12345";

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("doc.pdf")
                                .deleted(false).build();
                ShareLink shareLink = ShareLink.builder().file(file).shareCode(shareCode).build();

                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.of(shareLink));

                assertThrows(com.cloudshare.exception.ResourceNotFoundException.class,
                                () -> shareService.revokePublicLink(shareCode, randomUserId, "127.0.0.1"));

                verify(shareLinkRepository, never()).delete(any());
                verify(auditLogService, never()).log(any(), any(), any(), any(), any());
        }

        @Test
        void revokePublicLink_notExist_throwsException() {
                String shareCode = "XYZ12345";
                UUID callerId = UUID.randomUUID();
                when(shareLinkRepository.findByShareCode(shareCode)).thenReturn(Optional.empty());

                assertThrows(com.cloudshare.exception.ResourceNotFoundException.class,
                                () -> shareService.revokePublicLink(shareCode, callerId, "127.0.0.1"));
        }

        // The two tests that used to live here (shareFileInternally_evictionDeleteThrows_setsBypassMarker,
        // shareFileInternally_evictionDeleteAndBypassSetThrow_doesNotCrash) exercised the OLD inline
        // eviction-with-bypass-marker exception handling that lived directly in ShareService. That
        // handling — and its failure modes — now live entirely inside PermissionCacheService.evict(),
        // which never propagates an exception back to its caller (see PermissionCacheServiceTest for
        // the self-heal/bypass-marker-under-Redis-failure coverage). From ShareService's perspective
        // there is only one meaningful behavior left to verify: that evict() is actually called.
        @Test
        void shareFileInternally_delegatesEvictionToPermissionCacheService() {
                UUID ownerId = UUID.randomUUID();
                UUID targetId = UUID.randomUUID();
                UUID fileId = UUID.randomUUID();
                String ipAddress = "192.168.1.10";

                InternalShareRequest request = InternalShareRequest.builder()
                                .fileId(fileId)
                                .targetUsernameOrEmail("janedoe")
                                .permissionType("READ")
                                .build();

                FileMetadata file = FileMetadata.builder().id(fileId).ownerId(ownerId).originalFilename("report.pdf")
                                .deleted(false).build();
                User targetUser = User.builder().id(targetId).username("janedoe").email("janedoe@example.com").build();
                User ownerUser = User.builder().id(ownerId).username("john").email("john@example.com").build();

                when(fileRepository.findByIdAndOwnerIdAndDeletedFalse(fileId, ownerId)).thenReturn(Optional.of(file));
                when(userRepository.findByUsernameOrEmail("janedoe", "janedoe")).thenReturn(Optional.of(targetUser));
                when(userRepository.findById(ownerId)).thenReturn(Optional.of(ownerUser));
                when(fileShareRepository.findByFileIdAndSharedWithId(fileId, targetId)).thenReturn(Optional.empty());

                FileShare savedShare = FileShare.builder()
                                .id(UUID.randomUUID())
                                .file(file)
                                .sharedBy(ownerUser)
                                .sharedWith(targetUser)
                                .permissionType(PermissionType.READ)
                                .build();
                when(fileShareRepository.save(any(FileShare.class))).thenReturn(savedShare);

                InternalShareResponse response = shareService.shareFileInternally(request, ownerId, ipAddress);

                assertNotNull(response);
                verify(permissionCacheService).evict(fileId);
                verify(auditLogService).log(eq(ownerId), eq("SHARE_CREATED"), eq(fileId), eq(ipAddress),
                                any(String.class));
        }
}
