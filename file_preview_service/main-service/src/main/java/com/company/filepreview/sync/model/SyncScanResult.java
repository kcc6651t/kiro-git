package com.company.filepreview.sync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 源目录扫描结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncScanResult {
    private String dir;
    private String realDir;
    private List<SyncFileInfo> files;
    private boolean truncated;
}
