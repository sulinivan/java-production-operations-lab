package com.cloudshare.security;

import com.cloudshare.service.RateLimiterService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Locale;

/**
 * Security filter executing distributed sliding-window rate limiting via Redis
 * Lua scripts before authentication.
 * <p>
 * <b>Why multi-tier rate limiting and request attribute hand-off exist:</b>
 * <ul>
 * <li><b>Multi-Tier Protection:</b> Enforces route-specific thresholds to
 * protect against brute-force and DoS:
 * <ul>
 * <li><b>Authentication (5/min per IP):</b> {@code /api/v1/auth/login},
 * {@code /register}, {@code /refresh}</li>
 * <li><b>MFA Verification (5/min per User/IP):</b>
 * {@code /api/v1/auth/mfa/verify}, {@code /step-up}</li>
 * <li><b>File Uploads (10/min per User/IP):</b>
 * {@code /api/v1/files/upload}</li>
 * <li><b>Public Link Access (Two-Tier):</b> Per-link+IP limit (30/min) AND
 * Global IP limit (100/min) to prevent link scraping.</li>
 * <li><b>General REST Endpoints (100/min per User/IP):</b> All other
 * {@code /api/v1/*} routes.</li>
 * </ul>
 * </li>
 * <li><b>Client IP Trust:</b> Delegates to {@link ClientIpResolver}. Secure
 * because backend containers are internal-network-only;
 * Nginx edge proxy unconditionally overwrites {@code X-Real-IP} with the true
 * remote socket address.</li>
 * <li><b>JWT Memoization:</b> Parses the Bearer token once to identify user ID,
 * attaching {@link ResolvedJwt} to
 * {@link HttpServletRequest#setAttribute} so downstream
 * {@link JwtAuthenticationFilter} avoids redundant parsing.</li>
 * </ul>
 * </p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private static final String PUBLIC_LINK_PATH_PREFIX = "/api/v1/shares/link/";

    private final RateLimiterService rateLimiterService;
    private final JwtTokenProvider tokenProvider;
    private final ClientIpResolver clientIpResolver;
    private final ObjectMapper objectMapper;

    /** Hard cap on how much of the login body we'll buffer in memory to peek at it. */
    private static final int MAX_LOGIN_BODY_PEEK_BYTES = 8 * 1024;

    @Value("${security.rate-limiting.enabled:true}")
    private boolean rateLimitingEnabled = true;

    @Value("${security.rate-limiting.auth-limit:5}")
    private int authLimit;

    @Value("${security.rate-limiting.upload-limit:10}")
    private int uploadLimit;

    @Value("${security.rate-limiting.link-limit:30}")
    private int linkLimit;

    @Value("${security.rate-limiting.general-limit:100}")
    private int generalLimit;

    @Value("${security.rate-limiting.mfa-limit:5}")
    private int mfaLimit;

    @Value("${security.rate-limiting.link-global-limit:100}")
    private int linkGlobalLimit;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        if (!rateLimitingEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        String method = request.getMethod();
        String ip = clientIpResolver.resolveIp(request);

        // requestToUse is the (possibly body-wrapped) request forwarded down the
        // filter chain. Only /auth/login gets wrapped, and only up to a small size
        // cap, to peek at the account identifier for per-account rate limiting.
        HttpServletRequest requestToUse = request;

        boolean allowed = true;

        if (path.startsWith("/api/v1/")) {
            if ("POST".equalsIgnoreCase(method) &&
                    (path.equals("/api/v1/auth/login") ||
                            path.equals("/api/v1/auth/register") ||
                            path.equals("/api/v1/auth/refresh"))) {

                // Per-IP auth rate limiting (existing protection)
                String ipKey = "limit:" + ip + ":" + path;
                boolean ipAllowed = rateLimiterService.isAllowed(ipKey, 60, authLimit);

                // Per-account rate limiting, login only: an IP-only guard doesn't stop
                // credential stuffing distributed across many source IPs against a
                // single target account. We key on a hash of the submitted identifier
                // (never the raw value) so the rate-limit store never holds plaintext
                // usernames/emails.
                boolean accountAllowed = true;
                if (path.equals("/api/v1/auth/login")) {
                    try {
                        CachedBodyHttpServletRequest cachedRequest = new CachedBodyHttpServletRequest(request);
                        requestToUse = cachedRequest;
                        String usernameOrEmail = extractUsernameOrEmail(cachedRequest);
                        if (StringUtils.hasText(usernameOrEmail)) {
                            String acctKey = "limit:acct:" + sha256Hex(usernameOrEmail.trim().toLowerCase(Locale.ROOT));
                            // Wider window than per-IP: this is a defense-in-depth backstop
                            // against distributed attempts, not the primary throttle.
                            accountAllowed = rateLimiterService.isAllowed(acctKey, 60, authLimit * 3);
                        }
                    } catch (Exception e) {
                        // Fail open on parsing problems — never let a malformed body break
                        // login for legitimate users; the per-IP limit above still applies.
                        log.debug("Could not parse login body for account-level rate limiting: {}",
                                e.getMessage());
                    }
                }

                allowed = ipAllowed && accountAllowed;

            } else if ("POST".equalsIgnoreCase(method) && path.equals("/api/v1/files/upload")) {

                // File upload rate limiting
                String userId = getUserIdFromAuthorizationHeader(request);
                String identifier = (userId != null) ? userId : ip;
                String key = "limit:" + identifier + ":" + path;
                allowed = rateLimiterService.isAllowed(key, 60, uploadLimit);

            } else if ("POST".equalsIgnoreCase(method) &&
                    (path.equals("/api/v1/auth/mfa/verify") ||
                            path.equals("/api/v1/auth/mfa/step-up"))) {

                // MFA rate limiting
                String userId = getUserIdFromAuthorizationHeader(request);
                String identifier = (userId != null) ? userId : ip;
                String key = "limit:" + identifier + ":" + path;
                allowed = rateLimiterService.isAllowed(key, 60, mfaLimit);

            } else if ("GET".equalsIgnoreCase(method) && path.startsWith(PUBLIC_LINK_PATH_PREFIX)) {

                // Public link access rate limiting
                String remaining = path.substring(PUBLIC_LINK_PATH_PREFIX.length());
                int slashIdx = remaining.indexOf('/');
                String shareCode = (slashIdx != -1) ? remaining.substring(0, slashIdx) : remaining;

                if (shareCode.isBlank()) {
                    // Malformed/edge-case path under the prefix with no code — don't rate-limit on
                    // an
                    // empty key; fall through to general-endpoint handling instead of silently
                    // using ""
                    // as a shared rate-limit bucket for every malformed request.
                    String userId = getUserIdFromAuthorizationHeader(request);
                    String identifier = (userId != null) ? userId : ip;
                    String key = "limit:general:" + identifier;
                    allowed = rateLimiterService.isAllowed(key, 60, generalLimit);
                } else {
                    String linkKey = "limit:link:" + shareCode + ":" + ip;
                    String globalKey = "limit:linkglobal:" + ip;

                    boolean linkAllowed = rateLimiterService.isAllowed(linkKey, 60, linkLimit);
                    boolean globalAllowed = rateLimiterService.isAllowed(globalKey, 60, linkGlobalLimit);
                    allowed = linkAllowed && globalAllowed;
                }

            } else {

                // General REST API rate limiting
                String userId = getUserIdFromAuthorizationHeader(request);
                String identifier = (userId != null) ? userId : ip;
                String key = "limit:general:" + identifier;
                allowed = rateLimiterService.isAllowed(key, 60, generalLimit);
            }
        }

        if (!allowed) {
            log.warn("Rate limit exceeded for path={} method={} IP={}", path, method, ip);
            response.setStatus(429);
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"success\":false,\"error\":{\"code\":\"TOO_MANY_REQUESTS\",\"message\":\"Rate limit exceeded. Please try again later.\"},\"timestamp\":\""
                            + Instant.now() + "\"}");
            return;
        }

        filterChain.doFilter(requestToUse, response);
    }

    /**
     * Extracts {@code usernameOrEmail} from a JSON login request body without
     * consuming the underlying stream for downstream consumers (the request must
     * be a {@link CachedBodyHttpServletRequest}). Bounded to a small byte cap and
     * fails silently (returns {@code null}) on any parsing problem.
     */
    private String extractUsernameOrEmail(CachedBodyHttpServletRequest cachedRequest) {
        try {
            byte[] body = cachedRequest.getInputStream().readNBytes(MAX_LOGIN_BODY_PEEK_BYTES);
            if (body.length == 0) {
                return null;
            }
            JsonNode node = objectMapper.readTree(body);
            JsonNode field = node.get("usernameOrEmail");
            return (field != null && field.isString()) ? field.asString() : null;
        } catch (Exception e) {
            log.debug("Failed to extract usernameOrEmail from login body: {}", e.getMessage());
            return null;
        }
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private String getUserIdFromAuthorizationHeader(HttpServletRequest request) {
        String bearerToken = request.getHeader("Authorization");
        if (StringUtils.hasText(bearerToken) && bearerToken.startsWith("Bearer ")) {
            String jwt = bearerToken.substring(7);
            try {
                ResolvedJwt resolved = (ResolvedJwt) request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE);
                if (resolved == null || !jwt.equals(resolved.token())) {
                    // JwtTokenProvider#resolveToken is guaranteed to return a non-null
                    // ResolvedJwt (valid=false on parse failure), so no null fallback
                    // is needed here.
                    resolved = tokenProvider.resolveToken(jwt);
                    request.setAttribute(ResolvedJwt.REQUEST_ATTRIBUTE, resolved);
                }
                if (resolved.valid()) {
                    return resolved.userId();
                }
            } catch (Exception e) {
                log.debug("Silent failure parsing Bearer token for rate limiting user identification: {}",
                        e.getMessage());
            }
        }
        return null;
    }
}
