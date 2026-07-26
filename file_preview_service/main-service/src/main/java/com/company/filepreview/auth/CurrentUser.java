package com.company.filepreview.auth;

import com.company.filepreview.common.ApiException;
import com.company.filepreview.file.model.UserContext;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.Set;

/**
 * Convenience accessor for the authenticated principal.
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AppUserDetails require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getPrincipal() instanceof AppUserDetails)) {
            throw ApiException.forbidden("Not authenticated");
        }
        return (AppUserDetails) auth.getPrincipal();
    }

    public static Set<Role> roles() {
        AppUserDetails details = tryGet();
        return details != null ? details.getRoles() : Collections.emptySet();
    }

    public static UserContext context() {
        AppUserDetails details = require();
        return UserContext.builder()
                .id(String.valueOf(details.getId()))
                .name(details.getUsername())
                .build();
    }

    public static AppUserDetails tryGet() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AppUserDetails) {
            return (AppUserDetails) auth.getPrincipal();
        }
        return null;
    }
}
