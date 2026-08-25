package com.cloudshare.security;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/**
 * Filter enforcing Multi-Factor Step-Up Authentication for administrative API
 * paths ({@code /api/v1/admin/*}).
 * <p>
 * <b>Why single-use Redis blacklisting is strictly enforced:</b> Administrative
 * actions require heightened security boundaries beyond standard bearer JWTs.
 * Every step-up token <b>instance</b> is strictly <b>single-use</b>: after
 * cryptographically verifying signature and claims, this filter atomically
 * claims {@code blacklist:token:<jti>} on the dedicated <b>Redis Security</b>
 * instance via {@code setIfAbsent} (SET NX) before allowing execution. Any
 * re-use attempt or concurrent replay of the same step-up JTI is blocked with
 * HTTP 401. If Redis Security is unavailable, the filter fails closed with
 * HTTP 503 Service Unavailable.
 * </p>
 * <p>
 * <b>Rotation, bounded by an absolute session cap:</b> The documented UX is a
 * single MFA prompt covering a short "admin session" during which the client
 * may issue multiple sequential admin requests (e.g. loading the users table
 * then the audit log table). Because each token instance is single-use, this
 * filter issues a freshly rotated step-up token in the {@code X-StepUp-Token}
 * response header on every successful admin request, carrying forward the
 * session's original MFA-verification instant ({@code orig_iat}) unchanged.
 * Rotation is refused — forcing a fresh MFA prompt — once
 * {@code now - orig_iat} exceeds
 * {@link JwtTokenProvider#getStepUpSessionMaxSeconds()},
 * so an elevated session can never be kept alive indefinitely purely by
 * chaining rotations.
 * </p>
 */
@Component
@Slf4j
public class StepUpAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final StringRedisTemplate securityRedisTemplate;

    public StepUpAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            @Qualifier("securityRedisTemplate") StringRedisTemplate securityRedisTemplate) {
        this.tokenProvider = tokenProvider;
        this.securityRedisTemplate = securityRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        if (path.startsWith("/api/v1/admin")) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();

            // If the user is not authenticated at all, let standard Spring Security filters
            // handle it
            if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof UserPrincipal)) {
                log.debug("No active authenticated context for admin path {}. Skipping step-up check.", path);
                filterChain.doFilter(request, response);
                return;
            }

            boolean isAdmin = auth.getAuthorities().stream()
                    .anyMatch(grantedAuthority -> grantedAuthority.getAuthority().equals("ROLE_ADMIN"));

            if (!isAdmin) {
                log.debug("User lacks ROLE_ADMIN. Skipping step-up check to let Spring Security return 403 Forbidden.");
                filterChain.doFilter(request, response);
                return;
            }

            UserPrincipal principal = (UserPrincipal) auth.getPrincipal();
            String stepUpToken = request.getHeader("X-StepUp-Token");

            if (stepUpToken == null || stepUpToken.isBlank()) {
                log.warn("Missing X-StepUp-Token header for user {} accessing admin path {}", principal.getUsername(),
                        path);
                sendStepUpRequiredResponse(response);
                return;
            }

            // 1. Cryptographically verify signature and claims first (prevents
            // unauthenticated JTI pollution)
            Claims claims = tokenProvider.parseAndValidateStepUpToken(stepUpToken, principal.getId().toString());
            if (claims == null) {
                log.warn("Invalid step-up token for user {} attempting to access {}", principal.getUsername(), path);
                sendStepUpRequiredResponse(response);
                return;
            }

            String jti = claims.getId();
            Date expiration = claims.getExpiration();
            Long origIat = claims.get("orig_iat", Long.class);
            if (jti == null || expiration == null || origIat == null) {
                log.warn("Step-up token missing JTI, expiration, or orig_iat claim for user {}",
                        principal.getUsername());
                sendStepUpRequiredResponse(response);
                return;
            }

            // 2. TTL Guard: Reject expired token before attempting Redis call
            long remainingTimeMs = expiration.getTime() - System.currentTimeMillis();
            if (remainingTimeMs <= 0) {
                log.warn("Step-up token expired for user {}", principal.getUsername());
                sendStepUpRequiredResponse(response);
                return;
            }

            // 3. Atomic Single-Use Claim (SET NX EX) — this token INSTANCE may never be
            // presented again, regardless of what happens next in this request.
            String blacklistKey = "blacklist:token:" + jti;
            try {
                Boolean firstUse = securityRedisTemplate.opsForValue()
                        .setIfAbsent(blacklistKey, "blacklisted", Duration.ofMillis(remainingTimeMs));
                if (!Boolean.TRUE.equals(firstUse)) {
                    log.warn("Step-up token JTI {} already claimed for user {}", jti, principal.getUsername());
                    sendStepUpRequiredResponse(response);
                    return;
                }
            } catch (Exception e) {
                log.error("Step-up enforcement unavailable — failing closed for user {} accessing {}",
                        principal.getUsername(), path, e);
                sendServiceUnavailableResponse(response);
                return;
            }

            // 4. Absolute Session Cap: only rotate (extend the session with a fresh
            // single-use token) if the session's original MFA verification is still
            // within the configured absolute lifetime. Otherwise the request is still
            // allowed to complete (the presented token was validly claimed above), but
            // no successor token is issued — the client's next admin request will be
            // forced back through a fresh MFA prompt.
            long sessionAgeMs = System.currentTimeMillis() - origIat;
            long sessionCapMs = tokenProvider.getStepUpSessionMaxSeconds() * 1000L;
            if (sessionAgeMs < sessionCapMs) {
                String rotatedToken = tokenProvider.generateStepUpToken(
                        principal.getId().toString(), principal.getUsername(), origIat);
                response.setHeader("X-StepUp-Token", rotatedToken);
                response.setHeader("Access-Control-Expose-Headers", "X-StepUp-Token");
            } else {
                log.info("Step-up session for user {} reached absolute cap ({}s); not rotating.",
                        principal.getUsername(), tokenProvider.getStepUpSessionMaxSeconds());
            }
        }

        filterChain.doFilter(request, response);
    }

    private void sendStepUpRequiredResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"error\":{\"code\":\"STEP_UP_REQUIRED\",\"message\":\"Missing or invalid bearer/step-up token.\"},\"timestamp\":\""
                        + Instant.now() + "\"}");
    }

    private void sendServiceUnavailableResponse(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"success\":false,\"error\":{\"code\":\"SERVICE_UNAVAILABLE\",\"message\":\"Security state store unavailable.\"},\"timestamp\":\""
                        + Instant.now() + "\"}");
    }
}
