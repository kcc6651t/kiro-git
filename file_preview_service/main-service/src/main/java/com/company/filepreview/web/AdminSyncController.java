package com.company.filepreview.web;

import com.company.filepreview.auth.CurrentUser;
import com.company.filepreview.server.ServerDefinition;
import com.company.filepreview.sync.SyncJob;
import com.company.filepreview.sync.SyncService;
import lombok.Data;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 主备同步管理（ADMIN）。展示备机配置与统计、查询历史任务、手动触发同步。
 */
@RestController
@RequestMapping("/api/admin/sync")
public class AdminSyncController {

    private final SyncService syncService;

    public AdminSyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /** 配置了备机的服务器及其同步概要 + 最近一次任务统计。 */
    @GetMapping("/servers")
    public List<BackupView> servers() {
        List<BackupView> result = new ArrayList<>();
        for (ServerDefinition s : syncService.backupServers()) {
            ServerDefinition.BackupConfig b = s.getBackup();
            BackupView v = new BackupView();
            v.setServerId(s.getId());
            v.setServerName(s.getName());
            v.setTargetServerId(b.getTargetServerId());
            v.setEnabled(b.isEnabled());
            v.setSchedule(b.getSchedule());
            v.setRunning(syncService.isRunning(s.getId()));
            v.setRules(b.getRules());
            v.setLastJob(syncService.lastJob(s.getId()));
            result.add(v);
        }
        return result;
    }

    /** 同步任务历史（分页），可按 serverId 过滤。 */
    @GetMapping("/jobs")
    public Page<SyncJob> jobs(@RequestParam(required = false) String serverId,
                              @RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "50") int size) {
        return syncService.jobs(serverId, page, size);
    }

    /** 单个任务的逐文件明细日志（按需获取，避免分页列表载荷过大）。 */
    @GetMapping("/jobs/{id}/detail")
    public Map<String, String> jobDetail(@PathVariable long id) {
        return Collections.singletonMap("detail", syncService.jobDetail(id));
    }

    /** 手动触发一次同步（异步）：立即返回 RUNNING 任务，后台执行，前端可轮询状态。 */
    @PostMapping("/{serverId}/run")
    public SyncJob run(@PathVariable String serverId) {
        return syncService.submitBackup(serverId, "MANUAL", CurrentUser.context());
    }

    @Data
    public static class BackupView {
        private String serverId;
        private String serverName;
        private String targetServerId;
        private boolean enabled;
        private String schedule;
        private boolean running;
        private List<ServerDefinition.SyncRule> rules;
        private SyncJob lastJob;
    }
}
