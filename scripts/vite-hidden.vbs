' Nora web dev server (vite) launcher - runs with NO visible window.
' Usage: wscript.exe D:\claude\Nora\scripts\vite-hidden.vbs
Set sh = CreateObject("WScript.Shell")
sh.Run "cmd /c cd /d D:\claude\Nora\nora-web && npm run dev > D:\claude\Nora\nora-web.stdout.log 2> D:\claude\Nora\nora-web.stderr.log", 0, False
