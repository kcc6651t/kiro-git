package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Result of a file preview.
 *
 * <p>The Agent returns raw bytes as base64 to avoid corrupting JSON with binary
 * content or mixed encodings. The central service decodes using the requested
 * encoding and returns text to the frontend.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FilePreview {
    private String path;
    private String realPath;
    private String mimeType;
    private String encoding;
    private long size;
    private boolean truncated;
    /** number of lines returned in head mode */
    private int returnedLines;
    /** number of bytes returned by the agent */
    private int bytesReturned;
    /** 1-based line number of the first returned line (lines mode; 1 for head, 0 for range) */
    private int startLine;
    /** byte offset of the first returned byte; startOffset+bytesReturned is the next range offset */
    private long startOffset;
    /** raw bytes as returned by the agent (base64) */
    private String contentBase64;
    /** decoded text, populated by the central service */
    private String content;
    /** true when the agent flagged the content as binary */
    private boolean binary;
}
