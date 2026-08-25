package com.cloudshare.model;

import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditLog {
    private Long id;
    private UUID userId;
    private String action;
    private UUID fileId;
    private String ipAddress;
    private String details;
    private Instant createdAt;
}
