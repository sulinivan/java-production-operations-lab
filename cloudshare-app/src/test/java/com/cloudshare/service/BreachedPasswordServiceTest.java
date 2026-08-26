package com.cloudshare.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BreachedPasswordServiceTest {

    private BreachedPasswordService breachedPasswordService;
    private HttpClient mockHttpClient;
    private HttpResponse<String> mockHttpResponse;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        breachedPasswordService = new BreachedPasswordService();
        mockHttpClient = mock(HttpClient.class);
        mockHttpResponse = mock(HttpResponse.class);
        breachedPasswordService.setHttpClient(mockHttpClient);
        
        // Use ReflectionTestUtils to inject values normally injected by @Value
        ReflectionTestUtils.setField(breachedPasswordService, "enabled", true);
        ReflectionTestUtils.setField(breachedPasswordService, "timeoutMs", 3000);
        
        breachedPasswordService.init();
    }

    @Test
    void testIsBreached_Disabled() {
        ReflectionTestUtils.setField(breachedPasswordService, "enabled", false);
        
        // Even if the password is well known (like "password123"), it should return false
        assertFalse(breachedPasswordService.isBreached("password123"));
        
        // HttpClient should not be invoked
        verifyNoInteractions(mockHttpClient);
    }

    @Test
    void testIsBreached_EmptyOrNullPassword() {
        assertFalse(breachedPasswordService.isBreached(null));
        assertFalse(breachedPasswordService.isBreached(""));
        assertFalse(breachedPasswordService.isBreached("   "));
        verifyNoInteractions(mockHttpClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIsBreached_MatchFound() throws IOException, InterruptedException {
        // "password123" SHA-1: CBFDAC6008F9CAB4083784CBD1874F76618D2A97
        // Prefix: CBFDA, Suffix: C6008F9CAB4083784CBD1874F76618D2A97
        String testPassword = "password123";
        String suffix = "C6008F9CAB4083784CBD1874F76618D2A97";
        
        String responseBody = suffix + ":12345\nOTHER_SUFFIX:12\n";
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(responseBody);
        
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
                
        assertTrue(breachedPasswordService.isBreached(testPassword));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIsBreached_NoMatchFound() throws IOException, InterruptedException {
        String testPassword = "SomeVerySecurePassword2026!!!";
        
        // HIBP does not have this suffix
        String responseBody = "7A393A248A85081E0B49F517F48B29F4D08:12345\n";
        when(mockHttpResponse.statusCode()).thenReturn(200);
        when(mockHttpResponse.body()).thenReturn(responseBody);
        
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
                
        assertFalse(breachedPasswordService.isBreached(testPassword));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIsBreached_FailsOpenOnNon200Response() throws IOException, InterruptedException {
        String testPassword = "password123";
        
        when(mockHttpResponse.statusCode()).thenReturn(500);
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(mockHttpResponse);
                
        assertFalse(breachedPasswordService.isBreached(testPassword));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testIsBreached_FailsOpenOnException() throws IOException, InterruptedException {
        String testPassword = "password123";
        
        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Network Timeout"));
                
        assertFalse(breachedPasswordService.isBreached(testPassword));
    }
}
