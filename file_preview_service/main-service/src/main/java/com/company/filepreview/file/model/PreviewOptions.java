package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for a file preview request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewOptions {
    /** head | range */
    private String mode;
    private long offset;
    private int limitBytes;
    /** max lines for head/lines mode (default 5000) */
    private int maxLines;
    /** 1-based start line for lines mode (jump to a line's context); 0 = file start */
    private int fromLine;
    private String encoding;

    public static PreviewOptions defaults() {
        return PreviewOptions.builder()
                .mode("head")
                .offset(0)
                .limitBytes(5 * 1024 * 1024)
                .maxLines(5000)
                .encoding("UTF-8")
                .build();
    }
}
