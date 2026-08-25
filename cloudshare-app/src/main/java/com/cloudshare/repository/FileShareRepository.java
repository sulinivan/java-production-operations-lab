package com.cloudshare.repository;

import com.cloudshare.model.FileShare;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FileShareRepository extends JpaRepository<FileShare, UUID> {

    @Query("SELECT fs FROM FileShare fs JOIN fs.file f WHERE fs.sharedWith.id = :userId AND f.id = :fileId AND f.deleted = false")
    Optional<FileShare> findActiveShare(@Param("fileId") UUID fileId, @Param("userId") UUID userId);

    List<FileShare> findByFileId(UUID fileId);

    @Query("SELECT fs FROM FileShare fs WHERE fs.file.id = :fileId AND fs.sharedWith.id = :sharedWithId")
    Optional<FileShare> findByFileIdAndSharedWithId(@Param("fileId") UUID fileId, @Param("sharedWithId") UUID sharedWithId);

    @Query(value = "SELECT fs FROM FileShare fs JOIN FETCH fs.file f JOIN FETCH fs.sharedBy sb WHERE fs.sharedWith.id = :userId AND f.deleted = false",
           countQuery = "SELECT count(fs) FROM FileShare fs JOIN fs.file f WHERE fs.sharedWith.id = :userId AND f.deleted = false")
    Page<FileShare> findBySharedWithIdAndFileNotDeleted(@Param("userId") UUID userId, Pageable pageable);
}

