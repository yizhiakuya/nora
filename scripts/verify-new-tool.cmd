@echo off
setlocal
rem Token 从本地密钥文件读取（.gitignore 已忽略，勿把 token 写进仓库）
set "TOKFILE=D:\claude\Nora\scripts\.phone-mcp-token"
if not exist "%TOKFILE%" (echo missing %TOKFILE% & exit /b 1)
set /p TOK=<"%TOKFILE%"
echo === 1. tools/list: check photos_review description has the new warnings ===
curl.exe -s --max-time 25 -X POST "https://home.rainaki.top:8900/mcp" -H "Content-Type: application/json" -H "Authorization: Bearer %TOK%" --data-binary "@D:\claude\Nora\scripts\probe-list.json" > "D:\claude\Nora\scripts\list-out.json"
findstr /C:"tile=160" /C:"列数比" /C:"粗筛" "D:\claude\Nora\scripts\list-out.json" >nul && echo   description updated: YES || echo   description updated: NO

echo.
echo === 2. photos_review with default tile (320) - check size hint in output ===
curl.exe -s --max-time 60 -X POST "https://home.rainaki.top:8900/mcp" -H "Content-Type: application/json" -H "Authorization: Bearer %TOK%" --data-binary "@D:\claude\Nora\scripts\probe-review.json" > "D:\claude\Nora\scripts\review-out.json"
findstr /C:"\u6bcf\u683c" /C:"tileUsed" /C:"px" "D:\claude\Nora\scripts\review-out.json" >nul && echo   size hint present: YES || echo   size hint present: (check file)
endlocal
