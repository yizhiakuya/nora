/**
 * 统一请求缓存：TTL + 并发去重 + 按资源失效。
 *
 * 为什么需要（实测的重复请求，来自 agent-service 日志统计）：
 *   - GET /api/chat/sessions/{id}/messages  被调 27 次，单次响应 123KB
 *   - GET /api/chat/sessions/{id}           被调 40 次（切换会话反复重拉）
 *   - 后端恢复在线时一次性 re-sync 8 个 store，每个都是一次新请求
 * 本地服务本身只要 3-6ms，所以瓶颈不是延迟而是**重复劳动**：同一份数据在
 * 短时间内被反复拉取、解析、重建对象。这一层统一收口这些重复。
 *
 * 三条不变量（顺序很重要）：
 *   1. 写操作必定失效——任何非 GET 成功响应都会清掉对应资源前缀的缓存。
 *      宁可多取一次，也不能把写后的旧数据当新的用。
 *   2. 并发只发一次——同 key 同时在途时复用同一个 Promise。
 *   3. 失效与在途互不干扰——失效后旧请求的结果不得回填（版本号守卫），
 *      否则「失效 → 旧响应晚到 → 覆盖」会把刚清的脏数据又写回来。
 *
 * 本模块不依赖任何其它模块（纯容器），避免与 client.ts 形成循环引用。
 */

interface CacheEntry {
  value: unknown;
  expiresAt: number;
}

interface InFlight {
  promise: Promise<unknown>;
  /** 发起时的版本号：与当前版本不一致说明期间被失效过，结果必须丢弃 */
  version: number;
}

const entries = new Map<string, CacheEntry>();
const inFlight = new Map<string, InFlight>();
/** 每个 key 的失效代数；失效一次自增，用于识别「失效前发出的请求」。 */
const versions = new Map<string, number>();

const stats = {
  hits: 0,
  misses: 0,
  dedupes: 0,
  staleDrops: 0,
  invalidations: 0,
};

function bumpVersion(key: string): void {
  versions.set(key, (versions.get(key) ?? 0) + 1);
}

/**
 * 从请求路径取资源前缀（第一段），用于失效范围。
 *
 * 刻意取第一段而不是更精确的前缀：写 `/chat/sessions/{id}/messages` 时，
 * 会话列表的 messageCount、lastActivity 也变了——按第一段失效能一并覆盖。
 * 代价是范围偏大（写 /chat 会清掉 /chat 下所有缓存），但缓存的目的本就是
 * 消除重复突发，不是把每一个字节都留住；可预测 > 精细。
 */
function resourceOf(path: string): string {
  const clean = path.split("?")[0].replace(/^\/+/, "");
  const first = clean.split("/")[0] ?? "";
  return first ? `/${first}` : "";
}

/**
 * 读缓存或执行 fetcher。
 *
 * @param key    缓存键（调用方保证同参数同 key）
 * @param ttlMs  存活时间；<=0 表示只做并发去重、不留存
 * @param force  跳过缓存直接取（拿终态/写后立即刷新时用）
 */
export function cached<T>(key: string, ttlMs: number, force: boolean, fetcher: () => Promise<T>): Promise<T> {
  if (!force) {
    const hit = entries.get(key);
    if (hit && hit.expiresAt > Date.now()) {
      stats.hits++;
      return Promise.resolve(hit.value as T);
    }
    if (hit) entries.delete(key); // 过期即清，避免无限堆积

    const pending = inFlight.get(key);
    if (pending) {
      stats.dedupes++;
      return pending.promise as Promise<T>;
    }
  }

  stats.misses++;
  const myVersion = versions.get(key) ?? 0;
  const promise = fetcher()
    .then((value) => {
      // 版本守卫：期间被失效过就丢弃结果，不回填
      if ((versions.get(key) ?? 0) !== myVersion) {
        stats.staleDrops++;
        return value;
      }
      if (ttlMs > 0) {
        entries.set(key, { value, expiresAt: Date.now() + ttlMs });
      }
      return value;
    })
    .finally(() => {
      // 只清自己这一格：期间可能有新的在途（失效后重发）已顶替
      if (inFlight.get(key)?.promise === promise) inFlight.delete(key);
    });

  inFlight.set(key, { promise, version: myVersion });
  return promise;
}

/** 失效匹配的资源前缀（精确前缀匹配，含边界）。 */
export function invalidate(prefix: string): void {
  if (!prefix) return;
  let dropped = 0;
  // Array.from 而非展开 Map 迭代器：本项目 tsconfig 的 target 不支持直接
  // 迭代 Map（TS2802，需 downlevelIteration）
  const allKeys = Array.from(new Set([...Array.from(entries.keys()), ...Array.from(inFlight.keys())]));
  for (const key of allKeys) {
    // 边界匹配：/chat 命中 /chat、/chat/sessions、/chat-x 不命中
    const isSame = key === prefix;
    const isChild = key.startsWith(prefix.endsWith("/") ? prefix : `${prefix}/`);
    const isQuery = key.startsWith(`${prefix}?`);
    if (isSame || isChild || isQuery) {
      bumpVersion(key);
      if (entries.delete(key)) dropped++;
      inFlight.delete(key);
      stats.invalidations++;
    }
  }
  if (dropped === 0) return;
}

/** 按请求路径失效（写操作成功后调用）。 */
export function invalidateForPath(path: string): void {
  invalidate(resourceOf(path));
}

/** 清空全部缓存。 */
export function invalidateAll(): void {
  const allKeys = Array.from(new Set([...Array.from(entries.keys()), ...Array.from(inFlight.keys())]));
  for (const key of allKeys) {
    bumpVersion(key);
  }
  entries.clear();
  inFlight.clear();
  stats.invalidations++;
}

/**
 * 重置计数器（不清缓存条目）。
 *
 * 统计是模块级累加：观测长时间运行的命中率有意义，但单测断言增量需要
 * 一个干净的起点。与 invalidateAll 分开，避免「想清零计数」的调用方
 * 意外丢掉缓存内容。
 */
export function resetStats(): void {
  stats.hits = 0;
  stats.misses = 0;
  stats.dedupes = 0;
  stats.staleDrops = 0;
  stats.invalidations = 0;
}

/** 观测快照：调试与验证用（命中率能说明这层是否真的在起作用）。 */
export function cacheStats(): {
  size: number;
  inFlight: number;
  hits: number;
  misses: number;
  dedupes: number;
  staleDrops: number;
  invalidations: number;
  hitRate: number | null;
  keys: string[];
} {
  const total = stats.hits + stats.misses;
  return {
    size: entries.size,
    inFlight: inFlight.size,
    ...stats,
    hitRate: total > 0 ? Number((stats.hits / total).toFixed(3)) : null,
    keys: Array.from(entries.keys()),
  };
}
