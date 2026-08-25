package com.cloudshare.exception;

import com.cloudshare.dto.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.servlet.NoHandlerFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        List<ApiResponse.ValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> ApiResponse.ValidationError.builder()
                        .field(fieldError.getField())
                        .issue(fieldError.getDefaultMessage())
                        .build())
                .collect(Collectors.toList());

        ApiResponse<Void> response = ApiResponse.error(
                "VALIDATION_FAILED",
                "The request body failed to validate.",
                errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    /**
     * Handles constraint violations on {@code @RequestParam}/{@code @PathVariable}
     * method parameters (e.g. {@code @Min}/{@code @Max} on AdminController's tunable
     * limits). This is Spring Framework 6.1+'s native method-validation mechanism —
     * deliberately NOT paired with a class-level {@code @Validated}, since that would
     * route validation through the older AOP-proxy path and raise
     * {@code ConstraintViolationException} instead, which Spring's own docs recommend
     * against for controllers on this framework version.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponse<Void>> handleHandlerMethodValidationException(HandlerMethodValidationException ex) {
        List<ApiResponse.ValidationError> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String field = result.getMethodParameter().getParameterName();
            String issue = result.getResolvableErrors().stream()
                    .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid value")
                    .collect(Collectors.joining("; "));
            errors.add(ApiResponse.ValidationError.builder()
                    .field(field != null ? field : "parameter")
                    .issue(issue)
                    .build());
        });

        ApiResponse<Void> response = ApiResponse.error(
                "VALIDATION_FAILED",
                "The request parameters failed to validate.",
                errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArgumentException(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("BAD_REQUEST", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentialsException(BadCredentialsException ex) {
        ApiResponse<Void> response = ApiResponse.error("UNAUTHORIZED", "Invalid username/email or password.");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler({ AuthenticationException.class, SecurityException.class, UsernameNotFoundException.class })
    public ResponseEntity<ApiResponse<Void>> handleAuthenticationException(Exception ex) {
        log.warn("Authentication or Security violation: {}", ex.getMessage());
        // UsernameNotFoundException messages (e.g. "User not found with ID: <uuid>")
        // are useful server-side but should not be echoed to the client verbatim.
        // SecurityException messages here are intentional, reviewed user-facing
        // copy (e.g. the refresh-token-reuse "force re-authentication" notice), so
        // those are still surfaced as-is.
        String clientMessage = (ex instanceof UsernameNotFoundException)
                ? "Authentication failed."
                : ex.getMessage();
        ApiResponse<Void> response = ApiResponse.error("UNAUTHORIZED", clientMessage);
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
    }

    @ExceptionHandler(VirusDetectedException.class)
    public ResponseEntity<ApiResponse<Void>> handleVirusDetectedException(VirusDetectedException ex) {
        log.warn("Virus detected warning: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("VIRUS_DETECTED", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(response);
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnsupportedMediaTypeException(UnsupportedMediaTypeException ex) {
        log.warn("Unsupported media type: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("UNSUPPORTED_MEDIA_TYPE", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE).body(response);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResourceFoundException(NoResourceFoundException ex) {
        log.warn("Route not found: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", "The requested route or resource was not found.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandlerFoundException(NoHandlerFoundException ex) {
        log.warn("Route not found: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", "The requested route or resource was not found.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> handleMethodNotSupportedException(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        log.warn("Method not supported: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("NOT_FOUND", "The requested route or resource was not found.");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUploadSizeExceededException(
            org.springframework.web.multipart.MaxUploadSizeExceededException ex) {
        log.warn("Max upload size exceeded: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("PAYLOAD_TOO_LARGE",
                "The uploaded file exceeds the maximum allowed size of 100MB.");
        return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(response);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDeniedException(AccessDeniedException ex) {
        log.warn("Access denied: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("FORBIDDEN", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
    }

    @ExceptionHandler(ScanCapacityExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleScanCapacityExceededException(ScanCapacityExceededException ex) {
        log.warn("Scan capacity exceeded: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("SCAN_CAPACITY_EXCEEDED", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(DownloadCapacityExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleDownloadCapacityExceededException(DownloadCapacityExceededException ex) {
        log.warn("Download capacity exceeded: {}", ex.getMessage());
        ApiResponse<Void> response = ApiResponse.error("DOWNLOAD_CAPACITY_EXCEEDED", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleAllExceptions(Exception ex) {
        log.error("Unhandled system exception", ex);
        ApiResponse<Void> response = ApiResponse.error("INTERNAL_SERVER_ERROR",
                "An unexpected error occurred on the server.");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
    }
}
