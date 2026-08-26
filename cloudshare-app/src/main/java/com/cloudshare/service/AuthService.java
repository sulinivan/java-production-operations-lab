package com.cloudshare.service;

import com.cloudshare.dto.*;
import com.cloudshare.model.Role;
import com.cloudshare.model.User;
import com.cloudshare.repository.RoleRepository;
import com.cloudshare.repository.UserRepository;
import com.cloudshare.security.JwtTokenProvider;
import com.cloudshare.security.UserPrincipal;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseCookie;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import com.cloudshare.exception.ResourceNotFoundException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenService refreshTokenService;
    private final AuditLogService auditLogService;
    private final MfaService mfaService;
    private final BreachedPasswordService breachedPasswordService;

    @Value("${security.jwt.expiration-seconds:900}")
    private long jwtExpirationSeconds;

    /**
     * Registers a new user in the system.
     * Note: If the email address is already registered, the method returns normally
     * without throwing an exception to prevent account-existence enumeration
     * attacks.
     * A REGISTRATION_DUPLICATE_EMAIL_ATTEMPT event is logged to the audit log
     * instead.
     * Username collisions are still explicitly rejected as usernames are public
     * identifiers.
     */
    @Transactional
    public void registerUser(RegisterRequest request, String ipAddress) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new IllegalArgumentException("Username is already taken");
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            // Log audit event and return normally to prevent account enumeration via email
            auditLogService.log(
                    null,
                    "REGISTRATION_DUPLICATE_EMAIL_ATTEMPT",
                    null,
                    ipAddress,
                    "Duplicate registration attempt for email: " + request.getEmail());
            return;
        }

        if (breachedPasswordService.isBreached(request.getPassword())) {
            throw new IllegalArgumentException(
                    "Password has been found in a data breach. Please choose a different password.");
        }

        // Fetch ROLE_USER
        Role userRole = roleRepository.findByName("ROLE_USER")
                .orElseThrow(() -> new IllegalStateException(
                        "ROLE_USER not found in the database. Ensure schema is seeded."));

        // Create new user
        User user = User.builder()
                .username(request.getUsername())
                .email(request.getEmail())
                .passwordHash(passwordEncoder.encode(request.getPassword()))
                .roles(Collections.singleton(userRole))
                .build();

        userRepository.saveAndFlush(user);

        // Audit log registration
        auditLogService.log(
                null,
                "REGISTRATION_SUCCESS",
                null,
                ipAddress,
                "User registered with username: " + request.getUsername());
    }

    public AuthResponseAndCookie login(LoginRequest request, String ipAddress) {
        UUID tempUserId = null;
        try {
            // Look up user first to get ID for login attempt log
            User preUser = userRepository
                    .findByUsernameOrEmail(request.getUsernameOrEmail(), request.getUsernameOrEmail()).orElse(null);
            if (preUser != null) {
                tempUserId = preUser.getId();
            }

            auditLogService.log(tempUserId, "LOGIN_ATTEMPT", null, ipAddress,
                    "Attempted login for usernameOrEmail: " + request.getUsernameOrEmail());

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(request.getUsernameOrEmail(), request.getPassword()));

            UserPrincipal principal = (UserPrincipal) authentication.getPrincipal();
            UUID userId = principal.getId();

            User userEntity = userRepository.findById(userId)
                    .orElseThrow(() -> new UsernameNotFoundException("User not found"));

            if (userEntity.isMfaEnabled()) {
                if (request.getMfaCode() == null || !mfaService.verifyCode(userEntity.getId().toString(),
                        userEntity.getMfaSecret(), request.getMfaCode())) {
                    throw new org.springframework.security.authentication.BadCredentialsException(
                            "Invalid credentials or invalid MFA code.");
                }
            }

            List<String> roles = principal.getAuthorities().stream()
                    .map(auth -> auth.getAuthority())
                    .collect(Collectors.toList());

            // Generate tokens
            String accessToken = tokenProvider.generateAccessToken(userId.toString(), principal.getUsername(), roles);
            String refreshToken = refreshTokenService.createRefreshToken(userId);

            // Audit log successful login
            auditLogService.log(userId, "LOGIN_SUCCESS", null, ipAddress, "Successful login");

            AuthResponse authResponse = AuthResponse.builder()
                    .accessToken(accessToken)
                    .expiresIn(jwtExpirationSeconds)
                    .user(AuthResponse.UserDetailsDto.builder()
                            .id(userId)
                            .username(principal.getUsername())
                            .email(principal.getEmail())
                            .roles(roles)
                            .mfaRequired(userEntity.isMfaEnabled())
                            .build())
                    .build();

            ResponseCookie cookie = createHttpOnlyCookie(refreshToken, 604800); // 7 days

            return new AuthResponseAndCookie(authResponse, cookie);

        } catch (Exception ex) {
            log.warn("Login failed for user {}: {}", request.getUsernameOrEmail(), ex.getMessage());
            auditLogService.log(tempUserId, "LOGIN_FAILED", null, ipAddress,
                    "Authentication failed: " + ex.getMessage());
            throw ex;
        }
    }

    public TokenRefreshResponseAndCookie refresh(String oldRefreshToken, String ipAddress) {
        if (oldRefreshToken == null || oldRefreshToken.isEmpty()) {
            throw new IllegalArgumentException("Refresh token is missing");
        }

        // Rotate token. If reuse is detected, this will throw SecurityException and
        // revoke all user tokens.
        RefreshTokenService.TokenRotationResult rotationResult = refreshTokenService
                .rotateRefreshToken(oldRefreshToken);

        UUID userId = rotationResult.getUserId();
        String newRefreshToken = rotationResult.getNewTokenId();

        // Retrieve user
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with ID: " + userId));

        List<String> roles = user.getRoles().stream()
                .map(role -> role.getName())
                .collect(Collectors.toList());

        // Generate a new access token
        String accessToken = tokenProvider.generateAccessToken(userId.toString(), user.getUsername(), roles);

        // Audit log token refresh
        auditLogService.log(userId, "TOKEN_REFRESH", null, ipAddress, "Token refreshed successfully");

        TokenRefreshResponse refreshResponse = TokenRefreshResponse.builder()
                .accessToken(accessToken)
                .expiresIn(jwtExpirationSeconds)
                .build();

        ResponseCookie cookie = createHttpOnlyCookie(newRefreshToken, 604800);

        return new TokenRefreshResponseAndCookie(refreshResponse, cookie);
    }

    public ResponseCookie logout(String accessTokenHeader, String refreshTokenCookie, String ipAddress) {
        UUID userId = null;

        // 1. Revoke refresh token
        if (refreshTokenCookie != null && !refreshTokenCookie.isEmpty()) {
            refreshTokenService.revokeToken(refreshTokenCookie);
        }

        // 2. Blacklist access token
        if (accessTokenHeader != null && accessTokenHeader.startsWith("Bearer ")) {
            String token = accessTokenHeader.substring(7);
            if (tokenProvider.validateToken(token)) {
                try {
                    String jti = tokenProvider.getJtiFromToken(token);
                    String userIdStr = tokenProvider.getUserIdFromToken(token);
                    userId = UUID.fromString(userIdStr);

                    Date expiration = tokenProvider.getExpirationDateFromToken(token);
                    long remainingSeconds = (expiration.getTime() - System.currentTimeMillis()) / 1000;

                    if (remainingSeconds > 0) {
                        refreshTokenService.blacklistAccessToken(jti, remainingSeconds);
                    }
                } catch (Exception e) {
                    log.error("Failed to parse access token details during logout", e);
                }
            }
        }

        // Audit log successful logout
        auditLogService.log(userId, "LOGOUT_SUCCESS", null, ipAddress, "User logged out successfully");

        // Return clearing cookie (Max-Age=0)
        return createHttpOnlyCookie("", 0);
    }

    @Transactional
    public MfaSetupResponse setupMfa(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.isMfaEnabled()) {
            throw new IllegalArgumentException("MFA is already enabled for this user");
        }

        String secret = mfaService.generateSecret();
        String qrCodeUri = mfaService.generateQrCodeUri(user.getUsername(), secret);

        user.setMfaSecret(secret);
        userRepository.save(user);

        return MfaSetupResponse.builder()
                .secret(secret)
                .qrCodeDataUri(qrCodeUri)
                .build();
    }

    @Transactional
    public void verifyMfa(UUID userId, String code, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (user.isMfaEnabled()) {
            throw new IllegalArgumentException("MFA is already enabled");
        }

        if (user.getMfaSecret() == null) {
            throw new IllegalArgumentException("MFA setup has not been initialized");
        }

        if (!mfaService.verifyCode(userId.toString(), user.getMfaSecret(), code)) {
            throw new IllegalArgumentException("Invalid MFA verification code");
        }

        user.setMfaEnabled(true);
        userRepository.save(user);

        auditLogService.log(userId, "MFA_ENABLED", null, ipAddress, "MFA enabled successfully");
    }

    public MfaStepUpResponse stepUpMfa(UUID userId, String code, String ipAddress) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (!user.isMfaEnabled()) {
            throw new IllegalArgumentException("MFA is not enabled for this user");
        }

        if (!mfaService.verifyCode(userId.toString(), user.getMfaSecret(), code)) {
            // Audit trail for a failed elevated-privilege attempt — this is a
            // security-sensitive event and, like every other sensitive action in
            // this service, should not be silently un-logged.
            auditLogService.log(userId, "STEP_UP_FAILED", null, ipAddress,
                    "Invalid MFA code presented for step-up authentication");
            throw new org.springframework.security.authentication.BadCredentialsException("Invalid MFA code");
        }

        String stepUpToken = tokenProvider.generateStepUpToken(user.getId().toString(), user.getUsername());

        auditLogService.log(userId, "STEP_UP_GRANTED", null, ipAddress,
                "Step-up MFA session issued");

        return MfaStepUpResponse.builder()
                .stepUpToken(stepUpToken)
                .expiresInSeconds(300)
                .build();
    }

    public ResponseCookie createHttpOnlyCookie(String token, long maxAgeSeconds) {
        return ResponseCookie.from("refresh_token", token)
                .httpOnly(true)
                .secure(true) // HttpOnly Secure SameSite=Strict
                .sameSite("Strict")
                .path("/api/v1/auth")
                .maxAge(maxAgeSeconds)
                .build();
    }

    @RequiredArgsConstructor
    @Getter
    public static class AuthResponseAndCookie {
        private final AuthResponse authResponse;
        private final ResponseCookie cookie;
    }

    @RequiredArgsConstructor
    @Getter
    public static class TokenRefreshResponseAndCookie {
        private final TokenRefreshResponse refreshResponse;
        private final ResponseCookie cookie;
    }
}
