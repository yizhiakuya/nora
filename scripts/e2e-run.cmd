@echo off
set SID=%~1
curl.exe -sS --noproxy "*" -N -X POST "http://127.0.0.1:8083/api/chat/sessions/%SID%/messages" -H "Content-Type: application/json; charset=utf-8" --data-binary "@D:\claude\Nora\scripts\e2e-payload.json" --max-time 300 > "D:\claude\Nora\scripts\e2e-sse.txt" 2> "D:\claude\Nora\scripts\e2e-sse.err"
echo CURL_EXIT=%ERRORLEVEL%>> "D:\claude\Nora\scripts\e2e-sse.err"
