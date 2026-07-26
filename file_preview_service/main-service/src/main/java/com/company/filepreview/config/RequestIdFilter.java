package com.company.filepreview.config;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * Assigns (or propagates) a {@code requestId} for every request. The id flows into
 * MDC for logging, the response header, and downstream Agent calls so central and
 * Agent audit logs can be correlated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter implements Filter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = "requestId";

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String requestId = http.getHeader(HEADER);
        if (requestId == null || requestId.trim().isEmpty()) {
            requestId = UUID.randomUUID().toString();
        }
        http.setAttribute(ATTRIBUTE, requestId);
        httpResponse.setHeader(HEADER, requestId);
        MDC.put(ATTRIBUTE, requestId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(ATTRIBUTE);
        }
    }

    public static String current(HttpServletRequest request) {
        Object rid = request.getAttribute(ATTRIBUTE);
        return rid != null ? rid.toString() : UUID.randomUUID().toString();
    }

    public static String fromAttributes(RequestAttributes attributes) {
        Object rid = attributes.getAttribute(ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        return rid != null ? rid.toString() : UUID.randomUUID().toString();
    }
}
