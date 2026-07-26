package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for a directory listing request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ListOptions {
    private boolean showHidden;
    /** name | size | modified | type */
    private String sortBy;
    /** asc | desc */
    private String sortOrder;
    private int offset;
    private int limit;

    public static ListOptions defaults() {
        return ListOptions.builder()
                .showHidden(false)
                .sortBy("name")
                .sortOrder("asc")
                .offset(0)
                .limit(200)
                .build();
    }
}
