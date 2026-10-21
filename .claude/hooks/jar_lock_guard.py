"""Windows Maven 打包前检查本应用 jar 是否仍被 Java 使用。"""
import json
from pathlib import Path
import re
import subprocess
import sys


def main():
    if sys.platform != "win32":
        return
    try:
        command = (json.load(sys.stdin).get("tool_input") or {}).get("command", "")
    except (ValueError, AttributeError):
        return
    if not re.search(r"\bmvn(?:\.cmd)?\b.*\b(?:package|verify|install|deploy|repackage)\b", command):
        return
    jar = str(Path(__file__).resolve().parents[2] / "nora-api/target/nora-api-0.1.0-SNAPSHOT.jar")
    literal = jar.replace("'", "''")
    script = ("Get-CimInstance Win32_Process -Filter \"Name LIKE 'java%'\" | "
              "Where-Object { $_.CommandLine -and $_.CommandLine.IndexOf('" + literal
              + "', [StringComparison]::OrdinalIgnoreCase) -ge 0 } | Select-Object -ExpandProperty ProcessId")
    try:
        result = subprocess.run(["powershell", "-NoProfile", "-Command", script],
                                capture_output=True, text=True, timeout=15, check=True)
    except (OSError, subprocess.SubprocessError) as error:
        print("Unable to inspect the Nora jar lock: " + str(error), file=sys.stderr)
        return
    pids = result.stdout.split()
    if pids:
        print("Nora jar is in use (PID " + ", ".join(pids)
              + "). Stop the owned application before Maven packaging; use ./nora.sh stop.", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
