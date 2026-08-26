package com.cloudshare.security;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Request wrapper that eagerly reads and buffers the request body into memory so
 * it can be inspected early in the filter chain (e.g. to extract a login
 * identifier for per-account rate limiting) while still being fully readable by
 * downstream consumers such as Spring MVC's {@code @RequestBody} message
 * converters.
 * <p>
 * <b>Why this is scoped narrowly:</b> Only used for the small, size-bounded
 * {@code /api/v1/auth/login} JSON body — never for multipart file uploads,
 * which are excluded to avoid buffering large payloads in memory.
 * </p>
 */
public class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

    private final byte[] cachedBody;

    public CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
        super(request);
        this.cachedBody = request.getInputStream().readAllBytes();
    }

    @Override
    public ServletInputStream getInputStream() {
        return new CachedBodyServletInputStream(cachedBody);
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(cachedBody), StandardCharsets.UTF_8));
    }

    private static class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream buffer;

        CachedBodyServletInputStream(byte[] body) {
            this.buffer = new ByteArrayInputStream(body);
        }

        @Override
        public boolean isFinished() {
            return buffer.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener readListener) {
            // No-op: fully buffered in memory, nothing to signal asynchronously.
        }

        @Override
        public int read() {
            return buffer.read();
        }
    }
}
