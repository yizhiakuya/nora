# Default: build sources. Use --build-arg ARTIFACT_STAGE=prebuilt after mvn verify.
ARG ARTIFACT_STAGE=build
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2,sharing=locked mvn -q verify && cp target/nora-api-0.1.0-SNAPSHOT.jar /app.jar

FROM scratch AS prebuilt
COPY target/nora-api-0.1.0-SNAPSHOT.jar /app.jar

FROM ${ARTIFACT_STAGE} AS artifact

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=docker:28-cli /usr/local/bin/docker /usr/local/bin/docker
ARG DOCKER_GID=999
RUN apt-get update && apt-get install -y --no-install-recommends curl util-linux procps \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r nora && useradd -r -g nora -d /app -s /sbin/nologin nora \
    && groupadd -o -g "${DOCKER_GID}" docker && usermod -aG docker nora \
    && mkdir -p /app/data /app/logs /app/agent-workspace && chown -R nora:nora /app
COPY --from=artifact --chown=nora:nora /app.jar /app/app.jar
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright
RUN java -Dloader.main=com.microsoft.playwright.CLI -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher install --with-deps --only-shell chromium \
    && chmod -R a+rX /ms-playwright
ENV PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 TZ=Asia/Shanghai LOG_PATH=/app/logs \
    JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseG1GC"
USER nora
EXPOSE 18080
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD ["sh", "-c", "curl -fsS --max-time 4 http://localhost:${NORA_PORT:-18080}/actuator/health >/dev/null || exit 1"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
