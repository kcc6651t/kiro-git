#!/usr/bin/env bash
#
# 无 Ansible 环境下，通过 SSH 批量向多台 Agent 下发并开启 sync 配置，然后重启。
# 适合已用 systemd 安装过 Agent 的场景（本脚本聚焦「渲染 agent.yml + 可选下发证书/二进制 + 重启」）。
#
# 用法:
#   ./rollout-sync.sh hosts.txt
#
# hosts.txt 每行（# 开头为注释）:
#   <ip> <server_id> <role:primary|standby> <allowed_roots 逗号分隔>
# 示例:
#   10.10.1.11 prod-app-01 primary /opt/apps,/data/logs
#   10.10.1.12 prod-app-02 standby /opt/apps,/data/logs
#
# 可用环境变量覆盖:
#   SSH_USER(默认 deploy) CENTRAL_IP CENTRAL_CN AGENT_HOME AGENT_USER AGENT_PORT
#   CERTS_DIR(本地证书目录,含 <server_id>-agent.crt/.key 与 main-ca.crt; 为空则跳过下发证书)
#   AGENT_BIN(本地二进制路径; 为空则不下发二进制)
set -euo pipefail

HOSTS_FILE="${1:-hosts.txt}"
SSH_USER="${SSH_USER:-deploy}"
CENTRAL_IP="${CENTRAL_IP:-10.10.0.5}"
CENTRAL_CN="${CENTRAL_CN:-file-preview-main}"
AGENT_HOME="${AGENT_HOME:-/opt/file-preview-agent}"
AGENT_USER="${AGENT_USER:-filepreview}"
AGENT_PORT="${AGENT_PORT:-9443}"
CERTS_DIR="${CERTS_DIR:-}"
AGENT_BIN="${AGENT_BIN:-}"

if [ ! -f "$HOSTS_FILE" ]; then
  echo "找不到主机清单: $HOSTS_FILE" >&2
  exit 1
fi

# 渲染 agent.yml 到标准输出
render_agent_yml() {
  local server_id="$1" role="$2" roots_csv="$3"
  local sync_write="false"
  if [ "$role" = "standby" ]; then
    sync_write="true"
  fi

  echo "serverId: $server_id"
  echo "listen:"
  echo "  host: 0.0.0.0"
  echo "  port: $AGENT_PORT"
  echo "tls:"
  echo "  certFile: $AGENT_HOME/config/certs/agent-server.crt"
  echo "  keyFile: $AGENT_HOME/config/certs/agent-server.key"
  echo "  clientCaFile: $AGENT_HOME/config/certs/main-ca.crt"
  echo "  allowedClientSubjects:"
  echo "    - CN=$CENTRAL_CN"
  echo "security:"
  echo "  allowedSourceCidrs:"
  echo "    - $CENTRAL_IP/32"
  echo "  allowedRoots:"
  local IFS=','
  for r in $roots_csv; do echo "    - $r"; done
  echo "  deniedPaths:"
  echo "    - /etc/shadow"
  echo "    - /etc/sudoers"
  echo "    - /root"
  echo "    - /home/*/.ssh/**"
  echo "    - /**/id_rsa"
  echo "    - /**/*.pem"
  echo "    - /**/*.key"
  echo "    - /proc"
  echo "    - /sys"
  echo "    - /dev"
  echo "  showHiddenFilesDefault: false"
  echo "  maxDirectoryEntries: 1000"
  echo "  maxPreviewBytes: 5242880"
  echo "  maxPreviewLines: 5000"
  echo "  maxTailLines: 5000"
  echo "  maxSearchResults: 200"
  echo "  maxSearchScanBytes: 67108864"
  echo "  requestTimeoutSeconds: 10"
  echo "  apiToken: \"\""
  echo "sync:"
  echo "  writeEnabled: $sync_write"
  echo "  targetRoots:"
  if [ "$role" = "standby" ]; then
    for r in $roots_csv; do echo "    - $r"; done
  else
    echo "    []"
  fi
  echo "  maxChunkBytes: 4194304"
  echo "  maxFiles: 100000"
  echo "logging:"
  echo "  file: $AGENT_HOME/logs/agent.log"
}

rollout_one() {
  local ip="$1" server_id="$2" role="$3" roots_csv="$4"
  local target="$SSH_USER@$ip"
  echo ">> [$server_id] $ip role=$role"

  ssh "$target" "sudo mkdir -p $AGENT_HOME/config/certs $AGENT_HOME/logs $AGENT_HOME/run"

  if [ -n "$AGENT_BIN" ] && [ -f "$AGENT_BIN" ]; then
    scp "$AGENT_BIN" "$target:/tmp/file-preview-agent"
    ssh "$target" "sudo install -m0755 /tmp/file-preview-agent $AGENT_HOME/file-preview-agent && rm -f /tmp/file-preview-agent"
  fi

  if [ -n "$CERTS_DIR" ]; then
    scp "$CERTS_DIR/${server_id}-agent.crt" "$target:/tmp/agent-server.crt"
    scp "$CERTS_DIR/${server_id}-agent.key" "$target:/tmp/agent-server.key"
    scp "$CERTS_DIR/main-ca.crt" "$target:/tmp/main-ca.crt"
    ssh "$target" "sudo install -m0644 /tmp/agent-server.crt $AGENT_HOME/config/certs/agent-server.crt && \
      sudo install -m0600 /tmp/agent-server.key $AGENT_HOME/config/certs/agent-server.key && \
      sudo install -m0644 /tmp/main-ca.crt $AGENT_HOME/config/certs/main-ca.crt && \
      rm -f /tmp/agent-server.crt /tmp/agent-server.key /tmp/main-ca.crt"
  fi

  render_agent_yml "$server_id" "$role" "$roots_csv" > "/tmp/agent-${server_id}.yml"
  scp "/tmp/agent-${server_id}.yml" "$target:/tmp/agent.yml"
  rm -f "/tmp/agent-${server_id}.yml"
  ssh "$target" "sudo install -m0640 /tmp/agent.yml $AGENT_HOME/config/agent.yml && rm -f /tmp/agent.yml && \
    sudo chown -R $AGENT_USER:$AGENT_USER $AGENT_HOME/config && \
    sudo systemctl restart file-preview-agent && sleep 1 && systemctl is-active file-preview-agent"

  echo ">> [$server_id] 完成"
}

while read -r line; do
  # 跳过空行与注释
  case "$line" in
    ''|\#*) continue ;;
  esac
  # shellcheck disable=SC2086
  set -- $line
  rollout_one "$1" "$2" "$3" "${4:-/data/logs}"
done < "$HOSTS_FILE"

echo "全部完成。请在中心 servers.yml 配置 backup 后于 Web「服务器管理」重新加载使同步调度生效。"
