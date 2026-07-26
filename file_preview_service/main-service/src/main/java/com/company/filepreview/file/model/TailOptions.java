package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for a log tail request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TailOptions {
    private int lines;
    private String encoding;

    public static TailOptions defaults() {
        return TailOptions.builder()
                .lines(500)
                .encoding("UTF-8")
                .build();
    }
}
