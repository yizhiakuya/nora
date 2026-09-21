/**
 * SSE 流解析（浏览器 ReadableStream）。
 *
 * 2026-09-21:手写解析替换为 eventsource-parser(标准库,零依赖)。
 * 此前手写实现漏了规范边角(BOM、字段值仅去一个前导空格、多行 data 的
 * 空行语义等),替换后由库保证 WHATWG 规范一致性;对外接口不变:
 * 逐事件回调 {event, data}。
 *
 * 为什么仍保留 fetch + ReadableStream 形态(而非浏览器 EventSource):
 * 本项目的 SSE 端点需要 POST/自定义 header/AbortSignal,EventSource 不支持。
 */
import { createParser } from "eventsource-parser";

export interface SSEEvent {
  event: string;
  data: string;
}

export async function parseSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onEvent: (event: SSEEvent) => void | Promise<void>,
  /** 「停止生成」:abort 时释放 reader 退出读循环,已解析的事件不再派发 */
  signal?: AbortSignal
): Promise<void> {
  const decoder = new TextDecoder();

  // 停止生成:释放底层流锁并唤醒 reader.read(),让 await 里的循环立刻退出
  const onAbort = () => {
    void reader.cancel().catch(() => undefined);
  };
  if (signal?.aborted) {
    onAbort();
    return;
  }
  signal?.addEventListener("abort", onAbort, { once: true });

  // parser 的 onEvent 是同步回调,先入队;读循环里再按序 await 派发,
  // 保证 async 调用方的事件顺序与「处理完上一事件才读下一块」的语义
  const queue: SSEEvent[] = [];
  const parser = createParser({
    onEvent: (message) => {
      queue.push({ event: message.event ?? "", data: message.data });
    },
  });
  const dispatchQueued = async (): Promise<void> => {
    while (queue.length > 0) {
      if (signal?.aborted) return;
      await onEvent(queue.shift()!);
    }
  };

  try {
    let reading = true;
    while (reading) {
      const { done, value } = await reader.read();
      if (signal?.aborted || done) {
        reading = false;
        continue;
      }
      parser.feed(decoder.decode(value, { stream: true }));
      await dispatchQueued();
    }
    // 收尾:解码器残留字节;再补一个空行把「流结束时仍挂在缓冲里的事件」
    // flush 出来(对齐旧实现的语义——服务端最后一块没带空行时,完整事件
    // 也应派发;正常以 \n\n 结尾的流不受影响,不会重复派发)
    const tail = decoder.decode();
    if (tail) parser.feed(tail);
    parser.feed("\n\n");
    await dispatchQueued();
  } finally {
    signal?.removeEventListener("abort", onAbort);
  }
}
