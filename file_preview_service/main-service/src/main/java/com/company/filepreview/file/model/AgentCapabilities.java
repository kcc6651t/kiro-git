package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Capabilities advertised by an Agent so the central service can degrade features
 * gracefully when agent versions differ.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentCapabilities {
    private String serverId;
    private String version;
    private int maxPreviewBytes;
    private int maxPreviewLines;
    private int maxTailLines;
    private int maxDirectoryEntries;
    private boolean searchSupported;
    private boolean imagePreviewSupported;
}
