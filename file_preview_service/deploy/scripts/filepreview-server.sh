#!/usr/bin/env bash
#
# 中心文件预览主服务 一键启停脚本
# 用法: ./filepreview-server.sh {start|stop|restart|status}
#
# 目录约定（可用环境变量覆盖）:
#   APP_HOME   部署根目录，默认取脚本所在目录的上一级
#   JAR        可执行 jar，默认 $APP_HOME/linux-file-preview.jar
#   CONFIG     外部配置，默认 $APP_HOME/config/application.yml
#   JAVA       java 可执行文件，默认 java（需 JDK 1.8）
#   JAVA_OPTS  JVM 参数，默认见下
#   HEALTH_URL 健康检查地址，默认 http://127.0.0.1:8080/
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="${APP_HOME:-$(cd "$SCRIPT_DIR/.." && pwd)}"
JAR="${JAR:-$APP_HOME/linux-file-preview.jar}"
CONFIG="${CONFIG:-$APP_HOME/config/application.yml}"
JAVA="${JAVA:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms256m -Xmx512m -Dfile.encoding=UTF-8}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/}"

PID_FILE="$APP_HOME/run/server.pid"
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
  if [ ! -f "$JAR" ]; then
    echo "错误: 找不到 jar: $JAR" >&2
    exit 1
  fi
  if ! command -v "$JAVA" >/dev/null 2>&1; then
    echo "错误: 未找到 java，请安装 JDK 1.8 或设置 JAVA 环境变量" >&2
    exit 1
  fi

  local config_arg=""
  [ -f "$CONFIG" ] && config_arg="--spring.config.location=file:$CONFIG"

  echo "启动中... (工作目录 $APP_HOME)"
  cd "$APP_HOME"
  # shellcheck disable=SC2086
  nohup "$JAVA" $JAVA_OPTS -jar "$JAR" $config_arg >> "$STDOUT_LOG" 2>&1 &
  echo $! > "$PID_FILE"

  # 等待健康检查（最多 60s）
  local i=0
  while [ $i -lt 60 ]; do
    if curl -sf -o /dev/null "$HEALTH_URL" 2>/dev/null; then
      echo "启动成功 (PID $(cat "$PID_FILE"))，健康检查通过: $HEALTH_URL"
      return 0
    fi
    if ! is_running; then
      echo "启动失败，请查看日志: $STDOUT_LOG 与 $LOG_DIR/app.log" >&2
      exit 1
    fi
    sleep 1
    i=$((i + 1))
  done
  echo "警告: 进程已启动 (PID $(cat "$PID_FILE")) 但健康检查未通过，请检查 $HEALTH_URL 与日志" >&2
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
  while [ $i -lt 30 ]; do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
    i=$((i + 1))
  done
  if kill -0 "$pid" 2>/dev/null; then
    echo "优雅停止超时，强制结束"
    kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$PID_FILE"
  echo "已停止"
}

status() {
  if is_running; then
    echo "运行中 (PID $(cat "$PID_FILE"))"
    if curl -sf -o /dev/null "$HEALTH_URL" 2>/dev/null; then
      echo "健康检查: 通过 ($HEALTH_URL)"
    else
      echo "健康检查: 未通过 ($HEALTH_URL)"
    fi
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
