package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single matching line from an in-file search.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SearchMatch {
    private int lineNumber;
    private String line;
    /** Agent 原始字节模式（queryBase64）下返回的命中行 base64，中心按文件编码解码后填入 line。 */
    private String lineBase64;
}
