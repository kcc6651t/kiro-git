package com.company.filepreview.server;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A target server definition as declared in {@code servers.yml}.
 */
@Data
public class ServerDefinition {

    private String id;
    private String name;
    private String env;
    private boolean enabled = true;
    private List<String> tags = new ArrayList<>();

    private AgentEndpoint agent = new AgentEndpoint();

    private String defaultRoot;
    private List<String> allowedRoots = new ArrayList<>();
    private List<String> deniedPaths = new ArrayList<>();
    private List<BookmarkDefinition> bookmarks = new ArrayList<>();

    /** 主备同步配置（本机作为主机，同步到备机）。 */
    private BackupConfig backup;

    @Data
    public static class AgentEndpoint {
        private String baseUrl;
        private String serverName;
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 10000;
        private String caCertRef;
        private String clientCertRef;
    }

    @Data
    public static class BookmarkDefinition {
        private String name;
        private String path;
    }

    /** 备机同步配置。 */
    @Data
    public static class BackupConfig {
        /** 备机的 serverId（必须也是 servers.yml 中已定义的一台服务器）。 */
        private String targetServerId;
        private boolean enabled = true;
        // Spring cron 表达式（6 段，秒 分 时 日 月 周）。为空则不定时，仅可手动触发。
        private String schedule;
        private List<SyncRule> rules = new ArrayList<>();
    }

    /** 单条同步规则。 */
    @Data
    public static class SyncRule {
        private String name;
        private String sourceDir;
        /** 备机目标目录，为空默认与 sourceDir 相同。 */
        private String targetDir;
        /** 文件名通配符白名单（如 *.log、*.conf），为空表示全部。 */
        private List<String> includes = new ArrayList<>();
        /** 文件名通配符黑名单（如 *.tmp）。 */
        private List<String> excludes = new ArrayList<>();
        /** 排除的目录：按目录名（如 cache）或相对 sourceDir 的路径（如 logs/archive）匹配，递归扫描时整棵子树跳过。 */
        private List<String> excludeDirs = new ArrayList<>();
        private boolean recursive = true;
        /** 同步后将备机文件权限位(mode)设为与主机一致，默认开启。 */
        private boolean preservePermissions = true;
        /** 尝试保持属主/属组一致（需备机 Agent 有相应权限，默认关闭）。 */
        private boolean preserveOwnership = false;
    }
}
