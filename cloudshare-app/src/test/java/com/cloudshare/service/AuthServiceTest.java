package com.cloudshare.service;

import com.cloudshare.dto.RegisterRequest;
import com.cloudshare.model.Role;
import com.cloudshare.model.User;
import com.cloudshare.repository.RoleRepository;
import com.cloudshare.repository.UserRepository;
import com.cloudshare.security.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private AuthenticationManager authenticationManager;

    @Mock
    private UserRepository userRepository;

    @Mock
    private RoleRepository roleRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenProvider tokenProvider;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private MfaService mfaService;

    @Mock
    private BreachedPasswordService breachedPasswordService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                authenticationManager,
                userRepository,
                roleRepository,
                passwordEncoder,
                tokenProvider,
                refreshTokenService,
                auditLogService,
                mfaService,
                breachedPasswordService
        );
    }

    @Test
    void testRegisterUser_Success() {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "strongpassword123");
        String ipAddress = "192.168.1.1";

        when(userRepository.existsByUsername(request.getUsername())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(breachedPasswordService.isBreached(request.getPassword())).thenReturn(false);

        Role userRole = Role.builder().id(1L).name("ROLE_USER").build();
        when(roleRepository.findByName("ROLE_USER")).thenReturn(Optional.of(userRole));
        when(passwordEncoder.encode(request.getPassword())).thenReturn("encodedPassword");

        assertDoesNotThrow(() -> authService.registerUser(request, ipAddress));

        verify(userRepository, times(1)).saveAndFlush(any(User.class));
        verify(auditLogService, times(1)).log(
                isNull(),
                eq("REGISTRATION_SUCCESS"),
                isNull(),
                eq(ipAddress),
                contains("testuser")
        );
    }

    @Test
    void testRegisterUser_UsernameTaken_ThrowsException() {
        RegisterRequest request = new RegisterRequest("takenuser", "test@example.com", "strongpassword123");
        String ipAddress = "192.168.1.1";

        when(userRepository.existsByUsername(request.getUsername())).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.registerUser(request, ipAddress)
        );
        assertEquals("Username is already taken", exception.getMessage());

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }

    @Test
    void testRegisterUser_EmailTaken_LogsAndReturnsNormally() {
        RegisterRequest request = new RegisterRequest("testuser", "taken@example.com", "strongpassword123");
        String ipAddress = "192.168.1.1";

        when(userRepository.existsByUsername(request.getUsername())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertDoesNotThrow(() -> authService.registerUser(request, ipAddress));

        // Confirm it did not save user to the database
        verify(userRepository, never()).saveAndFlush(any(User.class));

        // Confirm the duplicate email event is logged
        verify(auditLogService, times(1)).log(
                isNull(),
                eq("REGISTRATION_DUPLICATE_EMAIL_ATTEMPT"),
                isNull(),
                eq(ipAddress),
                contains("taken@example.com")
        );

        // Confirm no other services are called after the early return
        verify(breachedPasswordService, never()).isBreached(anyString());
        verify(roleRepository, never()).findByName(anyString());
    }

    @Test
    void testRegisterUser_BreachedPassword_ThrowsException() {
        RegisterRequest request = new RegisterRequest("testuser", "test@example.com", "pwnedpassword");
        String ipAddress = "192.168.1.1";

        when(userRepository.existsByUsername(request.getUsername())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(breachedPasswordService.isBreached(request.getPassword())).thenReturn(true);

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> authService.registerUser(request, ipAddress)
        );
        assertTrue(exception.getMessage().contains("breach"));

        verify(userRepository, never()).saveAndFlush(any(User.class));
        verify(auditLogService, never()).log(any(), any(), any(), any(), any());
    }
}
