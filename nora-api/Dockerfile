# Nora 服务镜像(通用多阶段构建,8 个服务共用)
# 构建(在 nora-api 目录):
#   docker build -f Dockerfile --build-arg SERVICE=agent-service -t nora-agent-service .
# 说明:构建上下文为 nora-api/(需要根 pom 与全部模块);产物只含目标服务 jar + JRE。
#
# 平台说明:jar 是平台无关字节码,镜像按目标架构构建(JRE 层随 --platform 走)。

# ---- 构建阶段:JDK 21 + Maven,构建目标服务(含依赖模块) ----
FROM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build

# 先拷 pom 体系,利用层缓存预热依赖
COPY pom.xml .
COPY common ./common
COPY api ./api
COPY services ./services
# 先 install 父 pom 与共享模块(子模块 parent 需要从本地仓库解析),
# 再单独构建目标服务(-o 离线:依赖已全部就位,避免重复联网)
RUN mvn -q -N install -DskipTests \
    && mvn -q -pl common/nora-common,common/nora-security,api/rag-api,api/file-api,api/datasource-api install -DskipTests \
    && mvn -q -pl services/${SERVICE} package -DskipTests

# ---- 运行阶段:JRE 21(精简) ----
FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app

# 非 root 运行(容器安全基线)
RUN groupadd -r nora && useradd -r -g nora -d /app -s /sbin/nologin nora \
    && mkdir -p /app/data /app/logs /app/agent-workspace \
    && chown -R nora:nora /app
COPY --from=build --chown=nora:nora /build/services/${SERVICE}/target/${SERVICE}-0.1.0-SNAPSHOT.jar /app/app.jar

USER nora

# 时区与日志默认(可被环境变量覆盖)
ENV TZ=Asia/Shanghai \
    LOG_PATH=/app/logs \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseG1GC"

EXPOSE 18080 18081 18082 18083 18084 18085 18086 18087

# 健康检查:Spring Boot actuator(每个服务都暴露 /actuator/health)
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD ["sh", "-c", "wget -qO- http://localhost:${NORA_PORT:-18080}/actuator/health >/dev/null 2>&1 || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
