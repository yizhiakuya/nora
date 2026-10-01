# Nora 前端镜像(多阶段:Node 构建 → nginx 托管 + 反代网关)
# 构建(在 nora-web 目录):docker build -t nora-web .
#
# 多架构优化:构建阶段固定在 BUILDPLATFORM(原生架构)运行——
# vite 产物是纯静态文件(平台无关),只有最终 nginx 层随目标架构;
# 否则 arm64 在 QEMU 模拟下跑 pnpm/vite 慢 10 倍以上(实测 16 分钟未完成)。

# ---- 构建阶段(原生架构) ----
FROM --platform=$BUILDPLATFORM node:22-alpine AS build
WORKDIR /build
# 固定 pnpm 主版本(与 lockfile 生成版本一致,避免 corepack 拉最新导致 lockfile 不匹配)
RUN npm install -g pnpm@11.23.0
# patches 目录参与安装(pnpm patchedDependencies 需要补丁文件存在)
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml ./
COPY patches ./patches
RUN --mount=type=cache,target=/root/.local/share/pnpm/store pnpm install --frozen-lockfile
COPY . .
# 生产构建(走同源 /api,nginx 反代到网关)
RUN pnpm build

# ---- 运行阶段:nginx(随目标架构) ----
FROM nginx:1.27-alpine
COPY --from=build /build/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
HEALTHCHECK --interval=15s --timeout=3s --retries=5 \
    CMD wget -qO- http://127.0.0.1/ > /dev/null 2>&1 || exit 1
