package com.company.filepreview.sync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一个待同步文件的元信息（相对扫描目录）。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncFileInfo {
    private String relPath;
    private long size;
    /** 权限位，如 0640。 */
    private int mode;
    private long modTimeMs;
    private int uid;
    private int gid;
    private String owner;
    private String group;
}
