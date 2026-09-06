/**
 * SSE 流解析（浏览器 ReadableStream）。
 *
 * 处理规范：
 * - 事件以空行分隔；
 * - `event:` 行声明事件名；
 * - `data:` 行可以多行，派发前用 `\n` 拼接（JSON 跨行时同样成立）；
 * - CR 由网络层正常携带，这里统一剥掉，兼容 Windows 换行。
 */
export interface SSEEvent {
  event: string;
  data: string;
}

export async function parseSSEStream(
  reader: ReadableStreamDefaultReader<Uint8Array>,
  onEvent: (event: SSEEvent) => void | Promise<void>
): Promise<void> {
  const decoder = new TextDecoder();
  let buffer = "";

  const dispatch = (block: string) => {
    let eventName = "";
    const dataLines: string[] = [];

    for (const rawLine of block.split("\n")) {
      const line = rawLine.trimEnd();
      if (!line || line.startsWith(":")) continue;
      if (line.startsWith("event:")) {
        eventName = line.slice(6).trim();
      } else if (line.startsWith("data:")) {
        dataLines.push(line.slice(5).trimStart());
      }
    }

    if (eventName || dataLines.length > 0) {
      onEvent({ event: eventName, data: dataLines.join("\n") });
    }
  };

  let reading = true;
  while (reading) {
    const { done, value } = await reader.read();
    if (done) {
      reading = false;
      continue;
    }

    buffer += decoder.decode(value, { stream: true });
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() ?? "";

    for (const block of blocks) {
      dispatch(block);
    }
  }

  buffer += decoder.decode();
  if (buffer.trim()) dispatch(buffer);
}
