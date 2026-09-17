# -*- coding: utf-8 -*-
"""拆分 McpServerService v3:McpClientPool(连接池+进程树) + McpCommandResolver(命令解析)。"""
import io, re

BASE = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/'
SVC = BASE + 'McpServerService.java'
POOL = BASE + 'McpClientPool.java'
RESOLVER = BASE + 'McpCommandResolver.java'

src = io.open(SVC, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub, start=0):
    for i in range(start, len(lines)):
        if sub in lines[i] and not lines[i].strip().startswith(('//', '*')):
            return i
    raise RuntimeError('not found: ' + sub)

def block_end(start):
    depth = 0
    opened = False
    for j in range(start, len(lines)):
        depth += lines[j].count('{') - lines[j].count('}')
        if not opened and depth > 0:
            opened = True
        if opened and depth == 0:
            return j
    raise RuntimeError('unbalanced at %d' % (start + 1))

def grab_comment(s):
    while s - 1 >= 0:
        p = lines[s-1].strip()
        if p.startswith('/**') or p.startswith('*') or p.startswith('/*') or p == '*/':
            s -= 1
        else:
            break
    return s

def take(sub, comment=True):
    s = find_idx(sub)
    e = block_end(s)
    s2 = grab_comment(s) if comment else s
    return (s2, e, lines[s2:e+1])

MOVES = {}
def mv(key, sub, comment=True):
    MOVES[key] = take(sub, comment)
    print('MOVE   %-24s %4d-%4d' % (key, MOVES[key][0]+1, MOVES[key][1]+1))

# ---- 字段块 A:两个 Duration 常量(单行) ----
s = find_idx('private static final Duration STDIO_REQUEST_TIMEOUT')
s2 = grab_comment(s)
e = find_idx('private static final Duration HTTP_REQUEST_TIMEOUT')
MOVES['constBlock'] = (s2, e, lines[s2:e+1])
print('MOVE   %-24s %4d-%4d' % ('constBlock', s2+1, e+1))

# ---- 字段块 B:三个并发 Map(字段声明区,不含 jdbcTemplate/objectMapper) ----
s = find_idx('private final Map<Long, McpSyncClient> clients')
s2 = grab_comment(s)
e = find_idx('private final Map<Long, List<ProcessHandle>> stdioTrees')
MOVES['mapsBlock'] = (s2, e, lines[s2:e+1])
print('MOVE   %-24s %4d-%4d' % ('mapsBlock', s2+1, e+1))

# ---- 方法块 ----
mv('clientFor', 'private McpSyncClient clientFor(RawServer server) {')
mv('extractProcess', 'private Process extractProcess(McpClientTransport transport) {')
mv('parseStringList', 'private List<String> parseStringList(String raw) {')
mv('parseHeaders', 'private Map<String, String> parseHeaders(String raw) {')
mv('evictClient', 'private void evictClient(long serverId) {')
mv('killProcessTree', 'private void killProcessTree(long serverId) {')

# ---- 移入 McpCommandResolver ----
mv('resolveCommand', 'static String resolveCommand(String command) {')
mv('hasExtension', 'private static boolean hasExtension(String name) {', comment=False)
mv('pathExtCandidates', 'private static List<String> pathExtCandidates() {', comment=False)
mv('isWindows', 'private static boolean isWindows() {', comment=False)

# ---- 删除死代码 ----
DEL = {}
DEL['renderResult'] = take('private String renderResult(McpSchema.CallToolResult result) {')
print('DELETE %-24s %4d-%4d' % ('renderResult', DEL['renderResult'][0]+1, DEL['renderResult'][1]+1))

allr = sorted([(v[0], v[1]) for v in list(MOVES.values()) + list(DEL.values())])
for a, b in zip(allr, allr[1:]):
    assert a[1] < b[0], 'overlap %s %s' % (a, b)
print('no overlaps, %d blocks' % len(allr))

def join(key):
    return '\n'.join(MOVES[key][2])

# ============ McpClientPool.java ============
pool = []
pool.append('package com.nora.agent.service;')
pool.append('')
pool.append('import com.fasterxml.jackson.databind.JsonNode;')
pool.append('import com.fasterxml.jackson.databind.ObjectMapper;')
pool.append('import io.modelcontextprotocol.client.McpClient;')
pool.append('import io.modelcontextprotocol.client.McpSyncClient;')
pool.append('import io.modelcontextprotocol.spec.McpClientTransport;')
pool.append('import io.modelcontextprotocol.spec.McpSchema;')
pool.append('import org.slf4j.Logger;')
pool.append('import org.slf4j.LoggerFactory;')
pool.append('')
pool.append('import java.time.Duration;')
pool.append('import java.util.ArrayList;')
pool.append('import java.util.LinkedHashMap;')
pool.append('import java.util.List;')
pool.append('import java.util.Map;')
pool.append('import java.util.concurrent.ConcurrentHashMap;')
pool.append('import java.util.concurrent.TimeUnit;')
pool.append('')
pool.append('/**')
pool.append(' * MCP 客户端连接池(2026-09-17 从 McpServerService 拆出,复杂度审计建议 #2):')
pool.append(' * 按 server id 懒连接 + 池化;STDIO 子进程树的生命周期管理')
pool.append(' * (evict/disable/delete/shutdown 时杀树,快照句柄兜底孤儿进程)。')
pool.append(' * 纯机械平移,行为与拆分前逐行一致。')
pool.append(' */')
pool.append('class McpClientPool {')
pool.append('')
pool.append('    private static final Logger log = LoggerFactory.getLogger(McpClientPool.class);')
pool.append('')
pool.append(join('constBlock'))
pool.append('')
pool.append('    private final ObjectMapper objectMapper;')
pool.append(join('mapsBlock'))
pool.append('')
pool.append('    McpClientPool(ObjectMapper objectMapper) {')
pool.append('        this.objectMapper = objectMapper;')
pool.append('    }')
pool.append('')
pool.append(join('clientFor'))
pool.append('')
pool.append(join('extractProcess'))
pool.append('')
pool.append(join('parseStringList'))
pool.append('')
pool.append(join('parseHeaders'))
pool.append('')
pool.append(join('evictClient'))
pool.append('')
pool.append(join('killProcessTree'))
pool.append('')
pool.append('    /** Closes all pooled clients + kills all stdio processes (service shutdown 委托)。 */')
pool.append('    void shutdown() {')
pool.append('        for (Long id : List.copyOf(clients.keySet())) {')
pool.append('            evictClient(id);')
pool.append('        }')
pool.append('        // 两个 map 任一有残留都清理(killProcessTree 同时移除两者)')
pool.append('        java.util.Set<Long> remaining = new java.util.HashSet<>(stdioProcs.keySet());')
pool.append('        remaining.addAll(stdioTrees.keySet());')
pool.append('        for (Long id : remaining) {')
pool.append('            killProcessTree(id);')
pool.append('        }')
pool.append('    }')
pool.append('}')
pool_text = '\n'.join(pool)
pool_text = pool_text.replace('private McpSyncClient clientFor(RawServer server) {',
                              'McpSyncClient clientFor(RawServer server) {')
pool_text = pool_text.replace('private void evictClient(long serverId) {',
                              'void evictClient(long serverId) {')
pool_text = re.sub(r'(?<![\w.])resolveCommand\(', 'McpCommandResolver.resolveCommand(', pool_text)
pool_text = re.sub(r'(?<![\w.])isWindows\(', 'McpCommandResolver.isWindows(', pool_text)
io.open(POOL, 'w', encoding='utf-8', newline='\n').write(pool_text)
print('written:', POOL)

# ============ McpCommandResolver.java ============
res = []
res.append('package com.nora.agent.service;')
res.append('')
res.append('import java.io.File;')
res.append('import java.util.ArrayList;')
res.append('import java.util.List;')
res.append('')
res.append('/**')
res.append(' * stdio 命令解析(2026-09-17 从 McpServerService 拆出,复杂度审计建议 #2):')
res.append(' * 裸命令名 → PATH 扫描(Windows 按 PATHEXT 补全扩展名——CreateProcess')
res.append(' * 不解析无扩展名的 shell shim `npx`,只认 `npx.cmd`)。')
res.append(' */')
res.append('final class McpCommandResolver {')
res.append('')
res.append('    private McpCommandResolver() {')
res.append('    }')
res.append('')
res.append(join('resolveCommand'))
res.append('')
res.append(join('hasExtension'))
res.append('')
res.append(join('pathExtCandidates'))
res.append('')
res.append(join('isWindows'))
res.append('}')
res_text = '\n'.join(res)
# 类内静态互调保持短名(同文件)
io.open(RESOLVER, 'w', encoding='utf-8', newline='\n').write(res_text)
print('written:', RESOLVER)

# ============ 重建 McpServerService ============
skip = set()
for v in list(MOVES.values()) + list(DEL.values()):
    skip.update(range(v[0], v[1] + 1))
kept = [l for i, l in enumerate(lines) if i not in skip]
svc = '\n'.join(kept)

# 调用替换
svc = svc.replace('String resolved = resolveCommand(command.trim());',
                  'String resolved = McpCommandResolver.resolveCommand(command.trim());')
svc = re.sub(r'(?<![\w.])evictClient\(', 'clientPool.evictClient(', svc)
svc = re.sub(r'(?<![\w.])clientFor\(server\)', 'clientPool.clientFor(server)', svc)

# shutdown 委托改写
svc = re.sub(
    r'@PreDestroy\n    public void shutdown\(\) \{\n(?:[^\n]*\n)*?    \}',
    '''@PreDestroy
    public void shutdown() {
        clientPool.shutdown();
    }''', svc, count=1)
assert 'clientPool.shutdown();' in svc, 'shutdown rewrite failed'

# 字段 + 构造器
anchor = '    private final JdbcTemplate jdbcTemplate;'
assert anchor in svc, 'field anchor missing'
svc = svc.replace(anchor, anchor + '\n'
    '    /** 客户端连接池 + STDIO 进程树(从本类拆出,2026-09-17)。 */\n'
    '    private final McpClientPool clientPool;')
anchor2 = '''    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }'''
assert anchor2 in svc, 'ctor anchor missing'
svc = svc.replace(anchor2, '''    public McpServerService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.clientPool = new McpClientPool(objectMapper);
    }''')

io.open(SVC, 'w', encoding='utf-8', newline='\n').write(svc)
print('service: %d -> %d lines' % (len(lines), len(kept)))
