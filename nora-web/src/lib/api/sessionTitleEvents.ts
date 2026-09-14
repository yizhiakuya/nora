/**
 * 会话标题更新事件总线（进程内，浏览器标签页级）。
 *
 * 为什么单独一个模块：AI 起好的标题由 agentApi 的 SSE 解析层收到，而标题要
 * 写进 useChatSessions（zustand store）。让 agentApi 直接 import store 会形成
 * 循环依赖（store 已经 import agentApi 取 fetchSessions）。放这里两者都只依赖
 * 本模块，方向单一。
 */

type TitleListener = (sessionId: string, title: string) => void;

// 用数组而非 Set:本项目 tsconfig 的 target 不支持直接迭代 Set
// (TS2802,需 downlevelIteration)。听众数量极小(每个挂载的 store 一个),
// 线性查找的开销可以忽略;重复订阅用 indexOf 挡掉。
const listeners: TitleListener[] = [];

/** 广播一条标题更新；无听众时静默丢弃（例如标题事件到达时用户不在对话页）。 */
export function emitSessionTitle(sessionId: string, title: string): void {
  if (!sessionId || !title) return;
  for (const l of [...listeners]) {
    try {
      l(sessionId, title);
    } catch {
      /* 单个听众异常不影响其它听众 */
    }
  }
}

/** 订阅标题更新；返回退订函数。 */
export function subscribeSessionTitle(listener: TitleListener): () => void {
  if (listeners.indexOf(listener) < 0) listeners.push(listener);
  return () => {
    const i = listeners.indexOf(listener);
    if (i >= 0) listeners.splice(i, 1);
  };
}
