package com.cloudshare.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class ClientIpResolver {

    /**
     * Resolves the client's real IP address.
     * It reads the X-Real-IP header, which is set unconditionally by Nginx
     * from $remote_addr. If X-Real-IP is missing, it falls back to request.getRemoteAddr().
     *
     * @param request the HTTP servlet request
     * @return the resolved client IP address
     */
    public String resolveIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Real-IP");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        return ip;
    }
}
