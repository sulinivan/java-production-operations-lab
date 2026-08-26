package com.cloudshare.dto;

import com.cloudshare.model.PermissionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SharedFileResponse {
    private UUID id;
    private String name;
    private Long sizeBytes;
    private String mimeType;
    private String checksum;
    private Instant uploadedAt;
    private String sharedByUsername;
    private PermissionType permissionType;
    private Instant sharedAt;
}
