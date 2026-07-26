package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * File or directory metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteFileMeta {
    private String path;
    private String realPath;
    private String type;
    private long size;
    private String mode;
    private String owner;
    private String group;
    private String modifiedAt;
    private String mimeType;
    private boolean readable;
    private boolean previewable;
}
