package com.cloudshare.repository;

import com.cloudshare.model.ShareLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Repository
public interface ShareLinkRepository extends JpaRepository<ShareLink, UUID> {
    Optional<ShareLink> findByShareCode(String shareCode);
    boolean existsByShareCode(String shareCode);

    @Modifying
    @Transactional
    @Query("DELETE FROM ShareLink s WHERE s.expiresAt < :now")
    int deleteByExpiresAtBefore(@Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query("UPDATE ShareLink s SET s.downloadCount = s.downloadCount + 1 " +
           "WHERE s.id = :id AND (s.downloadLimit IS NULL OR s.downloadCount < s.downloadLimit)")
    int incrementDownloadCountConditional(@Param("id") UUID id);
}
