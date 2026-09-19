#!/usr/bin/env bash
# Nora 生产部署辅助脚本（在 megumin 上执行）
# 用法: deploy-megumin.sh <infra|restore|units|nginx|verify>
set -euo pipefail
BASE=/opt/nora

case "${1:-}" in
infra)
  mkdir -p $BASE/jars $BASE/logs $BASE/data/files $BASE/agent-workspace /var/www/nora
  docker rm -f nora-postgres nora-redis nora-nacos nora-kafka 2>/dev/null || true
  docker run -d --name nora-postgres --restart unless-stopped \
    -e POSTGRES_DB=nora -e POSTGRES_USER=nora -e POSTGRES_PASSWORD=nora \
    -p 15435:5432 -v nora-pgdata:/var/lib/postgresql/data pgvector/pgvector:pg16
  docker run -d --name nora-redis --restart unless-stopped -p 16379:6379 redis:7-alpine
  docker run -d --name nora-nacos --restart unless-stopped \
    -e MODE=standalone -e JVM_XMS=256m -e JVM_XMX=512m -e JVM_XMN=128m \
    -p 8848:8848 -p 9848:9848 -p 9849:9849 \
    -v nora-nacos-data:/home/nacos/data nacos/nacos-server:v2.5.1
  # Kafka(KRaft 单节点,2026-09-19 通知事件总线):业务服务 producer → notification-service consumer。
  # 内存限 512M(N100 与全服务共存);advertised 用 localhost——同机部署各服务直连。
  docker run -d --name nora-kafka --restart unless-stopped \
    -e KAFKA_NODE_ID=1 -e KAFKA_PROCESS_ROLES=broker,controller \
    -e KAFKA_LISTENERS=PLAINTEXT://:9092,CONTROLLER://:9093 \
    -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
    -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
    -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
    -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
    -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
    -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
    -e KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS=0 \
    -e KAFKA_HEAP_OPTS="-Xmx512m -Xms256m" \
    -p 9092:9092 -v nora-kafka-data:/var/lib/kafka/data apache/kafka:3.7.1
  echo "等待 postgres ..."
  for i in $(seq 1 90); do docker exec nora-postgres pg_isready -U nora -d nora >/dev/null 2>&1 && break; sleep 2; done
  docker exec nora-postgres pg_isready -U nora -d nora
  echo "等待 kafka ..."
  for i in $(seq 1 60); do
    docker exec nora-kafka /opt/kafka/bin/kafka-broker-api-versions.sh --bootstrap-server localhost:9092 >/dev/null 2>&1 && break
    sleep 3
  done
  # 通知 topic(幂等:已存在时 create 报 TopicExistsException,忽略)
  docker exec nora-kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 \
    --create --topic nora.notifications --partitions 1 --replication-factor 1 2>/dev/null || true
  echo "OK-infra"
  ;;
restore)
  docker cp $BASE/nora-dump.sql nora-postgres:/tmp/nora.sql
  docker exec nora-postgres psql -U nora -d nora -q -f /tmp/nora.sql >/tmp/nora-restore.log 2>&1 || { tail -20 /tmp/nora-restore.log; exit 1; }
  docker exec nora-postgres psql -U nora -d nora -c "select count(*) chat_msgs from schema_agent.chat_message;"
  docker exec nora-postgres psql -U nora -d nora -c "update schema_agent.app_setting set value='{\"host\":\"127.0.0.1\",\"port\":7897,\"enabled\":false}' where key='proxy';"
  echo "OK-restore"
  ;;
units)
  echo "等待 nacos 就绪 ..."
  for i in $(seq 1 60); do curl -sf --max-time 3 http://localhost:8848/nacos/v1/console/health/readiness >/dev/null 2>&1 && break; sleep 3; done
  curl -s --max-time 3 http://localhost:8848/nacos/v1/console/health/readiness || echo "(nacos readiness 未确认,继续)"

  mkunit() {
    name=$1; xmx=$2; shift 2
    unit=/etc/systemd/system/nora-$name.service
    {
      echo "[Unit]"
      echo "Description=Nora $name-service"
      echo "After=network.target docker.service"
      echo "Wants=docker.service"
      echo
      echo "[Service]"
      echo "Type=simple"
      echo "WorkingDirectory=$BASE"
      echo "Environment=NORA_ENV_FILE=$BASE/.env.local"
      for e in "$@"; do echo "Environment=$e"; done
      echo "ExecStart=/usr/bin/java -Xmx$xmx -jar $BASE/jars/$name-service-0.1.0-SNAPSHOT.jar"
      echo "Restart=always"
      echo "RestartSec=5"
      echo "StandardOutput=append:$BASE/logs/$name.out.log"
      echo "StandardError=append:$BASE/logs/$name.err.log"
      echo
      echo "[Install]"
      echo "WantedBy=multi-user.target"
    } > $unit
  }

  DB='jdbc:postgresql://localhost:15435/nora'
  DBU='SPRING_DATASOURCE_USERNAME=nora'
  DBP='SPRING_DATASOURCE_PASSWORD=nora'

  # 令牌登录(2026-09-19):生产必配——值从 megumin 的 $BASE/.env.local 读取
  # (NORA_AUTH_TOKEN=xxx 一行);未配置=免登录(仅限纯内网,公网暴露务必配)
  AUTH_TOKEN=$(grep -E '^NORA_AUTH_TOKEN=' "$BASE/.env.local" 2>/dev/null | head -1 | cut -d= -f2-)
  mkunit gateway    384m ${AUTH_TOKEN:+"NORA_AUTH_TOKEN=$AUTH_TOKEN"}
  mkunit file       640m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_file"       "$DBU" "$DBP"
  mkunit rag        512m "SPRING_DATASOURCE_URL=$DB"                                  "$DBU" "$DBP" NORA_REDIS_PORT=16379
  mkunit agent      768m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_agent"       "$DBU" "$DBP" NORA_REDIS_PORT=16379 NORA_AGENT_WORKSPACE=$BASE/agent-workspace
  mkunit datasource 384m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_datasource"  "$DBU" "$DBP"
  mkunit env        384m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_env"         "$DBU" "$DBP"
  mkunit automation 384m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_automation"  "$DBU" "$DBP"
  # 通知中心(2026-09-19):消费 Kafka 事件落库;producer 侧(rag/env/automation)
  # 同样需要 kafka 地址——下面给三个 producer 服务补 SPRING_KAFKA_BOOTSTRAP_SERVERS
  # (本机部署 localhost:9092 即默认值,仅显式化以防将来改端口)
  mkunit notification 384m "SPRING_DATASOURCE_URL=$DB?currentSchema=schema_notification" "$DBU" "$DBP"

  systemctl daemon-reload
  systemctl enable --now nora-gateway nora-file nora-rag nora-agent nora-datasource nora-env nora-automation nora-notification >/dev/null
  echo "等待服务健康 ..."
  ok=1
  for i in $(seq 1 60); do
    ok=1
    for p in 8080 8081 8082 8083 8084 8085 8086 8087; do
      [ "$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 3 http://localhost:$p/actuator/health)" = "200" ] || ok=0
    done
    [ $ok -eq 1 ] && break
    sleep 3
  done
  for p in 8080 8081 8082 8083 8084 8085 8086 8087; do
    printf "port %s: %s\n" $p "$(curl -s -o /dev/null -w '%{http_code}' --noproxy '*' --max-time 3 http://localhost:$p/actuator/health)"
  done
  echo "OK-units ok=$ok"
  ;;
nginx)
  if [ ! -f /etc/nginx/nora.htpasswd ]; then
    PASS=$(openssl rand -hex 5)
    printf "nora:%s\n" "$(openssl passwd -apr1 "$PASS")" > /etc/nginx/nora.htpasswd
    echo "NORA_WEB_PASSWORD=$PASS"
  else
    echo "htpasswd 已存在（沿用）"
  fi
  cat > /etc/nginx/sites-available/nora <<'EOF'
# Nora 工作台 生产入口（HTTPS + Basic 认证）
server {
    listen 8900 ssl;
    server_name nora.rainaki.top;

    ssl_certificate /etc/nginx/certs/rainaki.pem;
    ssl_certificate_key /etc/nginx/certs/rainaki.key;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_session_cache shared:NORASSL:10m;
    ssl_session_timeout 10m;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Content-Type-Options nosniff always;
    add_header X-Frame-Options SAMEORIGIN always;
    add_header Referrer-Policy no-referrer always;

    client_max_body_size 200m;

    root /var/www/nora;
    index index.html;

    auth_basic "Nora";
    auth_basic_user_file /etc/nginx/nora.htpasswd;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
        proxy_buffering off;
        proxy_cache off;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }

    location / {
        try_files $uri /index.html;
    }
}
EOF
  ln -sfn /etc/nginx/sites-available/nora /etc/nginx/sites-enabled/nora
  nginx -t
  systemctl reload nginx
  echo "OK-nginx"
  ;;
verify)
  echo "== 内部 =="
  curl -s -o /dev/null -w "gateway api: %{http_code}\n" --noproxy '*' --max-time 5 "http://localhost:8080/api/chat/sessions?limit=1"
  echo "== nginx(https, 本机) =="
  curl -sk -o /dev/null -w "web: %{http_code}\n" --max-time 5 -u "$(cut -d: -f1 /etc/nginx/nora.htpasswd)" https://localhost:8900/ -H "Host: nora.rainaki.top" || true
  echo "OK-verify"
  ;;
*)
  echo "usage: $0 <infra|restore|units|nginx|verify>"; exit 1 ;;
esac
