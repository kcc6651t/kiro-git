package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Result of a directory listing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListResult {
    private String path;
    private String realPath;
    private List<RemoteFileEntry> entries;
    private boolean hasMore;
}
