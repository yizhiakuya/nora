# -*- coding: utf-8 -*-
"""拆分 AgentThoughtBlock.tsx:ToolStepRow(工具行) + ContextStepRow(注入行)。"""
import io

DIR = r'nora-web/src/components/chat/'
SRC = DIR + 'AgentThoughtBlock.tsx'
TOOL = DIR + 'ToolStepRow.tsx'
CTX = DIR + 'ContextStepRow.tsx'

src = io.open(SRC, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub, start=0):
    for i in range(start, len(lines)):
        if sub in lines[i]:
            return i
    raise RuntimeError('not found: ' + sub)

def find_end(sub, end_pat, start=0):
    s = find_idx(sub, start)
    for j in range(s, len(lines)):
        if lines[j] == end_pat:
            return s, j
    raise RuntimeError('no end for ' + sub)

# ---- 定位各块(用内容锚定,含注释) ----
def block(sig_start, end_line='}'):
    s = find_idx(sig_start)
    # 向上吞注释
    s2 = s
    while s2 - 1 >= 0 and (lines[s2-1].strip().startswith('*') or lines[s2-1].strip().startswith('/**')
                           or lines[s2-1].strip() == '*/'):
        s2 -= 1
    # 向下找结束:行 == end_line 且缩进 0
    for j in range(s, len(lines)):
        if lines[j] == end_line:
            return (s2, j)
    raise RuntimeError('no end for ' + sig_start)

# 各块(1-based 转 0-based 由 find_idx 完成)
B = {}
B['outputLineCount'] = block('function outputLineCount(')
B['argsPreview'] = block('function argsPreview(')
B['extractResultImages'] = block('function extractResultImages(')
B['thumbVariant'] = block('function thumbVariant(')
B['ReasoningRow'] = block('function ReasoningRow(')
B['ToolRow'] = block('function ToolRow(')
B['ToolDetail'] = block('function ToolDetail(')
B['LiveOutput'] = block('function LiveOutput(')
B['Section'] = block('function Section(')
B['StepRow'] = block('function StepRow(')
B['ContextRow'] = block('function ContextRow(')
B['ContextFileRow'] = block('function ContextFileRow(')
B['formatBytes'] = block('function formatBytes(')
B['AgentThoughtBlock'] = block('export function AgentThoughtBlock(')
B['AgentProcessBlock'] = block('export function AgentProcessBlock(')
B['TurnMeta'] = block('export function TurnMeta(')

for k, (s, e) in sorted(B.items(), key=lambda kv: kv[1][0]):
    print('%-22s %4d-%4d' % (k, s+1, e+1))

# 重叠检查
allr = sorted(B.values())
for a, b in zip(allr, allr[1:]):
    assert a[1] < b[0], 'overlap %s %s' % (a, b)
print('no overlaps')

def seg(key):
    s, e = B[key]
    return '\n'.join(lines[s:e+1])

# ============ ToolStepRow.tsx ============
tool = []
tool.append('import { AlertTriangle, Ban, Check, ChevronDown, Loader2, Wrench } from "lucide-react";')
tool.append('import { useEffect, useRef, useState } from "react";')
tool.append('import type { ChatStep } from "@/lib/api/chatApi";')
tool.append('import { GalleryBlock, parseGalleryFence } from "./GalleryBlock";')
tool.append('import { ImageLightbox } from "@/components/shared/ImageLightbox";')
tool.append('')
tool.append('/**')
tool.append(' * 工具步骤行(2026-09-17 从 AgentThoughtBlock 拆出):单行 chip(名称 +')
tool.append(' * 参数摘要 + 状态),可展开参数/结果详情;MCP 结果的图片/画廊直出。')
tool.append(' */')
tool.append('')
tool.append(seg('outputLineCount'))
tool.append('')
tool.append(seg('argsPreview'))
tool.append('')
tool.append(seg('extractResultImages'))
tool.append('')
tool.append(seg('thumbVariant'))
tool.append('')
tool.append(seg('ToolRow'))
tool.append('')
tool.append(seg('ToolDetail'))
tool.append('')
tool.append(seg('LiveOutput'))
tool.append('')
tool.append(seg('Section'))
tool_text = '\n'.join(tool)
# 导出 ToolRow(StepRow 用)
tool_text = tool_text.replace('function ToolRow({ step }: { step: ChatStep }) {',
                              'export function ToolRow({ step }: { step: ChatStep }) {')
io.open(TOOL, 'w', encoding='utf-8', newline='\n').write(tool_text + '\n')
print('written:', TOOL)

# ============ ContextStepRow.tsx ============
ctx = []
ctx.append('import { Check, ChevronDown, FileText } from "lucide-react";')
ctx.append('import { useState } from "react";')
ctx.append('import type { ChatStep, ContextFile } from "@/lib/api/chatApi";')
ctx.append('')
ctx.append('/**')
ctx.append(' * 注入上下文步骤行(2026-09-17 从 AgentThoughtBlock 拆出,对齐 dsh 的注入')
ctx.append(' * 可见性):折叠一行「注入 N 个文件/条目」,展开按 form 渲染——')
ctx.append(' * instructions=文件清单+日记清单,catalog=条目列表;未知 form 降级通用展示。')
ctx.append(' */')
ctx.append('')
ctx.append(seg('ContextRow'))
ctx.append('')
ctx.append(seg('ContextFileRow'))
ctx.append('')
ctx.append(seg('formatBytes'))
ctx_text = '\n'.join(ctx)
ctx_text = ctx_text.replace('function ContextRow({ step }: { step: ChatStep }) {',
                            'export function ContextRow({ step }: { step: ChatStep }) {')
io.open(CTX, 'w', encoding='utf-8', newline='\n').write(ctx_text + '\n')
print('written:', CTX)

# ============ 重建 AgentThoughtBlock.tsx ============
# 移除 Tool 系 + Context 系块
remove_keys = ['outputLineCount', 'argsPreview', 'extractResultImages', 'thumbVariant',
               'ToolRow', 'ToolDetail', 'LiveOutput', 'Section',
               'ContextRow', 'ContextFileRow', 'formatBytes']
skip = set()
for k in remove_keys:
    s, e = B[k]
    skip.update(range(s, e+1))
kept = [l for i, l in enumerate(lines) if i not in skip]
main = '\n'.join(kept)

# 新 imports
main = main.replace(
    'import { AlertTriangle, Brain, Check, ChevronDown, ChevronRight, FileText, Info, Loader2, Wrench, Ban } from "lucide-react";',
    'import { AlertTriangle, Brain, ChevronDown, ChevronRight, Info } from "lucide-react";')
main = main.replace(
    'import type { ChatStep, ContextFile } from "@/lib/api/chatApi";',
    'import type { ChatStep } from "@/lib/api/chatApi";')
main = main.replace(
    'import { GalleryBlock, parseGalleryFence, type GalleryData } from "./GalleryBlock";',
    'import { GalleryBlock, parseGalleryFence, type GalleryData } from "./GalleryBlock";\n'
    'import { ToolRow } from "./ToolStepRow";\n'
    'import { ContextRow } from "./ContextStepRow";')
# ImageLightbox import 不再需要(移走了)
main = main.replace('import { ImageLightbox } from "@/components/shared/ImageLightbox";\n', '')
# useEffect 还需要吗?AgentThoughtBlock 主文件只剩 ReasoningRow(useState)/AgentProcessBlock(useState)/TurnMeta(无)
main = main.replace('import { useEffect, useRef, useState } from "react";',
                    'import { useState } from "react";')

io.open(SRC, 'w', encoding='utf-8', newline='\n').write(main)
print('main: %d -> %d lines' % (len(lines), len(kept)))
