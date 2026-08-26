package com.cloudshare.security;

/**
 * Immutable record representing a pre-resolved/validated JWT within the request lifecycle.
 * <p>
 * <b>Why this exists:</b> Multiple security filters in the chain (specifically {@link RateLimitingFilter}
 * followed by {@link JwtAuthenticationFilter}) require details from the incoming Bearer token (such as
 * user identity, token type, and JTI for blacklisting). Rather than re-parsing cryptographic signatures
 * and claim maps multiple times per request, {@link RateLimitingFilter} parses the token once and attaches
 * this {@code ResolvedJwt} instance to the {@link jakarta.servlet.http.HttpServletRequest} under
 * {@link #REQUEST_ATTRIBUTE}. Downstream filters consume this cached result to eliminate redundant cryptographic operations.
 * </p>
 *
 * @param token     the raw JWT string extracted from the request
 * @param valid     {@code true} if the signature, structure, and expiration claims are valid
 * @param userId    the subject UUID extracted from valid token claims, or {@code null}
 * @param tokenType the token type claim (e.g., "access", "step_up", or "refresh"), or {@code null}
 * @param jti       the unique token identifier claim (JTI) used for Redis blacklist lookups, or {@code null}
 */
public record ResolvedJwt(
        String token,
        boolean valid,
        String userId,
        String tokenType,
        String jti) {
    /**
     * Request attribute key under which the resolved JWT is cached in {@link jakarta.servlet.http.HttpServletRequest}.
     */
    public static final String REQUEST_ATTRIBUTE = "com.cloudshare.security.RESOLVED_JWT";
}
