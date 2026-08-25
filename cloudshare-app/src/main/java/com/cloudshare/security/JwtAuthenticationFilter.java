package com.cloudshare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Filter that authenticates incoming requests bearing a valid JWT access token in the {@code Authorization: Bearer <token>} header.
 * <p>
 * <b>Why attribute hand-off and blacklist verification exist:</b>
 * <ul>
 *   <li><b>Performance Optimization:</b> Checks if {@link RateLimitingFilter} already parsed and validated the token into a
 *   {@link ResolvedJwt} request attribute ({@link ResolvedJwt#REQUEST_ATTRIBUTE}). If present, it re-uses the pre-validated identity
 *   to avoid redundant HMAC-SHA256 signature verifications and JSON parsing.</li>
 *   <li><b>Instant Token Revocation:</b> Queries the dedicated <b>Redis Security</b> instance for {@code blacklist:token:<jti>}.
 *   If blacklisted (e.g., user logout or compromised token family revocation), authentication is rejected immediately with HTTP 401.</li>
 *   <li><b>Strict Type Enforcement:</b> Only tokens with {@code tokenType == "access"} are accepted for general API authentication,
 *   preventing step-up or refresh tokens from being passed as general bearer tokens.</li>
 * </ul>
 * </p>
 */
@Component
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtTokenProvider tokenProvider;
    private final CustomUserDetailsService customUserDetailsService;
    private final StringRedisTemplate securityRedisTemplate;

    public JwtAuthenticationFilter(
            JwtTokenProvider tokenProvider,
            CustomUserDetailsService customUserDetailsService,
            @Qualifier("securityRedisTemplate") StringRedisTemplate securityRedisTemplate) {
        this.tokenProvider = tokenProvider;
        this.customUserDetailsService = customUserDetailsService;
        this.securityRedisTemplate = securityRedisTemplate;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            String jwt = getJwtFromRequest(request);

            if (StringUtils.hasText(jwt)) {
                ResolvedJwt resolved = (ResolvedJwt) request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE);
                if (resolved == null || !jwt.equals(resolved.token())) {
                    // JwtTokenProvider#resolveToken is guaranteed to return a non-null
                    // ResolvedJwt (valid=false on parse failure), so no null fallback
                    // is needed here.
                    resolved = tokenProvider.resolveToken(jwt);
                }

                if (resolved.valid() && "access".equals(resolved.tokenType())) {
                    String jti = resolved.jti();

                    // Verify blacklist status in Redis Security
                    boolean isBlacklisted = false;
                    if (jti != null) {
                        String blacklistKey = "blacklist:token:" + jti;
                        isBlacklisted = Boolean.TRUE.equals(securityRedisTemplate.hasKey(blacklistKey));
                    }

                    if (isBlacklisted) {
                        log.warn("Attempted access with blacklisted JWT access token jti={}", jti);
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                        response.setContentType("application/json");
                        response.getWriter().write(
                                "{\"success\":false,\"error\":{\"code\":\"UNAUTHORIZED\",\"message\":\"Token is blacklisted/revoked.\"},\"timestamp\":\""
                                        + java.time.Instant.now() + "\"}");
                        return;
                    }

                    String userIdStr = resolved.userId();
                    UUID userId = UUID.fromString(userIdStr);

                    UserDetails userDetails = customUserDetailsService.loadUserById(userId);
                    UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            }
        } catch (Exception ex) {
            log.error("Could not set user authentication in security context", ex);
        }

        filterChain.doFilter(request, response);
    }

    private String getJwtFromRequest(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            return bearerToken.substring(7);
        }
        return null;
    }
}
