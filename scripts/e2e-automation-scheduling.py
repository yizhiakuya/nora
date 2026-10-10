"""本机真实服务验收：到期任务并发启动、领取与记录原子提交。

使用隔离 PostgreSQL 连接及三个 SQL 规则；一条规则的记录插入故障不应吞掉计划点。
"""
from datetime import datetime, timedelta
import json
from pathlib import Path
import subprocess
import time
from urllib.request import ProxyHandler, Request, build_opener


def main():
    opener = build_opener(ProxyHandler({}))
    stamp = str(time.time_ns())
    prefix = "scheduler-e2e-" + stamp
    report = {"checks": []}
    path = Path("logs") / (prefix + ".json")
    path.parent.mkdir(parents=True, exist_ok=True)
    rules = []
    connection_id = None
    function = "e2e_exec_fail_" + stamp
    injected = False

    def api(port, suffix, body=None, method=None):
        data = None if body is None else json.dumps(body).encode()
        with opener.open(Request("http://127.0.0.1:" + str(port) + "/api" + suffix, data,
                                 {"Content-Type": "application/json"}, method=method), timeout=20) as response:
            return json.load(response)["data"]

    def sql(statement):
        result = subprocess.run(["rtk", "proxy", "docker", "exec", "-i", "nora-postgres", "psql", "-U", "nora", "-d", "nora", "-tA", "-v", "ON_ERROR_STOP=1"],
                                input=statement, text=True, encoding="utf-8", capture_output=True, check=True)
        return result.stdout.strip()

    def check(condition, label):
        report["checks"].append({"label": label, "passed": bool(condition)})
        path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
        print("PASS" if condition else "FAIL", label, flush=True)
        if not condition:
            raise RuntimeError(label)

    try:
        connection_id = api(18084, "/datasources", {"name": prefix, "engine": "postgresql", "host": "127.0.0.1",
                                                     "port": 15432, "database": "nora", "username": "nora", "password": "nora"}, "POST")["id"]
        schedule = json.dumps({"frequency": "daily", "localTime": "23:59", "timezone": "Asia/Shanghai"})
        for i in range(3):
            rule = api(18086, "/automations", {"name": prefix + "-" + str(i), "triggerType": "daily", "actionType": "sql",
                                                "sql": "SELECT pg_sleep(6), 'verified' AS proof", "connectionId": connection_id,
                                                "schedule": schedule}, "POST")
            rules.append(rule["id"])
        failing = rules[-1]
        sql("CREATE FUNCTION schema_automation." + function + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.rule_id=" + str(failing)
            + " THEN RAISE EXCEPTION 'controlled execution record failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER " + function
            + " BEFORE INSERT ON schema_automation.execution_record FOR EACH ROW EXECUTE FUNCTION schema_automation." + function + "();")
        injected = True
        scheduled = (datetime.now() - timedelta(seconds=5)).isoformat(timespec="seconds")
        sql("UPDATE schema_automation.automation_rule SET next_run_at='" + scheduled + "' WHERE id IN (" + ",".join(map(str, rules)) + ");")
        print("Waiting for the real scheduler scan", flush=True)
        deadline = time.monotonic() + 95
        seen_running = set()
        records = []
        while time.monotonic() < deadline:
            records = [r for r in api(18086, "/automations/executions") if r["ruleId"] in rules[:2]]
            seen_running.update(r["ruleId"] for r in records if r["status"] in ("queued", "running"))
            if len(records) == 2 and all(r["status"] == "success" for r in records):
                break
            time.sleep(0.4)
        check(len(records) == 2 and all(r["status"] == "success" for r in records), "一个领取失败的规则不会阻塞其他到期任务")
        check(seen_running == set(rules[:2]), "执行前已创建可观察的 queued/running 记录")
        starts = [datetime.fromisoformat(r["startedAt"]) for r in records]
        check(abs((starts[0] - starts[1]).total_seconds()) < 2 and all(r["durationMs"] >= 5900 for r in records), "两条六秒命令并发开始，startedAt 是真实开始时间")
        check(sql("SELECT next_run_at FROM schema_automation.automation_rule WHERE id=" + str(failing) + ";").replace(" ", "T") == scheduled,
              "执行记录写入失败时计划点领取一起回滚")
        check(sql("SELECT count(*) FROM schema_automation.execution_record WHERE rule_id=" + str(failing) + ";") == "0", "领取失败没有伪造执行记录")
    finally:
        if injected:
            sql("DROP TRIGGER IF EXISTS " + function + " ON schema_automation.execution_record; DROP FUNCTION IF EXISTS schema_automation." + function + "();")
        for rule_id in rules:
            api(18086, "/automations/" + str(rule_id), method="DELETE")
        if connection_id is not None:
            api(18084, "/datasources/" + str(connection_id), method="DELETE")
        print("REPORT", path, flush=True)


if __name__ == "__main__":
    main()
