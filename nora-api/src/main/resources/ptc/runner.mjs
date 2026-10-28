import { createInterface } from 'node:readline';

// 容器负责隔离；此协议仅负责将工具请求交回可信 Java 入口。
const send = value => process.stdout.write(JSON.stringify(value) + '\n');
const pending = new Map();
let started = false;
let sequence = 0;
let outputBytes = 0;
let finished = false;
const lines = createInterface({ input: process.stdin, crlfDelay: Infinity });

lines.on('line', async line => {
  try {
    const message = JSON.parse(line);
    if (!started) {
      started = true;
      const tools = Object.freeze({
        call(name, args) {
          if (typeof name !== 'string' || !args || Array.isArray(args) || typeof args !== 'object') {
            throw new TypeError('工具名必须是字符串，参数必须是对象');
          }
          const id = ++sequence;
          if (id > 64) throw new Error('程序最多调用 64 次工具');
          const promise = new Promise((resolve, reject) => {
            pending.set(id, { resolve, reject });
            send({ type: 'call', id, name, args });
          });
          // 未 await 的拒绝仍由宿主记录，不触发 Node 的未处理拒绝退出。
          promise.catch(() => {});
          return promise;
        }
      });
      const output = value => {
        const encoded = JSON.stringify(value);
        if (encoded === undefined) throw new TypeError('output 需要可 JSON 序列化的值');
        outputBytes += Buffer.byteLength(encoded);
        if (outputBytes > 65536) throw new Error('程序输出超过 64 KiB，请筛选结果后输出');
        send({ type: 'output', value });
      };
      const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
      let error;
      try {
        await new AsyncFunction('tools', 'output', 'console', message.code)(
          tools, output, Object.freeze({ log: output, info: output, warn: output, error: output })
        );
        if (pending.size) error = '程序存在未等待的工具调用；已发出调用仍须结算';
      } catch (failure) {
        error = String(failure?.message ?? failure).slice(0, 4000);
      }
      // 宿主在收到 done 后等待所有已登记调用，不相信脚本宣称的业务终态。
      send({ type: 'done', error: error ?? null });
      finished = true;
      if (!pending.size) lines.close();
    } else {
      const waiting = pending.get(message.id);
      if (!waiting) throw new Error('工具结果序号不存在');
      pending.delete(message.id);
      if (message.error) waiting.reject(new Error(message.error));
      else waiting.resolve(message.result);
      if (finished && !pending.size) lines.close();
    }
  } catch (error) {
    send({ type: 'done', error: String(error.message).slice(0, 4000) });
    lines.close();
  }
});
