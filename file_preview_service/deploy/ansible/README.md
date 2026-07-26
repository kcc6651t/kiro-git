# 批量部署 Agent 并一键开启同步（Ansible）

用 Ansible 幂等地把 Agent 部署到多台目标服务器，并按每台角色（主机/备机）渲染 `agent.yml`、开启 `sync`、安装 systemd 并启动。

## 前置

- 控制机安装 Ansible（`pip install ansible` 或发行版包）。
- 目标机可 SSH 登录且账号具备 sudo。
- 已用 `deploy/certs/generate-certs.sh` 生成证书；将以下文件放到本目录 `certs/`：
  - 每台：`<server_id>-agent.crt`、`<server_id>-agent.key`
  - 公共：`main-ca.crt`
- 已构建 Agent 二进制：`agent/dist/file-preview-agent`（`cd agent && make build-linux`）。

## 目录

```
deploy/ansible/
├── inventory.ini                 # 主机清单
├── group_vars/all.yml            # 公共变量（中心 IP、目录、限额、sync 默认）
├── host_vars/<host>.yml          # 每台：server_id / allowed_roots / sync 开关
├── templates/agent.yml.j2        # 渲染 agent.yml（含 sync 段）
├── templates/file-preview-agent.service.j2
├── playbook-agent.yml
└── certs/                        # 放证书（不入库）
```

## 每台主机的开关（host_vars）

- **主机（源）**：无需设置任何 sync 变量（扫描/读取属只读、恒可用）
- **备机（目标）**：`sync_write_enabled: true`，并设 `sync_target_roots`（可写根目录）

参见 `host_vars/prod-app-01.yml`（主）与 `host_vars/prod-app-02.yml`（备）。

## 常用命令

```bash
cd deploy/ansible

# 全量部署（二进制 + 证书 + 配置 + systemd + 启动）
ansible-playbook -i inventory.ini playbook-agent.yml

# 一键“仅下发并开启 sync 配置”后重启（不重装二进制/证书）
ansible-playbook -i inventory.ini playbook-agent.yml --tags sync

# 只对某些备机执行
ansible-playbook -i inventory.ini playbook-agent.yml --tags sync --limit prod-app-02

# 试运行（不改动，查看差异）
ansible-playbook -i inventory.ini playbook-agent.yml --tags sync --check --diff
```

标签：`binary`（二进制）、`certs`（证书）、`config`/`sync`（agent.yml）、`service`（systemd）、`verify`（端口探测）。

## 下发后

同步的“配对关系”（哪台同步到哪台、规则、定时）在**中心** `servers.yml` 的 `backup` 段配置；编辑后在 Web「服务器管理」点“重新加载”，或重启中心服务使调度生效。随后可在「主备同步」中手动触发或等待定时执行。
