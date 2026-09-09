'use client';

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useServices } from "@/hooks/useServices";

const SOURCE_KINDS: { value: "FILE" | "DOCKER" | "PROC"; label: string }[] = [
  { value: "FILE", label: "📄 进程日志文件" },
  { value: "DOCKER", label: "🐳 Docker 容器" },
  { value: "PROC", label: "⚙️ 本地程序(托管)" },
];

interface AddSourceModalProps {
  isOpen: boolean;
  onClose: () => void;
}

/** 添加纳管源(FILE 日志文件 / DOCKER 容器 / PROC 平台托管程序);走 env-service,空态引导用户接入真实环境 */
export function AddSourceModal({ isOpen, onClose }: AddSourceModalProps) {
  const addManaged = useServices((s) => s.addManaged);
  const [kind, setKind] = useState<"FILE" | "DOCKER" | "PROC">("FILE");
  const [name, setName] = useState("");
  const [fileLogPath, setFileLogPath] = useState("");
  const [containerName, setContainerName] = useState("");
  const [command, setCommand] = useState("");
  const [workDir, setWorkDir] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 每次打开重置表单
  useEffect(() => {
    if (isOpen) {
      setKind("FILE");
      setName("");
      setFileLogPath("");
      setContainerName("");
      setCommand("");
      setWorkDir("");
      setError(null);
    }
  }, [isOpen]);

  const handleSubmit = async () => {
    if (!name.trim()) {
      setError("名称不能为空");
      return;
    }
    if (kind === "FILE" && !fileLogPath.trim()) {
      setError("日志文件路径不能为空");
      return;
    }
    if (kind === "DOCKER" && !containerName.trim()) {
      setError("容器名不能为空");
      return;
    }
    if (kind === "PROC" && !command.trim()) {
      setError("启动命令不能为空");
      return;
    }
    setSubmitting(true);
    setError(null);
    try {
      await addManaged({
        kind,
        name: name.trim(),
        fileLogPath: kind === "FILE" ? fileLogPath.trim() : undefined,
        containerName: kind === "DOCKER" ? containerName.trim() : undefined,
        command: kind === "PROC" ? command.trim() : undefined,
        workDir: kind === "PROC" && workDir.trim() ? workDir.trim() : undefined,
      });
      toast.success(`纳管源「${name.trim()}」已添加`);
      onClose();
    } catch (e) {
      setError((e as Error).message || "添加失败,请稍后重试");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="添加纳管服务"
      width="w-[440px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => void handleSubmit()} disabled={submitting}>
            {submitting ? "添加中…" : "添加"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">源类型</label>
          <Select value={kind} onValueChange={(v) => { setKind(v as typeof kind); setError(null); }}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {SOURCE_KINDS.map((k) => (
                <SelectItem key={k.value} value={k.value}>{k.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
          <p className="text-[10px] text-muted-foreground">
            {kind === "FILE"
              ? "进程不由平台启停,仅观测日志;容器/程序源支持启停与守护重启"
              : kind === "DOCKER"
                ? "容器名以 docker ps 里的 Names 为准,平台可启停"
                : "平台直接拉起本地程序(java -jar / node / 任意命令),stdout/stderr 重定向到受管日志,崩溃自动拉起"}
          </p>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">名称</label>
          <Input
            placeholder="例如:my-app-service"
            className="h-9 text-sm"
            value={name}
            onChange={(e) => { setName(e.target.value); setError(null); }}
          />
        </div>

        {kind === "FILE" ? (
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">日志文件路径</label>
            <Input
              placeholder="D:\logs\my-app.stdout.log"
              className="h-9 text-sm font-mono"
              value={fileLogPath}
              onChange={(e) => { setFileLogPath(e.target.value); setError(null); }}
            />
            <p className="text-[10px] text-muted-foreground">宿主机上的绝对路径,env-service 持续 tail 该文件</p>
          </div>
        ) : kind === "DOCKER" ? (
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">容器名</label>
            <Input
              placeholder="例如:nora-redis"
              className="h-9 text-sm font-mono"
              value={containerName}
              onChange={(e) => { setContainerName(e.target.value); setError(null); }}
            />
          </div>
        ) : (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">启动命令</label>
              <Input
                placeholder="例如:java -jar D:\app\my-app.jar --port=9090"
                className="h-9 text-sm font-mono"
                value={command}
                onChange={(e) => { setCommand(e.target.value); setError(null); }}
              />
              <p className="text-[10px] text-muted-foreground">按空格拆分参数;含空格的路径暂不支持引号,可用 bat 包装</p>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">工作目录(可选)</label>
              <Input
                placeholder="例如:D:\app"
                className="h-9 text-sm font-mono"
                value={workDir}
                onChange={(e) => setWorkDir(e.target.value)}
              />
            </div>
          </>
        )}

        {error && <p className="text-[11px] text-red-500 dark:text-red-400">{error}</p>}
      </div>
    </Modal>
  );
}
