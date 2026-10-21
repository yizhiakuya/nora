const labels: Record<string, string> = {
  queued: "等待开始", running: "处理中", awaiting_approval: "等待确认", cancelling: "正在停止",
  completed: "已完成", partial: "部分完成", failed: "失败", cancelled: "已停止", interrupted: "已中断",
};

export function chatRunStatusLabel(status: string) { return labels[status] ?? status; }
