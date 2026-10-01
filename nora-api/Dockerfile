# Nora 服务镜像(通用多阶段构建,8 个服务共用)
# 构建(在 nora-api 目录):
#   docker build -f Dockerfile --build-arg SERVICE=agent-service -t nora-agent-service .
# 说明:构建上下文为 nora-api/(需要根 pom 与全部模块);产物只含目标服务 jar + JRE。
#
# 平台说明:jar 是平台无关字节码,镜像按目标架构构建(JRE 层随 --platform 走)。

# ---- 构建阶段:JDK 21 + Maven,构建目标服务(含依赖模块) ----
# 固定在 BUILDPLATFORM(原生架构):jar 是平台无关字节码,多架构时避开 QEMU 模拟构建
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
ARG SERVICE
WORKDIR /build

# Maven 依赖走阿里云镜像(容器内直连 Maven Central 在国内极慢/超时,
# 实测单次全量下载 >40 分钟;镜像源秒级)。settings.xml 放独立路径——
# /root/.m2 被 cache mount 覆盖,不能放那里。
RUN mkdir -p /build/.mvn-config && printf '%s' '<settings><mirrors><mirror><id>aliyun</id><mirrorOf>central</mirrorOf><url>https://maven.aliyun.com/repository/central</url></mirror></mirrors></settings>' > /build/.mvn-config/settings.xml

# 分层缓存(2026-10-02 修复构建卡死):
# 1) --mount=type=cache 持久化 .m2 仓库,跨构建复用——此前无 cache mount,
#    任何源码改动(COPY 层失效)都会让容器内全量重新下载依赖,实测卡 40 分钟
# 2) 源码改动仍会重新编译(正确性所需),但依赖下载不再重复
COPY pom.xml .
COPY common ./common
COPY api ./api
COPY services ./services
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -q -s /build/.mvn-config/settings.xml -N install -DskipTests \
    && mvn -q -s /build/.mvn-config/settings.xml -pl common/nora-common,common/nora-security,api/rag-api,api/file-api,api/datasource-api install -DskipTests \
    && mvn -q -s /build/.mvn-config/settings.xml -pl services/${SERVICE} package -DskipTests

# ---- 运行阶段:JRE 21(精简) ----
FROM eclipse-temurin:21-jre
ARG SERVICE
WORKDIR /app

# docker CLI(静态二进制,从官方 docker:28-cli 镜像拷贝):env-service 管理容器时
# 需要 docker 命令(经挂载的 docker.sock),此前镜像内没有 docker → 所有
# DOCKER 纳管源报「docker 不可用」(megumin 实测)。其他服务不用但共享镜像,
# 多装 ~40MB 可接受。用 --from 拷贝而非容器内 curl 下载:构建期不走外网、
# 多架构(amd64/arm64)由 buildkit 自动选对应平台镜像层。
COPY --from=docker:28-cli /usr/local/bin/docker /usr/local/bin/docker

# 非 root 运行(容器安全基线);docker 组 GID 用 build-arg 匹配宿主机
# (默认 999,常见 docker 组 GID;挂载 docker.sock 后 nora 用户靠组成员身份访问)。
ARG DOCKER_GID=999
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r nora && useradd -r -g nora -d /app -s /sbin/nologin nora \
    && { groupadd -g "${DOCKER_GID}" docker 2>/dev/null || groupadd docker 2>/dev/null || true; } \
    && usermod -aG docker nora \
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
    CMD ["sh", "-c", "curl -fsS --max-time 4 http://localhost:${NORA_PORT:-18080}/actuator/health >/dev/null || exit 1"]

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
