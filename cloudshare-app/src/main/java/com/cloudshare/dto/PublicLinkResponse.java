package com.cloudshare.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicLinkResponse {
    private String shareCode;
    private String shareUrl;
    private Instant expiresAt;
    private boolean passwordProtected;
}
