package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a log tail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TailResult {
    private String path;
    private String realPath;
    private String encoding;
    private long size;
    private int returnedLines;
    private boolean truncated;
    private String content;
    private String contentBase64;
}
