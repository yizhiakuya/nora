import * as Dialog from "@radix-ui/react-dialog";

/**
 * 全屏「服务不可用」提示：网关不可达时覆盖应用，保留页面草稿。
 *
 * 此前探针是 agent-service /chat/health——agent 挂了也会全屏替换,即使
 * 文件/数据源/任务服务仍可用,用户无法通过导航进入任何区域。现在网关
 * (api/auth/status,gateway 自身端点)不可达才走到这里(此时确实全站
 * 不可用);agent 单独探测,只影响助手区域的局部提示。
 *
 * 点阵网格背景 + 居中大号三角警告 + 一句状态。自动重连:指数退避探测
 * (5s→10s→20s→30s)后台静默进行,服务恢复后本页自动消失、回到用户原所在页面。
 */
export function ServiceUnavailablePage() {
  return (
    <Dialog.Root open>
      <Dialog.Portal>
        <Dialog.Content
          className="fixed inset-0 z-[100] flex flex-col items-center justify-center bg-background text-gray-800 dark:text-gray-100 select-none"
          onEscapeKeyDown={(event) => event.preventDefault()}
          onInteractOutside={(event) => event.preventDefault()}
          style={{
            // hsl(var(--dot-grid)) 取主题里的点阵颜色(亮/暗各一份)
            backgroundImage:
              "radial-gradient(circle, hsl(var(--dot-grid) / 0.35) 1px, transparent 1px)",
            backgroundSize: "24px 24px",
          }}
        >
          {/* 大号三角警告:描边风格,点阵之上居中 */}
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1"
            strokeLinecap="round"
            strokeLinejoin="round"
            className="w-40 h-40 text-amber-500 dark:text-amber-400"
            aria-hidden
          >
            <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
            <line x1="12" y1="9" x2="12" y2="13" />
            <line x1="12" y1="17" x2="12.01" y2="17" />
          </svg>

          <Dialog.Title asChild>
            <h1 className="mt-8 text-2xl font-semibold tracking-wide text-foreground">
              服务暂时不可用
            </h1>
          </Dialog.Title>
          <Dialog.Description asChild>
            <p className="mt-3 text-xs text-muted-foreground max-w-xs text-center leading-relaxed">
              无法连接到 Nora 后端服务。请确认服务已启动;恢复后本页会自动消失。
            </p>
          </Dialog.Description>
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
