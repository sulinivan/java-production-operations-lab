package com.cloudshare.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InternalShareRequest {
    @NotNull(message = "File ID is required")
    private UUID fileId;

    @NotBlank(message = "Target username or email is required")
    private String targetUsernameOrEmail;

    @NotBlank(message = "Permission type is required")
    private String permissionType;
}
