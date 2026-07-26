package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Options for an in-file search.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchOptions {
    private String query;
    /**
     * 非 UTF-8 编码文件的原始字节查询串（base64）：由中心把 query 按 encoding
     * 编码后填充，Agent 按字节匹配并以 lineBase64 返回命中行。仅非 UTF-8 时下发。
     */
    private String queryBase64;
    private boolean caseSensitive;
    private int maxResults;
    private long maxScanBytes;
    private String encoding;

    public static SearchOptions defaults(String query) {
        return SearchOptions.builder()
                .query(query)
                .caseSensitive(false)
                .maxResults(200)
                .maxScanBytes(64L * 1024 * 1024)
                .encoding("UTF-8")
                .build();
    }
}
