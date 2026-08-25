package com.cloudshare.repository;

import com.cloudshare.model.FileMetadata;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileRepository extends JpaRepository<FileMetadata, UUID> {
    Optional<FileMetadata> findByIdAndOwnerIdAndDeletedFalse(UUID id, UUID ownerId);
    Page<FileMetadata> findByOwnerIdAndDeletedFalse(UUID ownerId, Pageable pageable);
    Optional<FileMetadata> findByIdAndDeletedFalse(UUID id);

    @Query("SELECT f FROM FileMetadata f WHERE f.id = :fileId AND f.deleted = false AND " +
           "(f.ownerId = :userId OR EXISTS (SELECT fs FROM FileShare fs WHERE fs.file.id = :fileId AND fs.sharedWith.id = :userId))")
    Optional<FileMetadata> findAccessibleFile(@Param("fileId") UUID fileId, @Param("userId") UUID userId);

    java.util.List<FileMetadata> findByDeletedTrueAndUpdatedAtBefore(java.time.Instant timestamp);

    @Query(value = "SELECT * FROM files WHERE kek_version = :oldVersion AND deleted = false AND id NOT IN (:failedIds) LIMIT :limit FOR UPDATE SKIP LOCKED", nativeQuery = true)
    java.util.List<FileMetadata> findBatchForReKey(
            @Param("oldVersion") int oldVersion,
            @Param("failedIds") java.util.Collection<java.util.UUID> failedIds,
            @Param("limit") int limit
    );
}
