"""验收本机 PostgreSQL/Redis 连接与 Schema，仅读取真实服务。

启动 gateway/datasource 和本地容器后，设置 NORA_AUTH_TOKEN，运行本文件。
"""
import runpy
from pathlib import Path


def run():
    data = runpy.run_path(str(Path(__file__).with_name("e2e-standards.py")))["data"]
    connections = {row["name"]: row for row in data("/datasources")}
    for name, port in (("nora-pg 本库", 15432), ("nora-redis 本机", 16379)):
        connection = connections.get(name)
        if connection is None or connection["host"] != "localhost" or connection["port"] != port:
            raise RuntimeError(f"{name}: 本机连接未使用容器映射端口 {port}")
        connection_id = connection["id"]
        result = data(f"/datasources/{connection_id}/test", "POST")
        if not result["ok"]:
            raise RuntimeError(f"{name}: 连接测试失败")
        tables = data(f"/datasources/{connection_id}/schema")["tables"]
        if not isinstance(tables, list) or (port == 15432 and not tables):
            raise RuntimeError(f"{name}: 未返回可用 Schema")
        print(f"PASS: {name} :{port}, {len(tables)} schema entries")


if __name__ == "__main__":
    run()
