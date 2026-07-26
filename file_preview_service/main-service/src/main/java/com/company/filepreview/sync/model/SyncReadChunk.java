package com.company.filepreview.sync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 从源文件读取的一块内容。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncReadChunk {
    private String path;
    private long offset;
    private boolean eof;
    private String contentBase64;
}
