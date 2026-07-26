package com.company.filepreview.auth;

/**
 * Application roles.
 *
 * <ul>
 *   <li>{@code ADMIN} - manage servers, users, global bookmarks, permissions, certs.</li>
 *   <li>{@code OPERATOR} - browse authorized servers/paths, manage personal bookmarks.</li>
 *   <li>{@code AUDITOR} - view audit logs, no file content access.</li>
 * </ul>
 */
public enum Role {
    ADMIN,
    OPERATOR,
    AUDITOR;

    public String authority() {
        return "ROLE_" + name();
    }
}
