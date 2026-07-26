package com.company.filepreview.sync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 向备机写入一块内容的指令；last=true 时定稿（chmod/mtime/chown + 原子改名）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncWriteCommand {
    private String path;
    private long offset;
    private boolean last;
    private String contentBase64;
    private int mode;
    private long modTimeMs;
    private int uid;
    private int gid;
    private boolean preservePermissions;
    private boolean preserveOwnership;
}
