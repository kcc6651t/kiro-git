# 部署手册

本手册覆盖两类节点的部署：**中心主服务**（单 Jar，含前端）与**目标服务器 Agent**（Linux 静态单文件）。

## 0. 架构与产物

```
浏览器 ──HTTPS──> 中心主服务(单 Jar, Java 8) ──HTTPS+mTLS──> 各服务器 Agent(Go 单文件) ── 只读本机文件
```

| 节点 | 产物 | 运行依赖 |
|------|------|----------|
| 中心主服务 | `linux-file-preview.jar`（前端已打包进内） | JDK 1.8 |
| Agent | `file-preview-agent`（Linux x86_64 静态） | 无（不需 Go/JRE） |

---

## 1. 构建产物

### 1.1 中心主服务单 Jar（前端自动打包进 Jar）

在开发机执行（需 JDK 1.8 + Maven，联网首次会自动下载 Node 构建前端）：

```bash
cd main-service
mvn clean package
# 产物: main-service/target/linux-file-preview.jar
```

- 前端由 `frontend-maven-plugin` 自动 `npm install && npm run build`，输出到 `src/main/resources/static`，最终打进 Jar 的 `BOOT-INF/classes/static/`。
- 已构建过前端、只想快速重打 Jar 时：`mvn clean package -Pskip-frontend`。
- 验证前端已在 Jar 内：`jar tf target/linux-file-preview.jar | grep static/index.html`。

### 1.2 Agent 二进制（Linux x86_64 静态）

在开发机执行（需 Go 1.22+）：

```bash
cd agent
make build-linux        # 产物: agent/dist/file-preview-agent
# ARM64: make build-arm64
```

产物为静态单文件，拷到目标服务器即可运行，无需安装 Go。

---

## 2. 生成 mTLS 证书（部署前必做）

在一台受控机器上执行（需 openssl）：

```bash
cd deploy/certs
./generate-certs.sh prod-app-01 10.10.1.11 prod-app-02 10.10.1.12
# 参数为若干 <serverId> <agent-ip> 组
```

产物在 `deploy/certs/out/`，分发规则：

- **中心主服务** `config/certs/`：`main-client.crt`、`main-client.key`、`agent-ca.crt`
- **每台 Agent** `config/certs/`：`<serverId>-agent.crt`→重命名 `agent-server.crt`、`<serverId>-agent.key`→`agent-server.key`、`main-ca.crt`

> 私钥务必 `chmod 600` 并归属运行账号。切勿提交进 Git（`.gitignore` 已忽略 `*.key/*.crt/*.pem`）。

---

## 3. 部署中心主服务

### 3.1 目录结构

```
/opt/file-preview-main/
├── linux-file-preview.jar
├── bin/filepreview-server.sh        # 一键启停脚本
├── config/
│   ├── application.yml
│   ├── servers.yml
│   └── certs/{main-client.crt,main-client.key,agent-ca.crt}
├── data/                            # H2 数据库（自动生成）
├── logs/                            # 应用日志
└── run/                             # PID 文件（脚本自动生成）
```

### 3.2 准备文件

```bash
sudo useradd -r -s /sbin/nologin filepreview || true
sudo mkdir -p /opt/file-preview-main/{bin,config/certs,data,logs,run}

# 拷贝产物与脚本
sudo cp target/linux-file-preview.jar        /opt/file-preview-main/
sudo cp deploy/scripts/filepreview-server.sh /opt/file-preview-main/bin/
sudo cp main-service/src/main/resources/application.yml /opt/file-preview-main/config/
sudo cp main-service/config/servers.yml      /opt/file-preview-main/config/
# 证书拷入 config/certs/

sudo chmod +x /opt/file-preview-main/bin/filepreview-server.sh
sudo chown -R filepreview:filepreview /opt/file-preview-main
sudo chmod 600 /opt/file-preview-main/config/certs/*.key
```

### 3.3 关键配置（`config/application.yml`）

- `filepreview.remote-client`：先用 `mock` 验证界面；接入真实 Agent 时改为 `agent`。
- `filepreview.agent.client-certs.main-client.{cert-file,key-file}` 与 `ca-certs.agent-ca.cert-file`：指向 `config/certs/*`。
- `filepreview.bootstrap.admin-password` 等：**首次启动前改掉默认密码**。
- 生产建议：在 Nginx 层终止 TLS（见 `deploy/nginx`），或开启 `server.ssl.*`。

`config/servers.yml` 中每台服务器的 `agent.baseUrl`、`serverName`（须与 Agent 证书 CN/SAN 一致）、`allowedRoots`、`deniedPaths` 按实际填写。

### 3.4 启动（二选一）

一键脚本：

```bash
sudo -u filepreview /opt/file-preview-main/bin/filepreview-server.sh start
# stop | restart | status 同理
```

或 systemd（模板 `deploy/systemd/file-preview-main.service`）：

```bash
sudo cp deploy/systemd/file-preview-main.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now file-preview-main
sudo systemctl status file-preview-main
```

### 3.5 验证

```bash
curl -sf http://127.0.0.1:8080/ >/dev/null && echo OK   # 首页可访问
# 浏览器打开 http://<主机>:8080 ，用 admin 登录（默认密码见 application.yml，务必已修改）
```

---

## 4. 部署 Agent（每台目标服务器）

### 4.1 目录结构

```
/opt/file-preview-agent/
├── file-preview-agent
├── bin/file-preview-agent.sh        # 一键启停脚本（非 systemd 场景）
├── config/
│   ├── agent.yml
│   └── certs/{agent-server.crt,agent-server.key,main-ca.crt}
├── logs/
└── run/
```

### 4.2 准备文件

```bash
sudo useradd -r -s /sbin/nologin filepreview || true
sudo mkdir -p /opt/file-preview-agent/{bin,config/certs,logs,run}

sudo cp dist/file-preview-agent            /opt/file-preview-agent/
sudo cp deploy/scripts/file-preview-agent.sh /opt/file-preview-agent/bin/
sudo cp agent/config/agent.yml             /opt/file-preview-agent/config/
# 证书拷入 config/certs/

sudo chmod +x /opt/file-preview-agent/file-preview-agent /opt/file-preview-agent/bin/file-preview-agent.sh
sudo chown -R filepreview:filepreview /opt/file-preview-agent
sudo chmod 600 /opt/file-preview-agent/config/certs/*.key
```

### 4.3 关键配置（`config/agent.yml`）

- `serverId`：与中心 `servers.yml` 中该服务器 id 一致。
- `tls.allowedClientSubjects`：中心客户端证书 CN（默认 `CN=file-preview-main`）。
- `security.allowedSourceCidrs`：只允许中心主服务 IP（如 `10.10.0.5/32`）。
- `security.allowedRoots` / `deniedPaths`：**最终权威**的可读/拒绝路径，务必最小化。
- `security.maxPreviewLines`(默认 5000) / `maxPreviewBytes`(5MB) / `maxTailLines` / `maxSearchScanBytes`：读取上限。

### 4.4 防火墙白名单（只放行中心主服务 IP）

见 `deploy/firewall/README.md`。iptables 示例：

```bash
iptables -A INPUT -p tcp -s 10.10.0.5 --dport 9443 -j ACCEPT
iptables -A INPUT -p tcp --dport 9443 -j DROP
```

### 4.5 启动（二选一）

一键脚本：

```bash
sudo -u filepreview /opt/file-preview-agent/bin/file-preview-agent.sh start
# stop | restart | status
```

或 systemd（推荐，模板 `deploy/systemd/file-preview-agent.service`，含 NoNewPrivileges 等加固）：

```bash
sudo cp deploy/systemd/file-preview-agent.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now file-preview-agent
sudo systemctl status file-preview-agent
```

### 4.6 验证

```bash
# 本机（缺客户端证书会被拒，但 TCP/TLS 可达即说明监听正常）
curl -k https://127.0.0.1:9443/agent/v1/health || true
# 从中心主服务侧：在 Web「服务器管理」中查看该服务器状态应为「在线」，或浏览其目录
```

---

## 5. 从 mock 切到真实 Agent

1. 确认各 Agent 已启动、防火墙已放行、证书分发正确。
2. 编辑中心 `config/application.yml`：`filepreview.remote-client: agent`。
3. 重启中心主服务：`filepreview-server.sh restart`。
4. 登录后浏览目录/预览应来自真实服务器；ADMIN 可在「服务器管理」查看在线状态与能力。

---

## 5.1 主备同步（可选）

将主机文件按规则定时/手动同步到备机，同步后保持文件权限位与主机一致。

> ⚠️ 该功能引入**写入**能力，默认关闭。仅在需要的节点显式开启，并严格限定 `allowedRoots/targetRoots`。

### 角色与开关（agent.yml 的 `sync` 段）

| 节点 | 需要开启 | 说明 |
|------|----------|------|
| 主机（源） | 无需额外开启 | 扫描/读取属只读操作，与 list/preview 同等安全，Agent 正常运行即可 |
| 备机（目标） | `sync.writeEnabled: true` | **唯一需要显式开启的开关**；`targetRoots` 限定可写根目录（缺省用 `allowedRoots`） |

备机 Agent 的运行账号需对 `targetRoots` 有写权限；若要保持属主/属组（`preserveOwnership`），Agent 需具备 `chown` 权限（通常需 root，默认关闭仅保持权限位 mode）。

### 配置备机（中心 servers.yml 的 `backup` 段）

```yaml
servers:
  - id: prod-app-01
    ...
    backup:
      targetServerId: prod-app-02        # 备机（须为已定义的一台 server）
      enabled: true
      schedule: "0 */30 * * * *"         # Spring cron(6 段)，留空则仅手动
      rules:
        - name: 应用日志与配置
          sourceDir: /data/logs
          targetDir: /data/logs          # 缺省与 sourceDir 相同
          includes: ["*.log", "*.conf"]  # 通配符文件名白名单
          excludes: ["*.tmp"]
          excludeDirs: ["archive"]       # 排除目录（目录名或相对路径，整棵子树跳过）
          recursive: true
          preservePermissions: true      # 保持权限位 mode 一致
          preserveOwnership: false        # 保持属主/属组（需备机权限）
```

### 工作机制

1. 中心扫描主机源目录（按 includes/excludes 通配符过滤文件名，excludeDirs 排除整个目录子树）。
2. 扫描备机目标目录做**增量比较**（大小 + 修改时间相同则跳过）。
3. 对需同步文件：分块从主机读、写入备机临时文件，最后一块 `chmod`(权限位) + 设置修改时间 +（可选）`chown`，再**原子改名**到目标路径。
4. 记录任务统计（总数/已同步/跳过/失败/字节/错误），中心与 Agent 各自留有审计。

### 使用

- 管理员在 Web「主备同步」中查看备机配置、最近统计，可「立即同步」，并查看同步历史。手动「立即同步」为**异步**：点击后立即返回并显示 RUNNING 与进度百分比（总数在扫描后确定，界面每 3 秒轮询），因此不受反向代理请求超时限制。历史任务点行首 + 可展开逐文件明细日志（含重试与失败原因，最多保留 500 行）。
- 单文件传输对 5xx 类瞬时错误自动重试 3 次（4xx 立即判失败）；同一规则内最多 4 个文件并行传输，海量小文件目录同步更快更稳。
- 同步历史在中心库保留 **3 天**，每小时自动清理（进行中的任务不受影响）。
- 定时任务由中心按 `schedule` 触发；修改 servers.yml 后在「服务器管理」点「重新加载」即可重建调度。
- 接口：`GET /api/admin/sync/servers`、`GET /api/admin/sync/jobs`、`GET /api/admin/sync/jobs/{id}/detail`、`POST /api/admin/sync/{serverId}/run`（均限 ADMIN）。

### 安全要点

- 备机 `writeEnabled` 仅在备机开启；`targetRoots`/`deniedPaths` 限定可写范围，写入前校验（含符号链接逃逸防护）。
- 同步链路仍走 mTLS；中心侧也会校验源/目标目录是否在各自 `allowedRoots` 内。
- 不覆盖敏感路径：`deniedPaths` 对写入同样生效。

### 批量下发并开启 sync（多台备机一键）

两种方式，二选一：

**A. Ansible（推荐，幂等）**：见 `deploy/ansible/`。为每台在 `host_vars/<host>.yml` 设角色（主机无需任何 sync 变量；备机设 `sync_write_enabled: true` 与 `sync_target_roots`），然后：

```bash
cd deploy/ansible
# 一键仅下发并开启 sync 配置后重启（不重装二进制/证书）
ansible-playbook -i inventory.ini playbook-agent.yml --tags sync
# 只对备机
ansible-playbook -i inventory.ini playbook-agent.yml --tags sync --limit prod-app-02
```

**B. 纯 SSH 脚本（无 Ansible 时）**：`deploy/scripts/rollout-sync.sh`，按 `hosts.txt`（见 `hosts.txt.example`，每行 `ip server_id role allowed_roots`）批量渲染 `agent.yml`（备机自动 `writeEnabled=true` 且 `targetRoots=allowed_roots`）、可选下发证书/二进制并重启：

```bash
cd deploy/scripts
CENTRAL_IP=10.10.0.5 CERTS_DIR=../certs/out AGENT_BIN=../../agent/dist/file-preview-agent \
  ./rollout-sync.sh hosts.txt
```

两种方式都只负责**目标机 Agent 侧**开启同步能力；同步的配对关系（谁同步到谁、规则、定时）仍在中心 `servers.yml` 的 `backup` 段配置，改后在 Web「服务器管理」点“重新加载”生效。

## 5.2 SQL 工作台（可选）

内置一个类 DBeaver 的 MySQL 工具：数据源可视化管理、库/表/字段树形浏览、多标签独立执行、结果导出 CSV。使用 **MySQL 驱动 5.1.47**（驱动类 `com.mysql.jdbc.Driver`），已随主服务打进 Jar，无需额外安装。

### 访问入口与权限

- 登录后顶部「SQL 工作台」一级菜单进入（仅 `ADMIN`/`OPERATOR` 可见）。
- 数据源的新增/编辑/删除/测试/批量导入：仅 `ADMIN`。
- 浏览元数据、执行 SQL：`ADMIN` 与 `OPERATOR`；`AUDITOR` 无权（接口返回 403）。

> ⚠️ 工作台按 DBeaver 定位，**不限制语句类型**（可执行 UPDATE/DELETE/DDL）。请只为受信任的运维人员开放 `OPERATOR`/`ADMIN`，并为数据源配置**最小权限**的数据库账号（例如只读账号）。

### 配置（`config/application.yml` 的 `sql` 段）

```yaml
sql:
  secret: "务必改成高强度随机值"   # 加密存储数据源密码的密钥口令
  default-max-rows: 1000            # 单次查询默认返回行数
  max-rows-limit: 100000            # 返回行数硬上限（前端可自定义，但不超过此值）
  query-timeout-seconds: 60         # JDBC 查询超时
  pool-size: 5                      # 每个数据源的连接池大小
```

- **密码加密**：数据源密码用 AES-256-GCM 加密后存入 H2（`data/`），密钥由 `sql.secret` 派生。**上生产前必须修改 `sql.secret`**；一旦修改，历史已存密文将无法解密，需重新录入数据源密码。
- **大结果集**：执行走 MySQL 流式游标（`setFetchSize(Integer.MIN_VALUE)`）+ `setMaxRows` 双重保护，避免把整表拉进内存；超过行数上限的结果会被标记「已截断」。

### 数据源批量导入

「数据源」面板右上「批量导入」，粘贴 JSON 数组即可（重名自动跳过）：

```json
[
  {"name":"生产-订单库","host":"10.0.0.11","port":3306,"defaultDatabase":"order","username":"ro_user","password":"***","params":"useSSL=false&characterEncoding=utf8"}
]
```

### 接口

`/api/sql/datasources`（增删改查/`import`/`{id}/test`）、`/api/sql/datasources/{id}/{databases,tables,columns}`、`POST /api/sql/datasources/{id}/execute`。

## 5.3 内存与日志留存

为长期稳定运行，已内置内存上限与日志滚动清理：

- **中心主服务**：`application.yml` 限制 H2 连接池（`spring.datasource.hikari.maximum-pool-size: 8`）；日志按天+大小滚动（单文件 50MB、保留 15 天、总量上限 500MB，超出自动清理）。systemd 模板设 `JAVA_OPTS=-Xms256m -Xmx768m -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError`。
- **Agent**：审计日志带大小滚动与数量/天数保留（`agent.yml` 的 `logging.maxSizeMb/maxBackups/maxAgeDays`，默认 50MB/10 个/15 天）；systemd 模板设 `GOMEMLIMIT=256MiB`、`GOGC=50`、`MemoryMax=384M`。
- 若用一键脚本而非 systemd 运行，可在启动脚本或环境变量中自行注入上述 `JAVA_OPTS`/`GOMEMLIMIT`。

## 6. 升级与回滚

- **中心主服务**：`stop` → 备份并替换 `linux-file-preview.jar` → `start`。数据在 `data/`，配置在 `config/`，不随 Jar 覆盖。
- **Agent**：`stop` → 替换 `file-preview-agent` 二进制 → `start`。Agent 暴露 `--version` 与 `/capabilities`，中心据 capabilities 做功能降级，支持灰度升级。
- 回滚：还原上一版本产物后重启即可。

---

## 7. 常见问题排查

| 现象 | 排查方向 |
|------|----------|
| 中心启动失败 | `logs/console.out`、`logs/app.log`；确认 JDK 1.8、`config/application.yml` 路径 |
| 界面能开但服务器全部离线 | `remote-client` 是否为 `agent`；证书路径/权限；Agent 防火墙；`serverName` 与证书 CN/SAN 是否一致 |
| 调用 Agent 报证书错误 | 中心 `main-client.*` 与 Agent `main-ca.crt` 是否同一 CA；Agent `agent-server.*` 与中心 `agent-ca.crt` 是否匹配；证书是否过期 |
| 403 拒绝访问路径 | 目标路径需同时在中心 `servers.yml` 与 Agent `agent.yml` 的 `allowedRoots` 内，且不命中 `deniedPaths` |
| 大文件预览慢 | 预览默认只加载前 300 行，向下滚动按 256KB 字节窗口动态续读；GB 级文件也能秒开 |
| SQL 工作台连接失败 | 数据源主机/端口/账号密码；`params` 中 `useSSL`/时区；目标库网络是否放行主服务出口 IP |
| SQL 数据源密码全部失效 | 是否改过 `sql.secret`？改密钥会使旧密文无法解密，需重新录入密码 |
| Agent 拒绝来源 | `agent.yml` 的 `allowedSourceCidrs` 是否包含中心主服务出口 IP |
