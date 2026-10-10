"""从实际构建产物运行命令执行器的 OS E2E；需要 Java 21。"""
from pathlib import Path
import os
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
java = Path(os.getenv('JAVA_HOME', 'D:/tools/jdk-21')) / 'bin'
jar = root / 'nora-api/services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar'
dest = Path(tempfile.mkdtemp(prefix='nora-cmd-e2e-'))
with zipfile.ZipFile(jar) as archive:
    for item in archive.namelist():
        if item.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
            archive.extract(item, dest)
classpath = os.pathsep.join([str(dest / 'BOOT-INF/classes'), str(dest / 'BOOT-INF/lib/*'), str(dest)])
subprocess.run([str(java / 'javac'), '-encoding', 'UTF-8', '-cp', classpath, '-d', str(dest), str(root / 'scripts/CommandProcessProbe.java')], check=True)
print('PROBE_CLASSES', dest, flush=True)
subprocess.run([str(java / 'java'), '-cp', classpath, 'CommandProcessProbe'], check=True, timeout=90)
