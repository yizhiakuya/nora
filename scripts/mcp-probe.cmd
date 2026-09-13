@echo off
curl.exe -s --max-time 25 -X POST "https://home.rainaki.top:8900/mcp" -H "Content-Type: application/json" -H "Authorization: Bearer eacb42b57c284ab0ae1a9f1e" --data-binary "@D:\claude\Nora\scripts\mcp-probe.json"
