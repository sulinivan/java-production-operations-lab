package com.cloudshare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MdcFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private ClientIpResolver clientIpResolver;

    private MdcFilter mdcFilter;

    @BeforeEach
    void setUp() {
        mdcFilter = new MdcFilter(clientIpResolver);
        MDC.clear();
    }

    @Test
    void testFilterWithIncomingTraceId() throws Exception {
        when(request.getHeader("X-Trace-Id")).thenReturn("test-trace-123");
        when(clientIpResolver.resolveIp(request)).thenReturn("10.0.0.1");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/v1/files");

        doAnswer(invocation -> {
            assertEquals("test-trace-123", MDC.get("traceId"));
            assertEquals("10.0.0.1", MDC.get("clientIp"));
            assertEquals("GET", MDC.get("httpMethod"));
            assertEquals("/api/v1/files", MDC.get("requestUri"));
            return null;
        }).when(filterChain).doFilter(request, response);

        mdcFilter.doFilterInternal(request, response, filterChain);

        verify(response).setHeader("X-Trace-Id", "test-trace-123");
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("clientIp"));
    }

    @Test
    void testFilterGeneratesTraceIdIfAbsent() throws Exception {
        when(request.getHeader("X-Trace-Id")).thenReturn(null);
        when(clientIpResolver.resolveIp(request)).thenReturn("127.0.0.1");
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/auth/login");

        doAnswer(invocation -> {
            String generatedTraceId = MDC.get("traceId");
            assertNotNull(generatedTraceId);
            assertFalse(generatedTraceId.isEmpty());
            assertEquals("127.0.0.1", MDC.get("clientIp"));
            assertEquals("POST", MDC.get("httpMethod"));
            assertEquals("/api/v1/auth/login", MDC.get("requestUri"));
            return null;
        }).when(filterChain).doFilter(request, response);

        mdcFilter.doFilterInternal(request, response, filterChain);

        verify(response).setHeader(eq("X-Trace-Id"), anyString());
        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get("traceId"));
    }

    @Test
    void testMdcClearedOnException() throws Exception {
        when(request.getHeader("X-Trace-Id")).thenReturn("err-trace-id");
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/error");

        doThrow(new RuntimeException("Filter chain exception")).when(filterChain).doFilter(request, response);

        assertThrows(RuntimeException.class, () -> 
            mdcFilter.doFilterInternal(request, response, filterChain)
        );

        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("clientIp"));
    }
}
