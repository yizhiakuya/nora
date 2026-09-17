# -*- coding: utf-8 -*-
"""拆 executeTool(562 行)为 11 个 handler + 薄分发器。"""
import io, re

F = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java'
lines = io.open(F, encoding='utf-8').read().split('\n')

# 定位 executeTool
s = next(i for i, l in enumerate(lines) if 'ToolOutcome executeTool(' in l)
depth = 0; opened = False
for j in range(s, len(lines)):
    depth += lines[j].count('{') - lines[j].count('}')
    if not opened and depth > 0: opened = True
    if opened and depth == 0:
        e = j; break

# 分支定位
branches = []
for i in range(s, e + 1):
    m = re.match(r'^        if \("([a-z_]+)"\.equals\(name\)\) \{$', lines[i])
    if m:
        for j in range(i + 1, e + 1):
            if lines[j] == '        }':
                branches.append((i, j, m.group(1)))
                break
print('branches: %d' % len(branches))
for (st, en, name) in branches:
    print('  %-20s %4d-%4d' % (name, st + 1, en + 1))

# 名称 → handler 方法名
HANDLER = {
    'execute_sql': 'execExecuteSql',
    'read_service_logs': 'execReadServiceLogs',
    'execute_write_sql': 'execExecuteWriteSql',
    'manage_container': 'execManageContainer',
    'manage_datasource': 'execManageDatasource',
    'manage_service': 'execManageService',
    'read_file': 'execReadFile',
    'manage_workspace': 'execManageWorkspace',
    'manage_skill': 'execManageSkill',
    'manage_mcp': 'execManageMcp',
    'run_command': 'execRunCommand',
}

# 提取各分支体(去 4 空格缩进)
handlers = {}
for (st, en, name) in branches:
    body = lines[st + 1:en]  # 不含 if 行与结尾 }
    dedented = []
    for l in body:
        if l.startswith('    '):
            dedented.append(l[4:])
        else:
            dedented.append(l)
    handlers[name] = dedented

# 生成 handler 方法(插到 executeTool 之后)
SIG = '    ToolOutcome {M}(String name, String args, ToolStepEmitter.ParsedArgs parsed,\n' \
      '                            java.util.function.Consumer<String> liveOutput) {{'
new_methods = []
# 保持原顺序
order = [b[2] for b in branches]
for name in order:
    m = HANDLER[name]
    new_methods.append('')
    new_methods.append('    /** %s handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */' % name)
    new_methods.append(SIG.format(M=m))
    new_methods.extend(handlers[name])
    new_methods.append('    }')
new_block = '\n'.join(new_methods)

# 新 executeTool 分发器 + MCP 兜底 + unknown
dispatch = []
dispatch.append('    /**')
dispatch.append('     * Dispatches a tool call. Guardrail rejections return a three-part error')
dispatch.append('     * (what was refused + which rule + a correct example) so the model can')
dispatch.append('     * self-correct on the next round.')
dispatch.append('     *')
dispatch.append('     * <p>2026-09-17:各工具分支拆为独立 handler(见下方 exec* 方法),此处只做分发。')
dispatch.append('     */')
dispatch.append('    ToolOutcome executeTool(String name, String args, ToolStepEmitter.ParsedArgs parsed,')
dispatch.append('                                    java.util.function.Consumer<String> liveOutput) {')
for name in order:
    dispatch.append('        if ("%s".equals(name)) {' % name)
    dispatch.append('            return %s(name, args, parsed, liveOutput);' % HANDLER[name])
    dispatch.append('        }')
# MCP 兜底 + unknown(从原 e 之前的尾巴提取:分支列表后到 e)
tail_start = branches[-1][1] + 1  # run_command 结束的下一行
tail = lines[tail_start:e]  # 不含 e 行(方法结尾 })
dispatch.extend(tail)
dispatch.append('    }')
dispatch_text = '\n'.join(dispatch)

# 组装:executeTool 行(s)前 + 新分发器 + 新 handlers + e 行后
before = lines[:s]
after = lines[e + 1:]
out = before + dispatch_text.split('\n') + new_block.split('\n') + after
io.open(F, 'w', encoding='utf-8', newline='\n').write('\n'.join(out))
print('executeTool: %d-%d -> dispatcher %d lines + handlers %d lines' %
      (s + 1, e + 1, len(dispatch_text.split('\n')), len(new_block.split('\n'))))
print('file: %d -> %d lines' % (len(lines), len(out)))
