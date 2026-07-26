# AGENTS.md — 项目上下文入口

> 本文件是所有会话的起点：先读这里，再按"文档地图"深入。最后更新：2026-07-25（SQL 草稿升级为服务端持久化 + 15 天定期清理）。

## 1. 项目是什么

**平台运维助手**：集中式、**只读**的 Linux 服务器文件预览系统。运维人员登录 Web 界面即可切换服务器、浏览目录、预览文本、tail 日志、文件内搜索、管理书签，无需逐台登录终端。

```
浏览器 ──HTTPS──> 中心主服务 (Java 8 / Spring Boot 2.7，单 Jar 含前端)
                       │
                       └──HTTPS + mTLS──> 各服务器 Agent (Go 静态单文件，默认 :9443)
                                               └── 本机文件系统（仅 allowedRoots，只读）
```

核心约束：不保存各服务器账号密码、不用 SFTP、mTLS 从不跳过证书校验、主服务兼容 JDK 1.8。文件编辑/上传/删除/命令执行/Web 终端**有意未实现**（接口层预留扩展点）。

## 2. 构建 / 运行 / 测试

```bash
# 中心主服务（前端产物自动打进 Jar 的 static/）
cd main-service && mvn clean package            # 全量（下载 node + 构建前端 + 测试 + 打 Jar）
cd main-service && mvn clean package -Pskip-frontend   # 跳过前端，仅重打 Jar
cd main-service && mvn -Pskip-frontend test     # 后端测试（50 例）
java -jar target/linux-file-preview.jar --spring.config.location=file:./config/application.yml

# 前端单独开发与测试
cd frontend && npm install && npm run dev       # Vite :5173，/api 代理到 :8080
cd frontend && npm test                         # vitest run（47 例）

# Go Agent
cd agent && make build-linux                    # dist/file-preview-agent（linux/amd64, CGO=0 静态）
cd agent && make build-arm64 / make test / make tidy / make clean
```

产物：`main-service/target/linux-file-preview.jar`（约 40MB，含前端与 Monaco）与 `agent/dist/file-preview-agent`（linux/amd64 静态二进制）。默认账号 `admin/admin123`、`operator/operator123`（BCrypt 存储，**生产必改**）。

> **交付约定（每次必做）**：任何代码改动完成并测试通过后，必须重新构建上述两个产物——全量 `cd main-service && mvn clean package`（含前端）与 `cd agent && make build-linux`（本机无 make 时用等价命令：`CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags "-s -w -X github.com/company/file-preview-agent/internal/version.Version=1.0.0" -o dist/file-preview-agent ./cmd/file-preview-agent`），不得只交付源码。构建后抽查 jar：`unzip -l main-service/target/linux-file-preview.jar | grep index.html` 必须能匹配到 `BOOT-INF/classes/static/index.html`。
>
> **打包防白屏**：根因是 **vite/maven 复制竞态**——Windows 上 frontend-maven-plugin 的 npm 进程链会在 vite 写完产物之前就退出（已轮询证实 index.html/monaco 在 default-resources 开始后才落盘），default-resources 可能复制到缺失/0 字节的静态文件；且 resources 插件仅在源更新时覆盖（目标时间戳被设为源时间戳），坏的首次复制可能一路带进 jar（曾产出 0 字节 JS 的白屏 jar、缺 index.html 的 404 jar）。防线：`prepare-package` 与 `package`（repackage 前，resources 插件须声明在 spring-boot 插件之前）各重拷一次（护 `spring-boot:run`）；repackage 后用 `build/overlay-static.ps1`（PowerShell + .NET ZipArchive）**就地覆盖 jar 内 static 条目**——不触碰其它条目、嵌套依赖保持 STORED——再由 antrun 把 jar 内 static 整树解出与 `src/main/resources/static` 逐文件内容比对（ant `different` 选择器），不一致即 BUILD FAILURE。**教训**：①不要用 ant `<jar update>`/`<zip>` 全量重写可执行 jar——会把嵌套依赖重压缩，破坏 Spring Boot 嵌套 jar 的 STORED 布局（启动报 `nested jar files must be stored without compression`）；②只按文件名/数量校验不可靠，必须按内容校验；③overlay 脚本注释须用英文（PowerShell 5.1 对无 BOM 的 UTF-8 中文注释解析失败）；④交付 jar 前做一次 `java -jar` 启动 + 拉取 JS 实测（见会话记录的做法）。

## 3. 目录结构与模块职责

```
file_preview_service/
├── main-service/        中心主服务（Maven, Spring Boot 2.7.18, Java 8）
│   ├── config/servers.yml                 目标服务器配置示例（热加载的权威来源）
│   └── src/main/java/com/company/filepreview/
│       ├── agent/       mTLS 调 Agent：AgentRemoteFileClient、MtlsHttpClientFactory、PemUtils
│       ├── audit/       audit_event 实体 + 审计服务
│       ├── auth/        本地用户（app_user/user_role）、UserDetailsService、CurrentUser
│       ├── bookmark/    书签（personal/team/global）
│       ├── common/      ApiError/ApiException/GlobalExceptionHandler
│       ├── config/      SecurityConfig、RequestIdFilter、DataInitializer、WebConfig
│       ├── file/        RemoteFileClient 接口 + FileBrowseService 编排 + Mock 实现 + model/ 13 个 DTO
│       ├── permission/  Operation 枚举、PermissionService、PathAuthorizer（中心侧路径校验）
│       ├── server/      ServerRegistry（servers.yml 加载/热加载/读写）
│       ├── sql/         SQL 工作台（数据源/元数据/执行/密码加密）
│       ├── sync/        主备同步（SyncService/SyncScheduler/SyncClient，4MB 分块）
│       └── web/         全部 @RestController + SpaForwardController
├── frontend/            React 18 + TS + Vite 5 + antd 5 + Monaco（扁平结构）
│   ├── vite.config.ts   /api 代理；build 直出到 main-service 的 static/；拷贝 Monaco 同源部署
│   └── src/             main.tsx / App.tsx(477行,全部页面状态) / api.ts / types.ts / theme.ts / utils.ts / sqlDraft.ts
│       └── components/  9 个组件：LoginPage、ServerPanel、FileList、PreviewPane(核心)、PreviewTabs(标签行)、
│                        AdminServers、SyncManager、UserManager、SqlWorkbench(694行,最大)
├── agent/               Go Agent（go 1.22，仅依赖 yaml.v3，标准库 net/http + crypto/tls）
│   ├── cmd/file-preview-agent/main.go   入口（flag → config → audit → server）
│   ├── config/agent.yml                 示例配置
│   └── internal/
│       ├── config/      YAML 加载 + applyDefaults + validate
│       ├── server/      路由、mTLS、secured 包装器（认证+审计）、错误码→HTTP 映射
│       ├── auth/        来源 IP CIDR / 客户端证书 CN / X-Agent-Token 三重检查
│       ├── fileops/     核心：PathGuard（路径权威校验）、List/Meta/Preview/Tail/Search、syncops
│       ├── audit/       JSONL 审计日志（自带大小滚动/保留清理）
│       └── version/     构建期 -X 注入版本号
└── deploy/              部署资产
    ├── DEPLOY.md        完整部署文档（构建→证书→主服务→Agent→切真实链路→sync/SQL→排错）
    ├── certs/generate-certs.sh          内部 CA + mTLS 证书签发（PKCS#8 PEM，Java 8 直读）
    ├── systemd/         主服务/Agent 单元模板（Agent 加固更全）
    ├── nginx/           反向代理示例（TLS 终止、限流注释、占位域名）
    ├── firewall/        Agent 9443 白名单（iptables/firewalld/安全组三套）
    ├── scripts/         两个一键启停脚本 + rollout-sync.sh（批量下发 sync）
    └── ansible/         扁平单 playbook（仅 Agent 部署）+ inventory + Jinja 模板，可用脚手架
```

根目录的 `*.log`（fb.log、winget-go.log、各模块 build/test 日志）是本地构建产物，已被 `.gitignore` 排除，非文档。

## 4. 技术栈与版本

| 层 | 技术 |
|---|---|
| 主服务 | JDK 1.8、Spring Boot 2.7.18（Web/Security/Validation/Data JPA）、H2 file 库（`jdbc:h2:file:./data/app`）、mysql-connector-java 5.1.47（SQL 工作台）、jackson-dataformat-yaml、OkHttp 3.12.13（mTLS，Java 8 兼容的最后一线）、Lombok |
| 前端 | React 18.3、TypeScript 5.4、Vite 5.2、antd 5.17、Monaco 0.44（@monaco-editor/react，同源加载）、axios 1.7、vitest 3（仅纯函数单测）；**无** react-router / Redux / ESLint |
| Agent | Go 1.22，标准库 + gopkg.in/yaml.v3；CGO_ENABLED=0 静态编译 |
| 部署 | systemd、Nginx、openssl、Ansible（扁平 playbook）、bash |

Node 仅是构建期依赖（frontend-maven-plugin 自动下载 v20.11.1，npm 走 npmmirror 镜像）；运行期只需 JRE 8。

## 5. 核心业务域

### 5.1 数据模型（H2，`ddl-auto: update`，无 Flyway/Liquibase）

6 张表，均 `@Entity` + Lombok，snake_case 字段，IDENTITY 主键，`Instant` 时间戳：

- `app_user` + `user_role`：本地用户；角色枚举 `ADMIN / OPERATOR / AUDITOR`
- `bookmark`：`scope_type`（personal/team/global），个人书签属主校验，非 personal 仅 ADMIN 可管
- `audit_event`：中心审计（requestId、用户、IP、服务器、操作、路径、成功/错误码、双耗时、字节数）
- `sync_job`：主备同步任务统计（总数/同步/跳过/失败/字节/耗时 + `detail` 逐文件明细 @Lob，@JsonIgnore 不随列表返回）；保留 3 天，每小时定期清理（RUNNING 不删）
- `sql_data_source`：SQL 工作台数据源，密码 AES-256-GCM 加密（`enc:v1:` 前缀，密钥由 `sql.secret` SHA-256 派生）
- `sql_draft`：SQL 工作台草稿（user_id 唯一 + content JSON @Lob，256KB 上限）；按 updated_at 保留 15 天，SqlDraftScheduler 每天清理

服务器配置**不入库**，权威来源是 `config/servers.yml`（`ServerRegistry` 自研加载，jackson-yaml 解析，不走 Spring 绑定）。

### 5.2 关键业务流程

- **登录**：`AuthController` 手工 `AuthenticationManager.authenticate` → SecurityContext 存入 Session（Cookie）。未认证 API 返回 JSON 401。`DataInitializer` 首启播种默认账号。
- **文件调用链**：`FileController` → `FileBrowseService`（① 权限校验 → ② `PathAuthorizer` 中心侧路径校验 → ③ 调 `RemoteFileClient` 计时 → ④ base64 按选定编码解码 → ⑤ 成功失败均写审计）→ `AgentRemoteFileClient`（OkHttp+mTLS，POST `/agent/v1/files/*`，错误码映射 ApiException：HTTP 状态定 code 兜底，agent 的 code/message 分别入 ApiException 的 code/message）。预览三模式：`head`（前 N 行）、`range`（字节窗口，下滚续读）、`lines`（`fromLine` 行号窗口，响应带 `startLine`/`startOffset`，供文件内搜索直接跳转目标行上下文）。文件内搜索支持非 UTF-8 文件：中心把查询串按所选编码转字节后 base64 下发（`queryBase64`，仅非 UTF-8 时），Agent 按原始字节匹配（大小写折叠仅 ASCII），命中行以 `lineBase64` 回传、中心解码；GBK 中文搜索可用。
- **双实现切换**：`RemoteFileClient` 与 `SyncClient` 各有 mock/agent 两实现，`@ConditionalOnProperty(filepreview.remote-client)` 互斥装配（mock 为缺省，供前端联调）。
- **配置热加载**：ADMIN `POST /api/admin/servers/reload` 或 `PUT /config`（先校验再写盘再 load），随后 `SyncScheduler.reschedule()` 重建 cron、`MtlsHttpClientFactory.invalidateAll()` 清空 mTLS client 缓存（证书/serverName 变更下次调用懒重建）。
- **主备同步**（默认关闭）：servers.yml `backup` 段（targetServerId + Spring cron + rules[includes/excludes/excludeDirs/recursive/preserve*]）。includes/excludes 按文件 basename 通配；excludeDirs 按目录名或相对路径匹配、整棵子树剪枝（仅递归扫描生效）。源侧 `scan/read` 只读恒可用；备机侧 `write` 需 Agent `sync.writeEnabled: true` 且受 `targetRoots` 约束。增量按 size+mtime 比较，4MB 分块，备机写 `<path>.fpsync.tmp` 临时文件 + `last` 定稿（chmod/mtime/chown + 原子 rename）。同 serverId 串行防重入；规则内 4 线程并行传输（海量小文件提速），单文件 5xx 瞬时错误重试 3 次（4xx 不重试）。RUNNING 期间总数/进度节流落库（1.5s），前端轮询显示百分比；`detail` 逐文件明细（含重试/失败原因，500 行截断）经 `/jobs/{id}/detail` 按需获取；历史保留 3 天由 SyncScheduler 每小时清理。源扫描超 Agent `maxFiles` 截断时任务落 PARTIAL 并告警（不静默报 SUCCESS）；`message` 限长 4000 字符防落库失败。
- **预览多标签**（Notepad++ 式）：`PreviewTabs` 标签行 + PreviewPane 按 `serverId|path` 缓存每标签预览状态（内容/偏移/mode/编码/滚动锚点），切标签不重拉；标签按服务器隔离随 sessions 恢复。缓存字符数超 32MB 时标签行下方 Alert 提醒并可一键「关闭最旧标签」（只提醒不强制）。
- **SQL 工作台**（需求 §16.3）：数据源 CRUD/测试/JSON 批量导入（仅 ADMIN）；库→表→字段懒加载元数据与单条 SQL 执行（ADMIN+OPERATOR）；流式游标 `setFetchSize(MIN_VALUE)` + `setMaxRows(limit+1)` 双保护 + truncated 标记；**不限语句类型**，安全靠数据库账号最小权限兜底。**草稿服务端持久化**：`sql_draft` 表每用户一份（数据源选择/标签组/SQL 内容/maxRows/激活标签，400ms 防抖 + 卸载即写 `PUT /api/sql/draft`），任何机器登录自动恢复（挂载后拉取，用户已开始编辑则放弃恢复防覆盖）；`sql_draft` 按 updated_at 保留 15 天由 SqlDraftScheduler 每天清理。
- **审计双轨**：中心 `audit_event` 表 ↔ Agent JSONL 审计，靠 `requestId` 关联（响应头 `X-Request-Id`，`RequestIdFilter` 进 MDC 并透传 Agent）。**缺口**：SQL 执行、登录、用户管理、同步触发均不写审计。

### 5.3 对外接口

主服务对前端（`/api/**` 需登录，`/api/admin/**` 需 ADMIN）：

```
POST /api/auth/login|logout   GET /api/auth/me
GET  /api/servers[/{id}[/roots|/health]]
GET  /api/servers/{id}/files[?path=]  /meta  /preview?lines=&encoding=  /tail?lines=  /search?q=
GET/POST/PUT/DELETE /api/bookmarks[/{id}]
GET  /api/admin/servers…  POST /api/admin/servers/reload  PUT /api/admin/servers/config
GET  /api/admin/sync/servers|/jobs|/jobs/{id}/detail   POST /api/admin/sync/{serverId}/run
GET  /api/admin/audits  /api/admin/audits/export(CSV)
GET/POST/PUT/DELETE /api/admin/users[/{id}[/password]]
/api/sql/datasources…（CRUD/test/import/databases/tables/columns/execute）   GET/PUT /api/sql/draft（草稿，属主即当前用户）
```

Agent 内部（仅中心经 mTLS 调用；health/capabilities 无认证，files/* 与 sync/* 走 secured 链）：

```
GET  /agent/v1/health  /agent/v1/capabilities
POST /agent/v1/files/list|meta|preview|tail|search
POST /agent/v1/sync/scan|read        源侧只读，恒可用
POST /agent/v1/sync/write            备机侧，需 sync.writeEnabled
```

## 6. 配置体系（三份 YAML）

- **`main-service/src/main/resources/application.yml`**：`server.port=8080`；H2 + `ddl-auto: update`；`filepreview.servers-config`（servers.yml 位置，文件系统优先 classpath 兜底）；`filepreview.remote-client: mock|agent`；`filepreview.agent.client-certs/ca-certs`（mTLS 证书，PKCS#8 PEM）；`filepreview.bootstrap.*` 默认账号；`sql.*`（secret/default-max-rows/max-rows-limit/query-timeout/pool-size）；logback 按天+50MB 滚动。
- **`main-service/config/servers.yml`**：每台服务器 `id/name/env/tags/agent{baseUrl,serverName(证书身份 CN/SAN),超时,caCertRef,clientCertRef}/defaultRoot/allowedRoots/deniedPaths/bookmarks/backup`。
- **`agent/config/agent.yml`**：`serverId`、`listen:9443`、`tls`（服务端证书 + clientCaFile + allowedClientSubjects CN 白名单）、`security`（allowedSourceCidrs、allowedRoots、deniedPaths、rejectMultiLink、各类限额）、`sync`（writeEnabled/targetRoots/maxChunkBytes/maxFiles）、`logging`（file 与审计滚动）。**Agent 的 allowedRoots/deniedPaths 拥有最终拒绝权**。config.Load 为非严格 YAML 解析，未知键静默忽略（旧配置含已删除的 `sync.enabled`/`logging.level` 仍可加载）。

## 7. 约定与风格

**Java（main-service）**
- 包按**领域**划分（auth/file/sql/sync…），层内 web → service → repository；控制器请求/响应 DTO 多为内嵌静态类，跨层 DTO 集中在 `file/model/`、`sync/model/`。
- Lombok 用于实体/DTO（`@Data/@Getter/@Setter/@Builder`），服务类手工构造器注入；个别类（AgentClientProperties 等）手写 getter，风格不统一。
- 错误处理：成功返回裸 DTO（无统一包装）；失败抛 `ApiException`（HttpStatus + 字符串错误码）→ `GlobalExceptionHandler` → `ApiError{code,message,requestId}`。错误码是字符串字面量，**无枚举**。
- 配置绑定用 `@ConfigurationProperties`；日志 MDC 注入 requestId。

**Go（agent）**
- 标准布局 `cmd/<bin>` + `internal/<domain>`；DTO 集中在 `types.go`/`sync_types.go`，行为在 `service.go`/`syncops.go`；平台差异用 build tag 文件对（`ownership_linux.go`/`ownership_other.go`）。
- 错误用具体类型 `*PathError{Code, Message}`（稳定字符串错误码），`mapError` 集中映射 HTTP 状态（400/403/404/500）。
- 运行日志用标准库 `log`（stderr→journald）；审计独立 JSONL 自滚动。无框架、无第三方库（除 yaml）。
- 行尾不统一：既有 Go 源文件多为 CRLF，新增测试文件为 LF——Go 工具链不敏感，但 `gofmt -l` 会全仓报警，**不能用作门禁**。

**前端（frontend）**
- 基本扁平：无 pages/hooks/context 目录；纯工具函数集中在 `src/utils.ts`（humanBytes/languageForPath，组件从这里导入，勿再复制）。组件 PascalCase.tsx + 默认导出，非组件 camelCase.ts。
- 路由靠 `App.tsx` 的 `view` state 条件渲染（`display:none` 保活），无 react-router；状态纯 useState/useRef 集中在 App 容器组件，props 下发。
- CSS：全局普通 CSS + `styles/tokens.css` 的 `--fp-*` 变量（唯一设计 token 源）+ BEM 风格类名；`theme.ts` 是 antd 主题唯一入口且须与 tokens 一致。
- 后端 DTO 全部集中在 `types.ts`；API 全部集中在 `api.ts` 的单个 `api` 对象（约 40 个方法，仅具名导出）。

**注释语言不统一**：Java file/agent/permission 包与 Go 代码多为英文注释，Java sql/sync/web 与 Go config、配置文件多为中文注释。新代码跟随所在文件的既有语言。

**测试组织**
- Java：`src/test` 71 例全绿，纯单元/集成测试（JUnit + Mockito + MockWebServer + okhttp-tls 真跑 mTLS 握手），覆盖 PathAuthorizer(15)/PermissionService(7)/FileBrowseService(5)/SyncService(10)/MtlsAgentClient(2)/sql(32：CryptoService 7 + SqlDataSourceService 11 + SqlExecService 9 + SqlDraftService 5)；无 @SpringBootTest、无控制器 MockMvc 测试。测试夹具：`support/TestServers` + `src/test/resources/servers-test.yml`。
- Go：包内单元测试（auth 4 / path_guard 7+1(linux) / preview_search 14 / service 8 / multilink 2(linux) / syncops 9 / config 6 / audit 4 / server 11），无端到端测试；linux-tagged 用例在 Windows 上跳过属正常。
- 前端：vitest 3（`src/utils.test.ts` 43 例纯函数：humanBytes/languageForPath/nextFreeTabNo/filterRows/highlightParts/tabNeighbor；`src/sqlDraft.test.ts` 4 例：草稿序列化/解析/校验）；无 lint/format 配置，质量门禁为 `tsc` strict + `npm test`。

## 8. 安全模型要点（改动代码时不得削弱）

- 两层路径校验：中心 `PathAuthorizer`（规范化/allowedRoots/deniedPaths glob）→ Agent `PathGuard`（**最终权威**：绝对路径 → 去控制字符 → `filepath.Clean` → denied glob → roots 前缀 → `EvalSymlinks` → 真实路径再查 roots/denied 防 symlink 逃逸 → 拒绝特殊文件）。denied glob 两层语义一致：编译前去尾斜杠（`/root/` 有效）、`**/` 编译为 `(.*/)?` 保证段边界（`log` 只拒名为 log 的项，不误拒 `catalog`）。
- mTLS 双向：Agent `RequireAndVerifyClientCert` + CN 白名单 + 来源 IP CIDR + 可选 `X-Agent-Token`（constant-time 比较）；中心只信配置的 Agent CA + `ExpectedNameVerifier` 按 serverName 校验 CN/SAN，未配置 fail-closed。`allowedSourceCidrs` 支持裸 IP（自动按 /32、/128 归一）；既非 CIDR 又非合法 IP 的条目启动即报错（fail-closed，防笔误导致 IP 白名单静默失效）。
- Agent 不执行任何 shell，限额齐全（目录 1000 条、预览 5MB/5000 行、tail 5000 行、搜索 200 条/64MB 扫描、请求 10s、**单行 1MB**——超长行截断防内存撑爆），日志不输出文件内容。
- CSRF 当前**禁用**（SPA + 同源 Session，README 有说明）；暴露到不受信网络时应启用。
- Agent 低权限账号运行（systemd 加固：NoNewPrivileges/ProtectSystem=strict/GOMEMLIMIT 等）。

## 9. 已知坏味道 / 疑问 / 过时点（仅记录，未修改）

**全局 / 文档**
- 需求文档章节号有重复（"16. 实现进展补充"出现在 19-22 章之后），系迭代增补拼接所致。
- 前端审计查询页面**未实现**（用户明确暂缓）：角色体系有 AUDITOR、后端有 `/api/admin/audits`，但前端无审计组件、无 audit API 方法。需求中的"目录树"也未实现（实际是单级列表+面包屑）。

**main-service**
- 硬编码弱默认值：`admin123/operator123`、`sql.secret: change-this-sql-secret-in-production`、H2 `sa`/空密码。
- 过时/EOL 依赖（多为 JDK 8 兼容的刻意取舍）：Java 8、Spring Boot 2.7、mysql-connector 5.1.47（旧 `com.mysql.jdbc.Driver`，已知 CVE）、OkHttp 3.12.x（2021 停更）、H2 2.1.214。
- `ddl-auto: update` 生产风险，无迁移工具。
- SQL 工作台不限语句类型且不进审计（需求明示如此，但与"只读"定位形成反差）；审计缺口还包括登录/用户管理/同步触发。
- `Bookmark.ScopeType.team` 与 `scopeRef` 半成品（查询只处理 personal+global）；`Operation.DOWNLOAD` 仅建模无端点（有意的扩展点）。
- `SyncJob.trigger` 字段名是 H2 非保留关键字，换库有炸点风险；sync 分块写中途失败备机留半截文件（靠 Agent `last` 定稿弥补）。
- `AuditController.export` 手写 CSV、上限 500 条无提示。

**agent**
- `health`/`capabilities` 无认证、不限方法、不审计（mTLS 仍要求有效客户端证书，但信息探测面存在）。
- 错误消息把内部细节（如文件 mode）透传给客户端；TOCTOU 窗口（EvalSymlinks 与 Open 之间）对本威胁模型可接受但未在文档声明。
- 无优雅停机/配置热加载；限额默认值三处重复；svc 方法开头四连校验近乎逐字重复。
- `Meta/Readable` 硬编码 true；Tail 借用 `MaxPreviewBytes` 而非独立限额；`decode()` 传 nil ResponseWriter 依赖内部容忍。

**frontend**
- 无 axios 拦截器：同一 `catch (e:any) => message.error(...)` 模式重复约 20 次；无 401 统一处理；axios 无 timeout。
- `ENV_COLORS`、`ROLE_COLORS`、时间格式化仍各复制两份（`human()` 已收敛到 `src/utils.ts`）。
- 33 处 `any`：`columns as any`、`useRef<any>`、`catch (e: any)` 等。
- `SqlWorkbench.tsx:231` `getValueInRange(getSelection())` 在 selection 为 null 时会抛（边角）；`loadDataSources` 的 useCallback 依赖 `selectedDs` 关系脆弱。
- 无 CSRF token 机制（依赖后端同源策略）。

**deploy**
- 占位值遍布，上线前必须全换：`10.10.0.5/10.10.1.11/12`、`file-preview.internal`、`example.internal`。
- Ansible 的 `file-preview-agent.service.j2` 比静态 systemd 模板少了 `GOMEMLIMIT/GOGC/MemoryMax`——走 Ansible 部署的 Agent 反而没有内存兜底。
- 主服务堆内存不一致：启停脚本 `-Xmx512m` vs systemd 模板 `-Xmx768m`。
- `generate-certs.sh` 用法注释写冒号分隔实为空格；单 CA 签双方 + EKU 双用途（MVP 取舍）；CA 私钥不自动 `chmod 600`；证书 825 天无轮换/到期提醒。
- `rollout-sync.sh` 的 deniedPaths 在脚本与 group_vars 各硬编码一份（两处需同步维护）。

## 10. 文档地图

| 文档 | 内容 |
|---|---|
| `README.md` | 项目总览、构建运行、配置说明、API 概览、安全边界 |
| `file-preview-service-requirements.md` | 完整需求与技术方案（架构、权限模型、数据模型、测试要求、§16 迭代增补：动态加载/SQL 工作台/sync） |
| `deploy/DEPLOY.md` | 生产部署全流程（证书分发、systemd、防火墙、sync/SQL 开启、排错表） |
| `deploy/firewall/README.md`、`deploy/ansible/README.md` | 专项说明 |
