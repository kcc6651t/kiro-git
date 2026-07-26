package com.company.filepreview.file.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Agent health snapshot.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentHealth {
    private String serverId;
    /** UP | DOWN */
    private String status;
    private String version;
    private String error;
}
