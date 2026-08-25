package com.cloudshare.security;

import com.cloudshare.model.User;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthenticationMdcFilterTest {

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    private AuthenticationMdcFilter authMdcFilter;

    @BeforeEach
    void setUp() {
        authMdcFilter = new AuthenticationMdcFilter();
        MDC.clear();
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        MDC.clear();
    }

    @Test
    void testFilterWithAuthenticatedUser() throws Exception {
        UUID testUserId = UUID.randomUUID();
        User user = new User();
        user.setId(testUserId);
        user.setUsername("testuser");
        user.setPasswordHash("hash");
        user.setRoles(Collections.emptySet());

        UserPrincipal principal = new UserPrincipal(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        doAnswer(invocation -> {
            assertEquals(testUserId.toString(), MDC.get("userId"));
            return null;
        }).when(filterChain).doFilter(request, response);

        authMdcFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get("userId"));
    }

    @Test
    void testFilterWithUnauthenticatedRequest() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(null);

        doAnswer(invocation -> {
            assertNull(MDC.get("userId"));
            return null;
        }).when(filterChain).doFilter(request, response);

        authMdcFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get("userId"));
    }

    @Test
    void testFilterWithAnonymousPrincipal() throws Exception {
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                "anonymousUser", null, Collections.emptyList()
        );
        SecurityContextHolder.getContext().setAuthentication(auth);

        doAnswer(invocation -> {
            assertNull(MDC.get("userId"));
            return null;
        }).when(filterChain).doFilter(request, response);

        authMdcFilter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertNull(MDC.get("userId"));
    }
}
