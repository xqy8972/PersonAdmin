#!/bin/bash
# PersonAdmin 阿里云一键部署脚本
# 用法：bash deploy.sh（在任意目录执行）
# 流程：清理旧代码 -> clone 最新代码 -> 删旧包 -> maven 打包 -> 杀旧进程 -> java -jar 启动
#       -> 健康检查 /personAdmin/health -> 部署前端到 nginx 目录
# 部署过程日志：tee 到 /home/work/app/PersonAdmin/log/start.log

APP_DIR=/home/work/app/PersonAdmin
REPO_DIR=$APP_DIR/PersonAdmin          # git clone 下来的代码目录
LOG_DIR=$APP_DIR/log
START_LOG=$LOG_DIR/start.log
NGINX_HTML=/usr/share/nginx/html/PersonAdmin
PORT=8089
HEALTH_URL=http://127.0.0.1:$PORT/personAdmin/health
DOMAIN=https://surfweb8972.com             # 前端部署后调后端用的域名（nginx 反代 /personAdmin/api）

mkdir -p "$LOG_DIR"
# 部署日志同时打控制台和 start.log
exec > >(tee -a "$START_LOG") 2>&1

echo "==================== 部署开始 $(date '+%Y-%m-%d %H:%M:%S') ===================="

# ---------- 1. 清理旧代码并 clone 最新 ----------
cd "$APP_DIR" || exit 1
if [ -d "$REPO_DIR" ]; then
  echo "[1/8] 发现已存在的代码目录 $REPO_DIR，删除..."
  rm -rf "$REPO_DIR"
fi
echo "[2/8] clone 最新代码 https://github.com/xqy8972/PersonAdmin.git ..."
git clone https://github.com/xqy8972/PersonAdmin.git || { echo "clone 失败，部署终止"; exit 1; }
cd "$REPO_DIR" || exit 1

# ---------- 2. 删除旧包并 maven 打包 ----------
echo "[3/8] 删除已存在的 PersonAdmin 包..."
rm -f "$APP_DIR"/PersonAdmin-*.jar
rm -rf target

echo "[4/8] maven 编译打包..."
mvn clean package -DskipTests || { echo "打包失败，部署终止"; exit 1; }
JAR=$(ls target/PersonAdmin-*.jar 2>/dev/null | head -1)
if [ -z "$JAR" ]; then echo "未找到打包产物 target/PersonAdmin-*.jar，部署终止"; exit 1; fi
echo "打包产物：$JAR"

# ---------- 3. 杀掉已有 PersonAdmin 进程 ----------
echo "[5/8] 检查已运行的 PersonAdmin 进程..."
PIDS=$(pgrep -f "PersonAdmin.*\.jar" || true)
if [ -n "$PIDS" ]; then
  echo "发现进程 $PIDS，停止..."
  kill $PIDS 2>/dev/null || true
  for i in $(seq 1 15); do
    sleep 1
    pgrep -f "PersonAdmin.*\.jar" >/dev/null || break
  done
  # 还活着就强杀
  PIDS=$(pgrep -f "PersonAdmin.*\.jar" || true)
  [ -n "$PIDS" ] && kill -9 $PIDS 2>/dev/null || true
  echo "旧进程已停止"
else
  echo "没有运行中的 PersonAdmin 进程"
fi

# ---------- 4. 启动 ----------
echo "[6/8] 启动服务（512M 堆，G1，OOM 生成 dump）..."
# 密钥统一从服务器本地 secrets.env 读取（该文件不进 git，权限 600）：
#   DB_PASSWORD=xxx  MOONSHOT_API_KEY=sk-xxx  AI_VISION_API_KEY=sk-xxx
SECRETS_FILE=$APP_DIR/secrets.env
if [ -f "$SECRETS_FILE" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$SECRETS_FILE"
  set +a
  echo "已加载 $SECRETS_FILE"
else
  echo "警告：未找到 $SECRETS_FILE，数据库/AI 密钥将以环境变量或空值生效，识图功能可能不可用"
fi
export LOG_PATH=$LOG_DIR
nohup java -Xms512m -Xmx512m \
  -XX:+UseG1GC \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=$LOG_DIR \
  -Dspring.profiles.active=prod \
  -jar "$JAR" > "$LOG_DIR/console.log" 2>&1 &
echo "服务已启动，PID=$!"

# ---------- 5. 健康检查 ----------
echo "[7/8] 等待健康检查 $HEALTH_URL ..."
HEALTH_OK=0
for i in $(seq 1 60); do
  CODE=$(curl -s -o /dev/null -w "%{http_code}" --max-time 3 "$HEALTH_URL" 2>/dev/null || echo 000)
  if [ "$CODE" = "200" ]; then
    echo "健康检查通过：HTTP 200（${i}0 秒尝试内）"
    HEALTH_OK=1
    break
  fi
  sleep 2
done
if [ "$HEALTH_OK" != "1" ]; then
  echo "健康检查未通过（最后返回 $CODE），请查看 $LOG_DIR/personAdmin.log 与 console.log"
  exit 1
fi

# ---------- 6. 部署前端到 nginx ----------
echo "[8/8] 部署前端到 $NGINX_HTML ..."
mkdir -p "$NGINX_HTML"
rm -rf "${NGINX_HTML:?}"/*           # 删除旧前端文件
SRC_HTML=""
if [ -f PersonAdmin.html ]; then
  SRC_HTML=PersonAdmin.html
elif [ -f personal-management-system.html ]; then
  SRC_HTML=personal-management-system.html
fi
if [ -z "$SRC_HTML" ]; then
  echo "仓库里未找到前端 html 文件（PersonAdmin.html），跳过前端部署"
  exit 1
fi
# 复制前把接口地址替换为线上域名（仓库里保留 localhost:8089 供本地开发）
sed "s|const API_BASE = '[^']*'|const API_BASE = '$DOMAIN/personAdmin/api'|" "$SRC_HTML" > "$NGINX_HTML/PersonAdmin.html"
# 兼容以 index.html 为索引的 nginx 配置
cp "$NGINX_HTML/PersonAdmin.html" "$NGINX_HTML/index.html"
# PWA 文件：manifest / Service Worker / 图标（与页面同目录，SW 作用域为 /personAdmin/）
for f in manifest.webmanifest sw.js icon-192.png icon-512.png icon-180.png; do
  if [ -f "$f" ]; then
    cp "$f" "$NGINX_HTML/"
  else
    echo "警告：仓库中缺少 $f，PWA 功能将不完整"
  fi
done
echo "前端已部署（API_BASE -> $DOMAIN/personAdmin/api，PWA 文件已同步）"

echo "==================== 部署完成 $(date '+%Y-%m-%d %H:%M:%S') ===================="