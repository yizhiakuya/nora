"""真实命令、历史保存/重放与真实模型请求；可读取私有留存记录验收损坏参数。"""
import argparse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import socket
import subprocess
import threading
import time
from urllib.error import HTTPError
from urllib.parse import urlsplit
from urllib.request import ProxyHandler, Request, build_opener
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--history', type=Path, required=True)
    parser.add_argument('--production', action='store_true')
    parser.add_argument('--provider', default='nora-a')
    options = parser.parse_args()
    stamp = str(time.time_ns())
    docker = ['docker'] if options.production else ['rtk', 'proxy', 'docker']
    opener = build_opener(ProxyHandler({}))
    base = 'http://127.0.0.1:18080'
    relay_host = '127.0.0.1'
    if options.production:
        container = json.loads(subprocess.check_output([*docker, 'inspect', 'nora-agent']))[0]
        address = next(v['IPAddress'] for v in container['NetworkSettings']['Networks'].values())
        base = 'http://' + address + ':18080'
        relay_host = '172.17.0.1'
    model = 'deepseek-v4.1-flash'
    report = {'checks': [], 'sessions': [], 'requestCount': 0}
    output = Path('logs/tool-history-e2e-' + stamp + '.json')
    output.parent.mkdir(exist_ok=True)

    def check(value, label):
        report['checks'].append({'label': label, 'passed': bool(value)})
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
        if not value:
            raise AssertionError(label)
        print('PASS', label, flush=True)

    def sql(query):
        return subprocess.run([*docker, 'exec', '-i', 'nora-postgres', 'psql', '-U', 'nora', '-d', 'nora', '-At', '-v', 'ON_ERROR_STOP=1'],
            input=query.encode(), capture_output=True, check=True).stdout.decode()

    def literal(value):
        return "'" + value.replace("'", "''") + "'"

    def api(path, data=None, method=None):
        with opener.open(Request(base + path, None if data is None else json.dumps(data, ensure_ascii=False).encode(),
                {'Content-Type': 'application/json'}, method=method), timeout=30) as response:
            value = json.load(response)
            return value.get('data', value) if isinstance(value, dict) else value

    source = next(p for p in api('/api/models/providers') if p['name'] == options.provider and p['enabled'])
    endpoint = urlsplit(source['endpoint'])
    origin = endpoint.scheme + '://' + endpoint.netloc
    provider = int(next(line for line in sql("INSERT INTO schema_agent.model_provider(name,protocol,endpoint,api_key,enabled,models,status,model_settings) "
        f"SELECT 'history-e2e-{stamp}',protocol,endpoint,api_key,true,models,status,model_settings FROM schema_agent.model_provider "
        f"WHERE id={source['id']} RETURNING id;").splitlines() if line.isdigit()))
    requests = []

    class Relay(BaseHTTPRequestHandler):
        drop_next = False

        def log_message(self, *_):
            pass

        def do_POST(self):
            body = self.rfile.read(int(self.headers['Content-Length']))
            requests.append(json.loads(body))
            report['requestCount'] = len(requests)
            if Relay.drop_next:
                Relay.drop_next = False
                self.connection.shutdown(socket.SHUT_RDWR)
                self.connection.close()
                return
            headers = {k: v for k, v in self.headers.items() if k.lower() not in ('host', 'content-length', 'connection')}
            try:
                response = opener.open(Request(origin + self.path, body, headers), timeout=180)
            except HTTPError as error:
                response = error
            with response:
                self.send_response(response.status)
                self.send_header('Content-Type', response.headers.get('Content-Type', 'text/event-stream'))
                self.end_headers()
                while chunk := response.read1(8192):
                    self.wfile.write(chunk)
                    self.wfile.flush()

    relay = ThreadingHTTPServer((relay_host, 0), Relay)
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    sessions = []

    def session(label):
        sid = 'tool-history-' + stamp + '-' + label
        sessions.append(sid)
        report['sessions'].append(sid)
        sql(f"INSERT INTO schema_agent.chat_session(id,title,title_generated) VALUES ({literal(sid)},{literal('工具历史验收 · ' + label)},true);")
        return sid

    def turn(sid, content):
        events = []
        payload = {'content': content, 'providerId': provider, 'model': model, 'reasoningLevel': 'none', 'permissionMode': 'full'}
        with opener.open(Request(base + '/api/chat/sessions/' + sid + '/messages', json.dumps(payload, ensure_ascii=False).encode(),
                {'Content-Type': 'application/json'}), timeout=180) as response:
            event = ''
            for raw in response:
                line = raw.decode().strip()
                if line.startswith('event:'):
                    event = line[6:].strip()
                elif line.startswith('data:'):
                    events.append((event, json.loads(line[5:])))
        done = next((data for event, data in reversed(events) if event == 'done'), {})
        check(done.get('status') == 'completed' and not any(event == 'error' for event, _ in events), '真实模型完成本轮请求')
        return events

    try:
        api(f'/api/models/providers/{provider}', {'endpoint': f'http://{relay_host}:{relay.server_port}' + endpoint.path.rstrip('/'),
            'modelSettings': {model: {'protocol': 'anthropic'}}}, 'PUT')
        sid = session('command')
        command = ('printf \'%s\\n\' "Authorization: Bearer canary-history-secret" "TOOL_HISTORY_OK"' if options.production else
            'Write-Output "Authorization: Bearer canary-history-secret"; Write-Output "TOOL_HISTORY_OK"')
        turn(sid, '这是安全的工具历史验收。只调用一次系统注册的命令工具，command 参数原样使用以下命令，无网络请求和文件写入：\n'
             + command + '\n完成后只回答 TOOL_HISTORY_OK，不进行其他操作。')
        history = api('/api/chat/sessions/' + sid + '/messages')
        steps = [step for message in history for step in (message.get('steps') or []) if step.get('toolName') in ('powershell', 'sh')]
        check(bool(steps) and all(step['status'] == 'completed' for step in steps), '安全命令实际执行并保存终态')
        check(all(isinstance(json.loads(step['input']['rawArgs']), dict) for step in steps), '带 Authorization 引号的命令参数脱敏后仍是合法 JSON')
        check('canary-history-secret' not in json.dumps(steps), '已保存的步骤不泄漏命令中的凭据值')
        before = len(requests)
        turn(sid, '本轮只回答 NEXT_TURN_OK，不调用工具。')
        check(len(requests) > before, '工具历史成功重放到下一轮真实上游请求')
        tool_inputs = [part.get('input') for request in requests[before:] for message in request.get('messages', [])
                      for part in message.get('content', []) if isinstance(part, dict) and part.get('type') == 'tool_use']
        check(bool(tool_inputs) and all(isinstance(value, dict) for value in tool_inputs)
              and 'canary-history-secret' not in json.dumps(tool_inputs), '上游历史参数结构正确且已脱敏')

        archived = json.loads(options.history.read_text(encoding='utf8'))
        broken = []
        for message in archived:
            entries = message.get('steps') or []
            if isinstance(entries, str):
                entries = json.loads(entries)
            for step in entries:
                raw = (step.get('input') or {}).get('rawArgs')
                if not raw:
                    continue
                try:
                    json.loads(raw)
                except json.JSONDecodeError:
                    broken.append(step)
        check(bool(broken), '私有留存包含线上实际损坏的参数记录')
        sid = session('archived')
        sql('INSERT INTO schema_agent.chat_message(id,session_id,role,content,steps) VALUES ('
            + ','.join(literal(value) for value in (str(uuid.uuid4()), sid, 'assistant', '已完成的历史工具记录', json.dumps(broken, ensure_ascii=False))) + ');')
        before = len(requests)
        events = turn(sid, '这是历史回放验收，旧任务已经结束。仅回答 HISTORY_REPLAY_OK，不执行任何工具或旧任务。')
        check(len(requests) > before and not any(event == 'step' and str(data.get('id', '')).startswith('s-retry') for event, data in events),
              '实际损坏历史不再破坏请求且无无效重试')
        sent = json.dumps(requests[before:], ensure_ascii=False)
        texts = '\n'.join(part.get('text', '') for request in requests[before:] for message in request.get('messages', [])
                          for part in message.get('content', []) if isinstance(part, dict) and part.get('type') == 'text')
        results = [step.get('result') or {} for step in broken]
        check('历史工具记录（参数已损坏，无法结构化重放）' in sent and all(step['toolName'] in sent for step in broken)
              and all((result.get('content') or result.get('error') or '(no output)') in texts for result in results),
              '损坏参数对应的已有工具记录和结果作为历史数据保留')
        Relay.drop_next = True
        before = len(requests)
        events = turn(sid, '只回答 NETWORK_RETRY_OK，不调用工具。')
        check(len(requests) > before + 1 and any(event == 'step' and str(data.get('id', '')).startswith('s-retry') for event, data in events),
              '真实传输断开仍可自动重试并得到真实模型回答')
    finally:
        for sid in sessions:
            if api('/api/chat/sessions/' + sid + '/turn/live').get('running'):
                api('/api/chat/sessions/' + sid + '/cancel', {}, 'POST')
        api(f'/api/models/providers/{provider}', method='DELETE')
        relay.shutdown()
        relay.server_close()
        output.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf8')
    print('REPORT', output, flush=True)


if __name__ == '__main__':
    main()
