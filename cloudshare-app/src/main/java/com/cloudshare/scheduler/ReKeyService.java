package com.cloudshare.scheduler;

import com.cloudshare.model.FileMetadata;
import com.cloudshare.repository.FileRepository;
import com.cloudshare.service.AuditLogService;
import com.cloudshare.service.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReKeyService {

    private final FileRepository fileRepository;
    private final EncryptionService encryptionService;
    private final AuditLogService auditLogService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int processNextBatch(int oldVersion, int newVersion, Set<UUID> failedIds) {
        log.debug("Fetching next batch of files to re-key from version {} to {}", oldVersion, newVersion);
        
        Collection<UUID> excludeIds = failedIds.isEmpty() ?
                Collections.singletonList(UUID.fromString("00000000-0000-0000-0000-000000000000")) : failedIds;

        List<FileMetadata> batch = fileRepository.findBatchForReKey(oldVersion, excludeIds, 100);
        if (batch.isEmpty()) {
            return 0;
        }

        for (FileMetadata file : batch) {
            try {
                // 1. Decrypt FEK using historical KEK version
                SecretKey fek = encryptionService.unwrapFek(file.getEncryptedFek(), oldVersion);

                // 2. Encrypt FEK using target KEK version
                String newCiphertext = encryptionService.wrapFek(fek, newVersion);

                // 3. Save new metadata
                file.setEncryptedFek(newCiphertext);
                file.setKekVersion(newVersion);
                fileRepository.save(file);

                // 4. Log audit event
                auditLogService.log(null, "SYSTEM_REKEY", file.getId(), "127.0.0.1",
                        "Successfully re-keyed file metadata from KEK version " + oldVersion + " to " + newVersion);

                log.info("Successfully re-keyed file metadata. UUID: {}, New KEK Version: {}", file.getId(), newVersion);
            } catch (Exception e) {
                log.error("Failed to re-key file metadata. UUID: {}. Skipping for manual review.", file.getId(), e);
                failedIds.add(file.getId());
                try {
                    auditLogService.log(null, "SYSTEM_REKEY_FAILED", file.getId(), "127.0.0.1",
                            "Re-key failed: " + e.getMessage());
                } catch (Exception auditEx) {
                    log.error("Failed to write failure audit log for UUID: {}", file.getId(), auditEx);
                }
            }
        }
        return batch.size();
    }

    public void performReKey(int oldVersion, int newVersion) {
        Set<UUID> failedIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int maxIterations = 10000;
        int iteration = 0;
        int processed;

        do {
            processed = processNextBatch(oldVersion, newVersion, failedIds);
            iteration++;
            if (iteration >= maxIterations) {
                log.error("Re-key job exceeded max iterations, possible stuck lock. Aborting.");
                throw new RuntimeException("Re-key job exceeded max iterations, possible stuck lock. Aborting.");
            }
        } while (processed > 0);
    }
}
