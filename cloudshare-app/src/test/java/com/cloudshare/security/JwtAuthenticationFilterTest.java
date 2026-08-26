package com.cloudshare.security;

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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private CustomUserDetailsService customUserDetailsService;

    @Mock
    private StringRedisTemplate securityRedisTemplate;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private UserDetails userDetails;

    private JwtAuthenticationFilter jwtAuthenticationFilter;

    @BeforeEach
    void setUp() {
        jwtAuthenticationFilter = new JwtAuthenticationFilter(tokenProvider, customUserDetailsService,
                securityRedisTemplate);
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void testNoTokenAllowsFilterToProceed() throws Exception {
        when(request.getHeader("Authorization")).thenReturn(null);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void testValidAccessTokenSucceeds() throws Exception {
        String token = "valid-access-token";
        UUID userId = UUID.randomUUID();

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(null);
        when(tokenProvider.resolveToken(token)).thenReturn(new ResolvedJwt(token, true, userId.toString(), "access", "jti-123"));
        when(securityRedisTemplate.hasKey("blacklist:token:jti-123")).thenReturn(false);
        when(customUserDetailsService.loadUserById(userId)).thenReturn(userDetails);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(customUserDetailsService).loadUserById(userId);
    }

    @Test
    void testStepUpTokenAsBearerTokenRejected() throws Exception {
        String token = "valid-stepup-token-presented-as-bearer";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(null);
        when(tokenProvider.resolveToken(token)).thenReturn(new ResolvedJwt(token, true, null, "step_up", null));

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
        verify(customUserDetailsService, never()).loadUserById(any());
    }

    @Test
    void testBlacklistedAccessTokenBlocked() throws Exception {
        String token = "blacklisted-access-token";

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(null);
        when(tokenProvider.resolveToken(token)).thenReturn(new ResolvedJwt(token, true, null, "access", "jti-123"));
        when(securityRedisTemplate.hasKey("blacklist:token:jti-123")).thenReturn(true);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(401);
        verify(response).setContentType("application/json");
    }

    @Test
    void testUsesCachedResolvedJwtAttributeWithoutCallingTokenProvider() throws Exception {
        String token = "cached-access-token";
        UUID userId = UUID.randomUUID();
        ResolvedJwt cachedJwt = new ResolvedJwt(token, true, userId.toString(), "access", "jti-999");

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(cachedJwt);
        when(securityRedisTemplate.hasKey("blacklist:token:jti-999")).thenReturn(false);
        when(customUserDetailsService.loadUserById(userId)).thenReturn(userDetails);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(customUserDetailsService).loadUserById(userId);

        // Verify tokenProvider methods were NEVER invoked because the cached attribute
        // was used
        verify(tokenProvider, never()).resolveToken(anyString());
        verify(tokenProvider, never()).validateToken(anyString());
    }

    @Test
    void testFallbackWhenCachedAttributeIsMissing() throws Exception {
        String token = "uncached-access-token";
        UUID userId = UUID.randomUUID();
        ResolvedJwt resolved = new ResolvedJwt(token, true, userId.toString(), "access", "jti-888");

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(null);
        when(tokenProvider.resolveToken(token)).thenReturn(resolved);
        when(securityRedisTemplate.hasKey("blacklist:token:jti-888")).thenReturn(false);
        when(customUserDetailsService.loadUserById(userId)).thenReturn(userDetails);

        jwtAuthenticationFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        verify(tokenProvider).resolveToken(token);
    }
}
