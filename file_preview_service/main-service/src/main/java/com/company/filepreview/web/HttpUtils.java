package com.company.filepreview.web;

import javax.servlet.http.HttpServletRequest;

/**
 * Small HTTP helpers.
 */
public final class HttpUtils {

    private HttpUtils() {
    }

    /**
     * Best-effort client IP. Honors X-Forwarded-For when the app sits behind a
     * trusted reverse proxy (see README for proxy hardening notes).
     */
    public static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.trim().isEmpty()) {
            int comma = xff.indexOf(',');
            return (comma > 0 ? xff.substring(0, comma) : xff).trim();
        }
        return request.getRemoteAddr();
    }
}
