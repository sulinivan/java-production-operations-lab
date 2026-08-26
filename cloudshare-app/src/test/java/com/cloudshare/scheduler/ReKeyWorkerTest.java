package com.cloudshare.scheduler;

import com.cloudshare.config.CryptoProperties;
import com.cloudshare.model.FileMetadata;
import com.cloudshare.model.User;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.repository.UserRepository;
import com.cloudshare.service.AuditLogService;
import com.cloudshare.service.ClamAvService;
import com.cloudshare.service.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@SpringBootTest(properties = {
    "storage.provider=LOCAL",
    "storage.local.directory=target/test-uploads"
})
@ActiveProfiles("test")
class ReKeyWorkerTest {

    @Autowired
    private ReKeyService reKeyService;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private FileRepository fileRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CryptoProperties cryptoProperties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ConfigurableApplicationContext context;

    @MockitoBean
    private AuditLogService auditLogService;

    @MockitoBean(name = "redisTemplate")
    private StringRedisTemplate redisTemplate;

    @MockitoBean(name = "securityRedisTemplate")
    private StringRedisTemplate securityRedisTemplate;

    @MockitoBean
    private ClamAvService clamAvService;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Clear tables
        jdbcTemplate.execute("DELETE FROM share_links");
        jdbcTemplate.execute("DELETE FROM file_shares");
        jdbcTemplate.execute("DELETE FROM files");
        jdbcTemplate.execute("DELETE FROM user_roles");
        jdbcTemplate.execute("DELETE FROM users");

        // Create a test user
        testUser = User.builder()
                .username("test_user_rekey")
                .email("test_user_rekey@example.com")
                .passwordHash("password")
                .mfaEnabled(false)
                .roles(Collections.emptySet())
                .build();
        testUser = userRepository.save(testUser);

        // Configure crypto properties
        cryptoProperties.setMasterKek("master_kek_fallback_32_bytes_xx");
        Map<Integer, String> keks = new HashMap<>();
        keks.put(1, "master_kek_fallback_32_bytes_xx");
        keks.put(2, "new_kek_version_2_32_bytes_long");
        cryptoProperties.setKeks(keks);
    }

    @Test
    void testReKeyBatchProcessing_success() throws Exception {
        // Insert test files with real encrypted FEKs wrapped using version 1
        List<FileMetadata> files = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            SecretKey fek = encryptionService.generateFek();
            String wrappedFek = encryptionService.wrapFek(fek, 1);
            FileMetadata file = FileMetadata.builder()
                    .ownerId(testUser.getId())
                    .storagePath("storage_path_" + i)
                    .originalFilename("file_" + i + ".txt")
                    .fileSizeBytes(100L)
                    .mimeType("text/plain")
                    .checksumSha256("sha256")
                    .encryptedFek(wrappedFek)
                    .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                    .kekVersion(1)
                    .deleted(false)
                    .build();
            files.add(fileRepository.save(file));
        }

        // Run processNextBatch
        int processed = reKeyService.processNextBatch(1, 2, new HashSet<>());
        assertEquals(5, processed);

        // Verify updated entities
        for (FileMetadata original : files) {
            FileMetadata updated = fileRepository.findById(original.getId()).orElseThrow();
            assertEquals(2, updated.getKekVersion());
            
            // Check that we can successfully decrypt the FEK using KEK version 2!
            SecretKey unwrappedWithV2 = encryptionService.unwrapFek(updated.getEncryptedFek(), 2);
            assertNotNull(unwrappedWithV2);
        }

        // Verify audit logging called 5 times
        verify(auditLogService, times(5)).log(isNull(), eq("SYSTEM_REKEY"), any(UUID.class), eq("127.0.0.1"), anyString());
    }

    @Test
    void testReKeyBatchProcessing_handlesBadFile() throws Exception {
        // Insert 2 good files and 1 bad file
        SecretKey fek = encryptionService.generateFek();
        String wrappedFek = encryptionService.wrapFek(fek, 1);
        
        FileMetadata good1 = FileMetadata.builder()
                .ownerId(testUser.getId())
                .storagePath("good1")
                .originalFilename("good1.txt")
                .fileSizeBytes(100L)
                .mimeType("text/plain")
                .checksumSha256("sha256")
                .encryptedFek(wrappedFek)
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .kekVersion(1)
                .deleted(false)
                .build();
        good1 = fileRepository.save(good1);

        FileMetadata bad = FileMetadata.builder()
                .ownerId(testUser.getId())
                .storagePath("bad")
                .originalFilename("bad.txt")
                .fileSizeBytes(100L)
                .mimeType("text/plain")
                .checksumSha256("sha256")
                .encryptedFek("invalid_fek_value") // Invalid decryption
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .kekVersion(1)
                .deleted(false)
                .build();
        bad = fileRepository.save(bad);

        FileMetadata good2 = FileMetadata.builder()
                .ownerId(testUser.getId())
                .storagePath("good2")
                .originalFilename("good2.txt")
                .fileSizeBytes(100L)
                .mimeType("text/plain")
                .checksumSha256("sha256")
                .encryptedFek(wrappedFek)
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .kekVersion(1)
                .deleted(false)
                .build();
        good2 = fileRepository.save(good2);

        Set<UUID> failedIds = new HashSet<>();
        int processed = reKeyService.processNextBatch(1, 2, failedIds);
        assertEquals(3, processed);
        assertEquals(1, failedIds.size());
        assertTrue(failedIds.contains(bad.getId()));

        // Verify good files are updated
        FileMetadata updatedGood1 = fileRepository.findById(good1.getId()).orElseThrow();
        assertEquals(2, updatedGood1.getKekVersion());

        FileMetadata updatedGood2 = fileRepository.findById(good2.getId()).orElseThrow();
        assertEquals(2, updatedGood2.getKekVersion());

        // Verify bad file is not updated
        FileMetadata updatedBad = fileRepository.findById(bad.getId()).orElseThrow();
        assertEquals(1, updatedBad.getKekVersion());

        // Verify failure audit log is generated
        verify(auditLogService).log(isNull(), eq("SYSTEM_REKEY_FAILED"), eq(bad.getId()), eq("127.0.0.1"), anyString());
    }

    @Test
    void testReKeyWorkerCommandLineRunner_runsToCompletion() throws Exception {
        // Insert files
        for (int i = 0; i < 3; i++) {
            SecretKey fek = encryptionService.generateFek();
            String wrappedFek = encryptionService.wrapFek(fek, 1);
            FileMetadata file = FileMetadata.builder()
                    .ownerId(testUser.getId())
                    .storagePath("storage_path_runner_" + i)
                    .originalFilename("runner_" + i + ".txt")
                    .fileSizeBytes(100L)
                    .mimeType("text/plain")
                    .checksumSha256("sha256")
                    .encryptedFek(wrappedFek)
                    .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                    .kekVersion(1)
                    .deleted(false)
                    .build();
            fileRepository.save(file);
        }

        // Instantiate ReKeyWorker manually to test it without activating the profile in test configuration
        ReKeyWorker worker = new ReKeyWorker(reKeyService, context);
        ReflectionTestUtils.setField(worker, "oldVersionProp", 1);
        ReflectionTestUtils.setField(worker, "newVersionProp", 2);

        // Run worker command runner
        worker.run();

        // Assert everything is re-keyed to version 2
        List<FileMetadata> allFiles = fileRepository.findAll();
        assertEquals(3, allFiles.size());
        for (FileMetadata file : allFiles) {
            assertEquals(2, file.getKekVersion());
        }

        // Verify context close was triggered
        verify(context).close();
    }

    @Test
    @Transactional
    void testConcurrencySkipLocked() throws Exception {
        // Insert a file to lock
        FileMetadata f1 = FileMetadata.builder()
                .ownerId(testUser.getId())
                .storagePath("f1")
                .originalFilename("f1.txt")
                .fileSizeBytes(100L)
                .mimeType("text/plain")
                .checksumSha256("sha256")
                .encryptedFek("fek1")
                .ivGcm(Base64.getEncoder().encodeToString(new byte[12]))
                .kekVersion(1)
                .deleted(false)
                .build();
        f1 = fileRepository.save(f1);

        // Lock row within this transaction
        List<FileMetadata> batch1 = fileRepository.findBatchForReKey(1, java.util.Collections.singletonList(java.util.UUID.randomUUID()), 1);
        assertEquals(1, batch1.size());
        assertEquals(f1.getId(), batch1.get(0).getId());

        // A concurrent query on another thread should skip f1 because it is locked
        Future<List<FileMetadata>> future = Executors.newSingleThreadExecutor().submit(() -> {
            return fileRepository.findBatchForReKey(1, java.util.Collections.singletonList(java.util.UUID.randomUUID()), 1);
        });

        List<FileMetadata> batch2 = future.get();
        assertTrue(batch2.isEmpty(), "Expected locked row to be skipped by concurrent query");
    }
}
