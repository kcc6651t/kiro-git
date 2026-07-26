package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A single directory entry returned from an Agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RemoteFileEntry {
    private String name;
    private String path;
    /** file | dir | symlink | special */
    private String type;
    private long size;
    private String mode;
    private String owner;
    private String group;
    private String modifiedAt;
    private boolean readable;
    private boolean previewable;
}
