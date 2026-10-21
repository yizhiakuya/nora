"""真实模型长输出：完整快照、过期游标、等待提问、增量续传与历史一致性。"""
import argparse
import json
import os
import sys
from pathlib import Path
import threading
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--base', default=os.environ.get('NORA_E2E_BASE_URL', 'http://127.0.0.1:18080'))
    parser.add_argument('--provider', type=int, required=True)
    parser.add_argument('--model', required=True)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--hold', action='store_true', help='等待浏览器回答问题')
    parser.add_argument('--lines', type=int, default=1000)
    options = parser.parse_args()
    sid = 'live-recovery-' + str(time.time_ns())
    output = Path('logs/live-turn-e2e')
    output.mkdir(parents=True, exist_ok=True)
    report = options.report or output / (sid + '.json')
    report.parent.mkdir(parents=True, exist_ok=True)
    trace = {'sessionId': sid, 'events': [], 'checks': []}
    opener = build_opener(ProxyHandler({}))

    def save():
        report.write_text(json.dumps(trace, ensure_ascii=False), encoding='utf8')

    def check(value, label):
        trace['checks'].append({'label': label, 'passed': bool(value)})
        save()
        if not value:
            raise AssertionError(label)
        print('PASS', label, flush=True)

    def request(path, body=None, headers=None):
        return opener.open(Request(options.base + path, None if body is None else json.dumps(body, ensure_ascii=False).encode(),
            {'Content-Type': 'application/json', 'Authorization': 'Bearer ' + os.environ.get('NORA_AUTH_TOKEN', ''), **(headers or {})}), timeout=360)

    def api(path, body=None):
        with request(path, body) as response:
            value = json.load(response)
            return value.get('data', value) if isinstance(value, dict) else value

    def events(response):
        event = ''
        cursor = ''
        for raw in response:
            line = raw.decode().strip()
            if line.startswith('event:'):
                event = line[6:].strip()
            elif line.startswith('id:'):
                cursor = line[3:].strip()
            elif line.startswith('data:'):
                yield {'event': event, 'data': json.loads(line[5:]), 'cursor': cursor}

    waiting = threading.Event()
    finished = threading.Event()
    errors = []
    prompt = ('这是工作过程恢复的真实验收，只在当前会话操作，不读文件，不访问网络。按顺序完成：\n'
        '1. 调用一次当前平台的命令工具，只打印 LIVE_RECOVERY_STAGE_1。\n'
        f'2. 在聊天正文完整输出 {options.lines} 行编号文本，编号 0001 起，每行格式“0001：会话恢复验收，保留中间工作过程和完整输出。”'
        '必须直接生成全部正文，不使用工具代替、不保存文件、不省略。\n'
        '3. 输出完成后调用 ask_user 问“恢复验收，是否继续？”，选项“继续”和“停止”。\n'
        '4. 收到继续后，调用一次命令工具，只打印 LIVE_RECOVERY_STAGE_2，然后最终只回答 LIVE_RECOVERY_DONE。')

    def run():
        try:
            with request('/api/chat/sessions/' + sid + '/messages', {
                'content': prompt, 'providerId': options.provider, 'model': options.model,
                'reasoningLevel': 'none', 'permissionMode': 'full'}) as response:
                for event in events(response):
                    trace['events'].append(event)
                    if event['event'] == 'question_required':
                        waiting.set()
        except Exception as error:
            errors.append(repr(error))
        finally:
            finished.set()
            waiting.set()

    threading.Thread(target=run, daemon=True).start()
    print('SESSION', sid, flush=True)
    try:
        if not waiting.wait(240):
            raise TimeoutError('真实模型未到达提问步骤')
        check(not errors and not finished.is_set(), '真实模型执行前置命令、长正文并等待用户回答')
        live = api('/api/chat/sessions/' + sid + '/turn/live')
        trace['live'] = live
        check(live['lastSeq'] > 8000, '真实事件已超出 8000 条回放窗口')
        snapshots = []
        for headers in ({}, {'Last-Event-ID': live['turnId'] + ':1'}):
            with request('/api/chat/sessions/' + sid + '/turn/stream', headers=headers) as response:
                first = next(events(response))
                check(first['event'] == 'snapshot', '首次打开及过期游标均直接返回完整快照')
                snapshots.append(first)
        snapshot = snapshots[-1]
        trace['snapshot'] = snapshot
        steps = snapshot['data']['steps']
        ids = [step['id'] for step in steps]
        original_ids = list(dict.fromkeys(event['data']['id'] for event in trace['events'] if event['event'] == 'step'))
        check(len(ids) == len(set(ids)) and all(step_id in ids for step_id in original_ids), '快照保留窗口外全部步骤且无重复')
        check(any(step.get('toolName') in ('powershell', 'sh') and step['status'] == 'completed'
            and 'LIVE_RECOVERY_STAGE_1' in json.dumps(step.get('result')) for step in steps), '早期真实命令结果完整恢复')
        text = ''.join(event['data']['content'] for event in trace['events'] if event['event'] == 'delta')
        snapshot_text = ''.join(step.get('detail') or '' for step in steps if step['type'] in ('commentary', 'answer'))
        check(text == snapshot_text and len(text) > 8000, '完整正文与原始实时流逐字一致')
        reasoning = {}
        for event in trace['events']:
            if event['event'] == 'reasoning_delta':
                key = event['data'].get('roundIndex')
                reasoning[key] = reasoning.get(key, '') + event['data']['content']
        check(bool(reasoning) and all(next(step['detail'] for step in steps
            if step['id'] == 's-reasoning-' + ('final' if key is None else str(key))) == value for key, value in reasoning.items()),
            '每轮完整思考文本与原始实时流逐字一致')
        check(snapshot['data']['question']['questionToken'] == next(event['data']['questionToken']
            for event in trace['events'] if event['event'] == 'question_required') and bool(snapshot['data']['contextUsage']),
            '提问令牌和上下文计量完整恢复')
        print('READY_FOR_BROWSER', sid, flush=True)
        save()
        connection_started = time.monotonic()
        with request('/api/chat/sessions/' + sid + '/turn/stream', headers={'Last-Event-ID': snapshot['cursor']}) as response:
            check(time.monotonic() - connection_started < 5, '无积压事件时续传连接立即建立')
            if not options.hold:
                api('/api/chat/answers/' + snapshot['data']['question']['questionToken'] + '?sessionId=' + sid, {'answer': '继续'})
            resumed = []
            for event in events(response):
                resumed.append(event)
                if event['event'] in ('done', 'error', 'idle'):
                    break
        trace['resumed'] = resumed
        check(bool(resumed) and not any(event['event'] == 'snapshot' for event in resumed), '有效游标仅续传增量，无重复整段历史')
        check(finished.wait(30) and not errors and any(event['event'] == 'done' and event['data']['status'] == 'completed' for event in resumed),
            '回答恢复后的问题，原轮次正常完成')
        history = api('/api/chat/sessions/' + sid + '/messages')
        trace['history'] = history
        assistant = history[-1]
        check(assistant['content'].strip() == 'LIVE_RECOVERY_DONE' and all(step_id in [step['id'] for step in assistant['steps']] for step_id in ids),
            '最终历史保留全部工作过程及唯一最终回答')
        check(any('LIVE_RECOVERY_STAGE_2' in json.dumps(step.get('result')) and step['status'] == 'completed' for step in assistant['steps']),
            '恢复提问后第二条真实命令实际执行成功')
    finally:
        failing = sys.exc_info()[0] is not None
        try:
            if api('/api/chat/sessions/' + sid + '/turn/live').get('running'):
                api('/api/chat/sessions/' + sid + '/cancel', {})
                finished.wait(10)
            with opener.open(Request(options.base + '/api/chat/sessions/' + sid, method='DELETE',
                    headers={'Authorization': 'Bearer ' + os.environ.get('NORA_AUTH_TOKEN', '')}), timeout=30):
                pass
        except Exception as error:
            errors.append('cleanup: ' + repr(error))
            if not failing:
                raise
        finally:
            trace['errors'] = errors
            save()
    print('REPORT', report, flush=True)


if __name__ == '__main__':
    main()
