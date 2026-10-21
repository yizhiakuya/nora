"""Run existing acceptance suites against a real Nora backend (see docs/dev/acceptance.md)."""
import argparse
import os
from pathlib import Path
import subprocess
import sys
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("suite", choices=["standards", "core", "builtins", "annotated", "catalog", "steering", "live"])
    parser.add_argument("--base", default=os.environ.get("NORA_E2E_BASE_URL", "http://127.0.0.1:18080"))
    parser.add_argument("--provider", type=int)
    parser.add_argument("--model")
    options = parser.parse_args()
    if options.suite != "standards" and (options.provider is None or not options.model):
        parser.error("model suites require --provider and --model for a real enabled provider")
    root = Path(__file__).resolve().parent.parent
    environment = {**os.environ, "NORA_E2E_BASE_URL": options.base, "PYTHONUTF8": "1"}
    if options.suite == "standards":
        commands = [[sys.executable, "scripts/e2e-standards.py"]]
    elif options.suite in ("core", "builtins", "annotated", "catalog", "steering"):
        commands = [[sys.executable, "scripts/e2e-single-app.py", "--base", options.base,
                     "--provider", str(options.provider), "--model", options.model,
                     "--case", "all" if options.suite == "core" else options.suite]]
    else:
        report = root / "logs" / "live-turn-e2e" / ("acceptance-" + str(time.time_ns()) + ".json")
        commands = [[sys.executable, "scripts/e2e-live-turn.py", "--base", options.base,
                     "--provider", str(options.provider), "--model", options.model, "--lines", "1200",
                     "--report", str(report)], ["node", "scripts/e2e-live-turn-client.mjs", str(report)]]
    for command in commands:
        subprocess.run(command, cwd=root, env=environment, check=True)


if __name__ == "__main__":
    main()
