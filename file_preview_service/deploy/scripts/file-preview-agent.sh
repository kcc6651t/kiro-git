#!/usr/bin/env bash
#
# 目标服务器 Agent 一键启停脚本（非 systemd 场景；生产推荐用 systemd 见 deploy/systemd）
# 用法: ./file-preview-agent.sh {start|stop|restart|status}
#
# 目录约定（可用环境变量覆盖）:
#   APP_HOME  部署根目录，默认取脚本所在目录的上一级
#   BIN       Agent 二进制，默认 $APP_HOME/file-preview-agent
#   CONFIG    配置文件，默认 $APP_HOME/config/agent.yml
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="${APP_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"
BIN="${BIN:-$APP_HOME/file-preview-agent}"
CONFIG="${CONFIG:-$APP_HOME/config/agent.yml}"

PID_FILE="$APP_HOME/run/agent.pid"
LOG_DIR="$APP_HOME/logs"
STDOUT_LOG="$LOG_DIR/console.out"

mkdir -p "$APP_HOME/run" "$LOG_DIR"

is_running() {
  [ -f "$PID_FILE" ] || return 1
  local pid
  pid="$(cat "$PID_FILE" 2>/dev/null || true)"
  [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null
}

start() {
  if is_running; then
    echo "已在运行 (PID $(cat "$PID_FILE"))"
    return 0
  fi
  if [ ! -x "$BIN" ]; then
    echo "错误: 找不到可执行 Agent: $BIN（可执行权限 chmod +x）" >&2
    exit 1
  fi
  if [ ! -f "$CONFIG" ]; then
    echo "错误: 找不到配置: $CONFIG" >&2
    exit 1
  fi
  echo "启动 Agent... (工作目录 $APP_HOME)"
  cd "$APP_HOME"
  nohup "$BIN" --config "$CONFIG" >> "$STDOUT_LOG" 2>&1 &
  echo $! > "$PID_FILE"
  sleep 1
  if is_running; then
    echo "启动成功 (PID $(cat "$PID_FILE"))"
  else
    echo "启动失败，请查看日志: $STDOUT_LOG 与 $LOG_DIR/agent.log" >&2
    exit 1
  fi
}

stop() {
  if ! is_running; then
    echo "未在运行"
    rm -f "$PID_FILE"
    return 0
  fi
  local pid
  pid="$(cat "$PID_FILE")"
  echo "停止中 (PID $pid)..."
  kill "$pid" 2>/dev/null || true
  local i=0
  while [ $i -lt 15 ]; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
    i=$((i + 1))
  done
  kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  rm -f "$PID_FILE"
  echo "已停止"
}

status() {
  if is_running; then
    echo "运行中 (PID $(cat "$PID_FILE"))"
    "$BIN" --version 2>/dev/null || true
  else
    echo "未运行"
    return 3
  fi
}

case "${1:-}" in
  start)   start ;;
  stop)    stop ;;
  restart) stop; sleep 1; start ;;
  status)  status ;;
  *) echo "用法: $0 {start|stop|restart|status}" >&2; exit 2 ;;
esac
