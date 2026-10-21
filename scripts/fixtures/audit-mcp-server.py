"""Official MCP SDK fixture: stdio ownership, rich results and write-then-error."""
import asyncio
import base64
import json
import os
from pathlib import Path
import time

from mcp.server.lowlevel import Server
from mcp.server.stdio import stdio_server
from mcp.shared.exceptions import MCPError
import mcp.types as t

root = Path(os.environ["NORA_AUDIT_DIR"])
root.mkdir(parents=True, exist_ok=True)
with (root / "starts.jsonl").open("a", encoding="utf8") as log:
    log.write(json.dumps({"pid": os.getpid(), "credential": os.environ.get("NORA_AUDIT_CREDENTIAL")}) + "\n")

if os.environ.get("NORA_AUDIT_FAIL_INIT") == "1":
    # Deliberately close the protocol pipes while the child remains alive.
    os.close(0)
    os.close(1)
    time.sleep(120)
    raise SystemExit(1)


async def tools(_context, _params):
    return t.ListToolsResult(tools=[t.Tool(name=name, description=description,
        inputSchema={"type": "object", "properties": {}, "additionalProperties": False},
        annotations=t.ToolAnnotations(readOnlyHint=name != "audit_write_then_error"))
        for name, description in [
            ("audit_rich", "Return structured data, embedded text and a resource link."),
            ("audit_write_then_error", "Append one durable receipt and then raise an RPC internal error. Do not retry."),
            ("audit_bad_image", "Return a corrupt PNG as a controlled validation test."),
            ("audit_good_image", "Return the valid test PNG for visual reading."),
        ]])


async def call(_context, params):
    if params.name == "audit_rich":
        return t.CallToolResult(content=[
            t.EmbeddedResource(resource=t.TextResourceContents(uri="audit://embedded", mimeType="text/plain", text="EMBEDDED_CANARY_428")),
            t.ResourceLink(name="receipt", uri="audit://receipt", mimeType="text/plain", description="RESOURCE_LINK_CANARY_729"),
        ], structuredContent={"result": "STRUCTURED_CANARY_613", "credential": os.environ.get("NORA_AUDIT_CREDENTIAL")})
    if params.name == "audit_write_then_error":
        with (root / "writes.txt").open("a", encoding="utf8") as log:
            log.write("written-once\n")
        raise MCPError(-32603, "controlled internal error after the write")
    if params.name in ("audit_bad_image", "audit_good_image"):
        data = b"not a PNG" if params.name == "audit_bad_image" else (root / "valid.png").read_bytes()
        return t.CallToolResult(content=[t.ImageContent(type="image", mimeType="image/png", data=base64.b64encode(data).decode())])
    raise MCPError(-32602, "unknown fixture tool")


async def main():
    server = Server("Nora architecture audit", version="1", on_list_tools=tools, on_call_tool=call)
    async with stdio_server() as streams:
        await server.run(*streams, server.create_initialization_options())


if __name__ == "__main__":
    asyncio.run(main())
