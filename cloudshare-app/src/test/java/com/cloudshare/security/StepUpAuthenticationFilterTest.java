package com.cloudshare.security;

import com.cloudshare.model.Role;
import com.cloudshare.model.User;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Collections;
import java.util.Date;
import java.util.UUID;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepUpAuthenticationFilterTest {

        @Mock
        private JwtTokenProvider tokenProvider;

        @Mock
        private HttpServletRequest request;

        @Mock
        private HttpServletResponse response;

        @Mock
        private StringRedisTemplate securityRedisTemplate;

        @Mock
        private ValueOperations<String, String> valueOperations;

        @Mock
        private FilterChain filterChain;

        @Mock
        private Claims claims;

        private StepUpAuthenticationFilter stepUpAuthenticationFilter;

        private static final long DEFAULT_SESSION_CAP_SECONDS = 900; // 15 minutes, matches application.yml default

        private UserPrincipal authenticateAsAdmin() {
                User user = User.builder()
                                .id(UUID.randomUUID())
                                .username("admin")
                                .email("admin@example.com")
                                .roles(Collections.singleton(new Role(1L, "ROLE_ADMIN")))
                                .build();
                UserPrincipal principal = new UserPrincipal(user);
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                                principal, null, principal.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authentication);
                return principal;
        }

        @BeforeEach
        void setUp() {
                stepUpAuthenticationFilter = new StepUpAuthenticationFilter(tokenProvider, securityRedisTemplate);
                SecurityContextHolder.clearContext();
        }

        @AfterEach
        void tearDown() {
                SecurityContextHolder.clearContext();
        }

        @Test
        void testNonAdminPathAllowed() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/files");

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain).doFilter(request, response);
                verify(response, never()).setStatus(401);
        }

        @Test
        void testAdminPathUnauthenticatedAllowedToProceed() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain).doFilter(request, response);
                verify(response, never()).setStatus(401);
        }

        @Test
        void testAdminPathAuthenticatedInvalidTokenBlockedWithoutRedisCall() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("invalid-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("invalid-stepup-token", principal.getId().toString()))
                                .thenReturn(null);

                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                when(response.getWriter()).thenReturn(printWriter);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain, never()).doFilter(request, response);
                verify(response).setStatus(401);
                verify(response).setContentType("application/json");
                // Verify Redis was never called for an unverified token signature/claim
                verifyNoInteractions(securityRedisTemplate);
        }

        @Test
        void testAdminPathAuthenticatedValidTokenClaimedAndAllowedOnce() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("valid-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("valid-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-123");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000)); // 60s in future
                when(claims.get("orig_iat", Long.class)).thenReturn(System.currentTimeMillis());

                when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
                when(valueOperations.setIfAbsent(eq("blacklist:token:jti-123"), eq("blacklisted"), any(Duration.class)))
                                .thenReturn(true);
                when(tokenProvider.getStepUpSessionMaxSeconds()).thenReturn(DEFAULT_SESSION_CAP_SECONDS);
                when(tokenProvider.generateStepUpToken(eq(principal.getId().toString()), eq(principal.getUsername()),
                                anyLong()))
                                .thenReturn("rotated-stepup-token");

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain).doFilter(request, response);
                verify(response, never()).setStatus(401);
        }

        @Test
        void testAdminPathSuccessfulRequestRotatesStepUpTokenWithinSessionCap() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                long origIat = System.currentTimeMillis() - Duration.ofMinutes(2).toMillis(); // 2 min into the session
                when(request.getHeader("X-StepUp-Token")).thenReturn("valid-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("valid-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-123");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
                when(claims.get("orig_iat", Long.class)).thenReturn(origIat);

                when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
                when(valueOperations.setIfAbsent(eq("blacklist:token:jti-123"), eq("blacklisted"), any(Duration.class)))
                                .thenReturn(true);
                when(tokenProvider.getStepUpSessionMaxSeconds()).thenReturn(DEFAULT_SESSION_CAP_SECONDS);
                when(tokenProvider.generateStepUpToken(principal.getId().toString(), principal.getUsername(), origIat))
                                .thenReturn("rotated-stepup-token");

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain).doFilter(request, response);
                // Successor single-use token issued in the response header, carrying the same
                // orig_iat forward
                verify(tokenProvider).generateStepUpToken(principal.getId().toString(), principal.getUsername(),
                                origIat);
                verify(response).setHeader("X-StepUp-Token", "rotated-stepup-token");
                verify(response).setHeader("Access-Control-Expose-Headers", "X-StepUp-Token");
        }

        @Test
        void testAdminPathRequestPastAbsoluteSessionCapDoesNotRotate() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                // Session originally verified further back than the absolute cap allows.
                long origIat = System.currentTimeMillis()
                                - Duration.ofSeconds(DEFAULT_SESSION_CAP_SECONDS + 30).toMillis();
                when(request.getHeader("X-StepUp-Token")).thenReturn("valid-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("valid-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-123");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
                when(claims.get("orig_iat", Long.class)).thenReturn(origIat);

                when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
                when(valueOperations.setIfAbsent(eq("blacklist:token:jti-123"), eq("blacklisted"), any(Duration.class)))
                                .thenReturn(true);
                when(tokenProvider.getStepUpSessionMaxSeconds()).thenReturn(DEFAULT_SESSION_CAP_SECONDS);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                // The presented (validly claimed) token is honored for this request...
                verify(filterChain).doFilter(request, response);
                // ...but no successor token is issued: the session has exceeded its absolute
                // lifetime,
                // so the next admin request will be forced back through a fresh MFA prompt.
                verify(tokenProvider, never()).generateStepUpToken(anyString(), anyString(), anyLong());
                verify(response, never()).setHeader(eq("X-StepUp-Token"), anyString());
        }

        @Test
        void testAdminPathAuthenticatedAlreadyClaimedTokenBlocked() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("reused-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("reused-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-123");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
                when(claims.get("orig_iat", Long.class)).thenReturn(System.currentTimeMillis());

                when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
                when(valueOperations.setIfAbsent(eq("blacklist:token:jti-123"), eq("blacklisted"), any(Duration.class)))
                                .thenReturn(false);

                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                when(response.getWriter()).thenReturn(printWriter);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain, never()).doFilter(request, response);
                verify(response).setStatus(401);
                // A rejected claim must never trigger rotation/issuance of a successor token
                verify(tokenProvider, never()).generateStepUpToken(anyString(), anyString(), anyLong());
        }

        @Test
        void testAdminPathRedisUnavailableFailsClosedWith503() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("valid-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("valid-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-123");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
                when(claims.get("orig_iat", Long.class)).thenReturn(System.currentTimeMillis());

                when(securityRedisTemplate.opsForValue()).thenReturn(valueOperations);
                when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                                .thenThrow(new RuntimeException("Redis memory limit reached (noeviction)"));

                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                when(response.getWriter()).thenReturn(printWriter);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain, never()).doFilter(request, response);
                verify(response).setStatus(503);
                verify(response).setContentType("application/json");
        }

        @Test
        void testAdminPathExpiredTokenBlockedBeforeRedisCall() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("expired-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("expired-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-expired");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() - 1000)); // Past expiration
                when(claims.get("orig_iat", Long.class)).thenReturn(System.currentTimeMillis());

                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                when(response.getWriter()).thenReturn(printWriter);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain, never()).doFilter(request, response);
                verify(response).setStatus(401);
                verifyNoInteractions(securityRedisTemplate);
        }

        @Test
        void testAdminPathMissingOrigIatClaimBlockedBeforeRedisCall() throws Exception {
                when(request.getRequestURI()).thenReturn("/api/v1/admin/users");
                UserPrincipal principal = authenticateAsAdmin();

                when(request.getHeader("X-StepUp-Token")).thenReturn("legacy-stepup-token");
                when(tokenProvider.parseAndValidateStepUpToken("legacy-stepup-token", principal.getId().toString()))
                                .thenReturn(claims);
                when(claims.getId()).thenReturn("jti-legacy");
                when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
                when(claims.get("orig_iat", Long.class)).thenReturn(null); // e.g. a pre-upgrade token shape

                StringWriter stringWriter = new StringWriter();
                PrintWriter printWriter = new PrintWriter(stringWriter);
                when(response.getWriter()).thenReturn(printWriter);

                stepUpAuthenticationFilter.doFilterInternal(request, response, filterChain);

                verify(filterChain, never()).doFilter(request, response);
                verify(response).setStatus(401);
                verifyNoInteractions(securityRedisTemplate);
        }
}
