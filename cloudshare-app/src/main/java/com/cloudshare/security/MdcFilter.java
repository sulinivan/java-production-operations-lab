package com.cloudshare.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class MdcFilter extends OncePerRequestFilter {

    private final ClientIpResolver clientIpResolver;

    private static final String TRACE_ID_HEADER = "X-Trace-Id";
    private static final String MDC_TRACE_ID = "traceId";
    private static final String MDC_CLIENT_IP = "clientIp";
    private static final String MDC_HTTP_METHOD = "httpMethod";
    private static final String MDC_REQUEST_URI = "requestUri";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.trim().isEmpty()) {
            traceId = UUID.randomUUID().toString();
        }

        String clientIp = clientIpResolver.resolveIp(request);
        String httpMethod = request.getMethod();
        String requestUri = request.getRequestURI();

        MDC.put(MDC_TRACE_ID, traceId);
        MDC.put(MDC_CLIENT_IP, clientIp);
        MDC.put(MDC_HTTP_METHOD, httpMethod);
        MDC.put(MDC_REQUEST_URI, requestUri);

        response.setHeader(TRACE_ID_HEADER, traceId);

        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.clear();
        }
    }}
