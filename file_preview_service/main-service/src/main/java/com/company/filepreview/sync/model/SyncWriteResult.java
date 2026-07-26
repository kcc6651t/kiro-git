package com.company.filepreview.sync.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 备机写入结果。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncWriteResult {
    private String path;
    private int bytesWritten;
    private boolean finalized;
    private boolean permissionsSet;
    private boolean ownershipSet;
    private String ownershipError;
}
