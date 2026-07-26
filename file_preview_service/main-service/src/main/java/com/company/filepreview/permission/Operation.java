package com.company.filepreview.permission;

/**
 * Read-only operations a user may perform against a server's files.
 *
 * <p>Write-style operations (upload/delete/edit) are deliberately not modelled.
 * When they are eventually introduced they should be added here so the same
 * permission gate covers them.</p>
 */
public enum Operation {
    LIST,
    STAT,
    PREVIEW,
    TAIL,
    SEARCH,
    DOWNLOAD
}
