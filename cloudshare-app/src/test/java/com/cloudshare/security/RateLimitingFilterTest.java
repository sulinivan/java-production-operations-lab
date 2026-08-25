package com.cloudshare.security;

import com.cloudshare.service.RateLimiterService;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RateLimitingFilterTest {

    @Mock
    private RateLimiterService rateLimiterService;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private HttpServletRequest request;

    @Mock
    private ClientIpResolver clientIpResolver;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private RateLimitingFilter rateLimitingFilter;

    @BeforeEach
    void setUp() {
        rateLimitingFilter = new RateLimitingFilter(rateLimiterService, tokenProvider, clientIpResolver, objectMapper);
    }

    @Test
    void testFilterAllowed() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void testFilterBlocked() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        when(rateLimiterService.isAllowed(anyString(), anyInt(), anyInt())).thenReturn(false);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(429);
        verify(response).setContentType("application/json");
    }

    @Test
    void testFilterMfaVerifyAllowed() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "mfaLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/mfa/verify");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        when(rateLimiterService.isAllowed(eq("limit:127.0.0.1:/api/v1/auth/mfa/verify"), eq(60), eq(5)))
                .thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void testFilterMfaStepUpBlocked() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "mfaLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/mfa/step-up");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        when(rateLimiterService.isAllowed(eq("limit:127.0.0.1:/api/v1/auth/mfa/step-up"), eq(60), eq(5)))
                .thenReturn(false);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(429);
    }

    @Test
    void testFilterMfaVerifyWithUserId() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "mfaLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/mfa/verify");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        when(request.getHeader("Authorization")).thenReturn("Bearer valid-jwt");
        when(request.getAttribute(ResolvedJwt.REQUEST_ATTRIBUTE)).thenReturn(null);
        ResolvedJwt resolved = new ResolvedJwt("valid-jwt", true, "user-123", "access", "jti-123");
        when(tokenProvider.resolveToken("valid-jwt")).thenReturn(resolved);

        when(rateLimiterService.isAllowed(eq("limit:user-123:/api/v1/auth/mfa/verify"), eq(60), eq(5)))
                .thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
    }

    @Test
    void testPublicLinkRateLimiting_PerLinkBlocked_AllowsOtherLinks() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "linkLimit", 30);
        ReflectionTestUtils.setField(rateLimitingFilter, "linkGlobalLimit", 100);

        // Setup request for link A
        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/linkA");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        // Mock RateLimiterService: linkA key is blocked (returns false), global key is
        // allowed (returns true)
        when(rateLimiterService.isAllowed(eq("limit:link:linkA:127.0.0.1"), eq(60), eq(30))).thenReturn(false);
        when(rateLimiterService.isAllowed(eq("limit:linkglobal:127.0.0.1"), eq(60), eq(100))).thenReturn(true);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        // Execute filter
        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        // Verify link A request is blocked (429)
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(429);

        // Reset mocks for request B testing
        reset(request, response, filterChain, rateLimiterService);

        // Setup request for link B (same IP)
        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/linkB");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        // Mock RateLimiterService: linkB key is allowed, global key is allowed
        when(rateLimiterService.isAllowed(eq("limit:link:linkB:127.0.0.1"), eq(60), eq(30))).thenReturn(true);
        when(rateLimiterService.isAllowed(eq("limit:linkglobal:127.0.0.1"), eq(60), eq(100))).thenReturn(true);

        // Execute filter
        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        // Verify link B request is allowed (goes to next filter)
        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void testPublicLinkRateLimiting_GlobalBlocked_BlocksAllLinks() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "linkLimit", 30);
        ReflectionTestUtils.setField(rateLimitingFilter, "linkGlobalLimit", 100);

        // Setup request for link A
        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/linkA");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        // Mock RateLimiterService: linkA is allowed (true), but global is blocked
        // (false)
        when(rateLimiterService.isAllowed(eq("limit:link:linkA:127.0.0.1"), eq(60), eq(30))).thenReturn(true);
        when(rateLimiterService.isAllowed(eq("limit:linkglobal:127.0.0.1"), eq(60), eq(100))).thenReturn(false);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        // Execute filter
        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        // Verify request is blocked (429)
        verify(filterChain, never()).doFilter(request, response);
        verify(response).setStatus(429);
    }

    @Test
    void testPublicLinkInfoRateLimiting_SharesBucketWithDownload() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "linkLimit", 30);
        ReflectionTestUtils.setField(rateLimitingFilter, "linkGlobalLimit", 100);

        // Setup request for link info (GET /api/v1/shares/link/linkA/info)
        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/linkA/info");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        // Verify that the rate-limiter evaluates the SAME key
        // ("limit:link:linkA:127.0.0.1")
        // and global key as the standard download route.
        when(rateLimiterService.isAllowed(eq("limit:link:linkA:127.0.0.1"), eq(60), eq(30))).thenReturn(true);
        when(rateLimiterService.isAllowed(eq("limit:linkglobal:127.0.0.1"), eq(60), eq(100))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void testPopulatesResolvedJwtAttribute() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "uploadLimit", 10);
        when(request.getRequestURI()).thenReturn("/api/v1/files/upload");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        String token = "valid-token";
        ResolvedJwt resolved = new ResolvedJwt(token, true, "user-456", "access", "jti-123");

        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(tokenProvider.resolveToken(token)).thenReturn(resolved);
        when(rateLimiterService.isAllowed(eq("limit:user-456:/api/v1/files/upload"), eq(60), eq(10))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(request).setAttribute(eq(ResolvedJwt.REQUEST_ATTRIBUTE), eq(resolved));
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void publicLinkPath_normalShareCode_extractsCorrectly() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "linkLimit", 30);
        ReflectionTestUtils.setField(rateLimitingFilter, "linkGlobalLimit", 100);

        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/linkXYZ");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("192.168.1.50");

        when(rateLimiterService.isAllowed(eq("limit:link:linkXYZ:192.168.1.50"), eq(60), eq(30))).thenReturn(true);
        when(rateLimiterService.isAllowed(eq("limit:linkglobal:192.168.1.50"), eq(60), eq(100))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    @Test
    void publicLinkPath_prefixWithNoTrailingCode_doesNotThrowAndFallsThroughGracefully() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "generalLimit", 100);
        when(request.getRequestURI()).thenReturn("/api/v1/shares/link/");
        when(request.getMethod()).thenReturn("GET");
        when(clientIpResolver.resolveIp(request)).thenReturn("192.168.1.50");

        // Should fall through to general rate limiting (avoiding 500
        // StringIndexOutOfBoundsException)
        when(rateLimiterService.isAllowed(eq("limit:general:192.168.1.50"), eq(60), eq(100))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        verify(response, never()).setStatus(429);
    }

    private ServletInputStream createServletInputStream(String content) {
        ByteArrayInputStream bais = new ByteArrayInputStream(content.getBytes());
        return new ServletInputStream() {
            @Override
            public boolean isFinished() {
                return bais.available() == 0;
            }

            @Override
            public boolean isReady() {
                return true;
            }

            @Override
            public void setReadListener(ReadListener listener) {
            }

            @Override
            public int read() {
                return bais.read();
            }
        };
    }

    @Test
    void testFilterLogin_AccountAllowed() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "authLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        String jsonBody = "{\"usernameOrEmail\":\"testuser@example.com\"}";
        ServletInputStream servletInputStream = createServletInputStream(jsonBody);
        when(request.getInputStream()).thenReturn(servletInputStream);

        when(rateLimiterService.isAllowed(eq("limit:127.0.0.1:/api/v1/auth/login"), eq(60), eq(5))).thenReturn(true);
        when(rateLimiterService.isAllowed(startsWith("limit:acct:"), eq(60), eq(15))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(any(HttpServletRequest.class), eq(response));
        verify(response, never()).setStatus(429);
    }

    @Test
    void testFilterLogin_AccountBlocked() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "authLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        String jsonBody = "{\"usernameOrEmail\":\"testuser@example.com\"}";
        ServletInputStream servletInputStream = createServletInputStream(jsonBody);
        when(request.getInputStream()).thenReturn(servletInputStream);

        when(rateLimiterService.isAllowed(eq("limit:127.0.0.1:/api/v1/auth/login"), eq(60), eq(5))).thenReturn(true);
        when(rateLimiterService.isAllowed(startsWith("limit:acct:"), eq(60), eq(15))).thenReturn(false);

        StringWriter stringWriter = new StringWriter();
        PrintWriter printWriter = new PrintWriter(stringWriter);
        when(response.getWriter()).thenReturn(printWriter);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain, never()).doFilter(any(), any());
        verify(response).setStatus(429);
    }

    @Test
    void testFilterLogin_MalformedJson_FailsOpenOnAccount() throws Exception {
        ReflectionTestUtils.setField(rateLimitingFilter, "authLimit", 5);
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(request.getMethod()).thenReturn("POST");
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");

        String jsonBody = "invalid json";
        ServletInputStream servletInputStream = createServletInputStream(jsonBody);
        when(request.getInputStream()).thenReturn(servletInputStream);

        when(rateLimiterService.isAllowed(eq("limit:127.0.0.1:/api/v1/auth/login"), eq(60), eq(5))).thenReturn(true);

        rateLimitingFilter.doFilterInternal(request, response, filterChain);

        verify(rateLimiterService, never()).isAllowed(startsWith("limit:acct:"), anyInt(), anyInt());
        verify(filterChain).doFilter(any(HttpServletRequest.class), eq(response));
        verify(response, never()).setStatus(429);
    }
}
