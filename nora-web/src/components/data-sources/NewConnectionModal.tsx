'use client';

import { useState, useEffect } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useConnections } from "@/hooks/useConnections";
import { DbConnection } from "@/types";

const ENGINES: { value: DbConnection["engine"]; label: string; defaultPort: number }[] = [
  { value: "postgresql", label: "PostgreSQL", defaultPort: 5432 },
  { value: "mysql", label: "MySQL", defaultPort: 3306 },
  { value: "redis", label: "Redis（键值,只读浏览）", defaultPort: 6379 },
  { value: "sqlite", label: "SQLite（本地文件）", defaultPort: 0 },
];

interface NewConnectionModalProps {
  isOpen: boolean;
  onClose: () => void;
  onCreated?: (id: number) => void;
}

export function NewConnectionModal({ isOpen, onClose, onCreated }: NewConnectionModalProps) {
  const addConnection = useConnections((s) => s.addConnection);
  const [engine, setEngine] = useState<string>("postgresql");
  const [name, setName] = useState("");
  const [host, setHost] = useState("localhost");
  const [port, setPort] = useState("5432");
  const [database, setDatabase] = useState("");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 支持 postgresql/mysql/redis
  const engineOptions = ENGINES.filter((e) => e.value === "postgresql" || e.value === "mysql" || e.value === "redis");

  useEffect(() => {
    if (isOpen) {
      setEngine("postgresql");
      setName("");
      setHost("localhost");
      setPort("5432");
      setDatabase("");
      setUsername("");
      setPassword("");
      setError(null);
    }
  }, [isOpen]);

  const handleEngineChange = (v: string) => {
    setEngine(v);
    const found = ENGINES.find((e) => e.value === v);
    if (found) setPort(String(found.defaultPort));
    if (v === "sqlite") {
      setHost("—");
    } else {
      if (host === "—") setHost("localhost");
      // Redis 的"数据库"是逻辑库编号(0-15),给个默认值
      if (v === "redis" && !database.trim()) setDatabase("0");
    }
  };

  const handleSubmit = async () => {
    if (!name.trim()) {
      setError("连接名称不能为空");
      return;
    }
    if (engine !== "sqlite" && !host.trim()) {
      setError("主机不能为空");
      return;
    }
    if (!database.trim()) {
      setError(engine === "sqlite" ? "数据库文件路径不能为空"
        : engine === "redis" ? "逻辑库编号不能为空（默认 0）" : "数据库名不能为空");
      return;
    }
    // Redis 允许无认证(开发环境常见),用户名密码可留空
    if (engine !== "redis" && !username.trim()) {
      setError("用户名不能为空");
      return;
    }
    setSubmitting(true);
    try {
      const conn = await addConnection({
        name: name.trim(),
        engine: engine as DbConnection["engine"],
        host: host.trim(),
        port: Number(port) || 0,
        database: database.trim(),
        username: username.trim(),
        password,
      });
      if (conn.status === "connected") toast.success(`连接「${conn.name}」已建立`);
      else toast.error(`连接「${conn.name}」已保存,连通测试失败,请检查配置`);
      onCreated?.(conn.id);
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="新建数据源连接"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSubmit} disabled={submitting}>
            {submitting ? "连接中…" : "测试并连接"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">数据库类型</label>
          <Select value={engine} onValueChange={handleEngineChange}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {engineOptions.map((e) => (
                <SelectItem key={e.value} value={e.value}>{e.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">连接名称</label>
          <Input placeholder="例如：本地开发库 / 测试环境" className="h-9 text-sm" value={name} onChange={(e) => { setName(e.target.value); setError(null); }} />
        </div>

        <div className="grid grid-cols-3 gap-3">
          <div className="space-y-1.5 col-span-2">
            <label className="text-xs font-bold text-foreground">主机</label>
            <Input placeholder="localhost" className="h-9 text-sm font-mono" value={host} disabled={engine === "sqlite"} onChange={(e) => setHost(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">端口</label>
            <Input placeholder="5432" className="h-9 text-sm font-mono" value={engine === "sqlite" ? "—" : port} disabled={engine === "sqlite"} onChange={(e) => setPort(e.target.value)} />
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-foreground">
            {engine === "sqlite" ? "数据库文件路径" : engine === "redis" ? "逻辑库编号（0-15）" : "数据库名"}
          </label>
          <Input
            placeholder={engine === "sqlite" ? "./data/app.db" : engine === "redis" ? "0" : "myapp_dev"}
            className="h-9 text-sm font-mono"
            value={database}
            onChange={(e) => { setDatabase(e.target.value); setError(null); }}
          />
        </div>

        <div className="grid grid-cols-2 gap-3">
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">
                用户名{engine === "redis" && <span className="font-normal text-muted-foreground">（可选,ACL）</span>}
              </label>
              <Input
                placeholder={engine === "redis" ? "留空 = 仅密码/无认证" : "postgres"}
                className="h-9 text-sm font-mono"
                value={username}
                onChange={(e) => { setUsername(e.target.value); setError(null); }}
              />
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">
                密码{engine === "redis" && <span className="font-normal text-muted-foreground">（可选）</span>}
              </label>
              <Input
                type="password"
                placeholder="••••••••"
                className="h-9 text-sm font-mono"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
          </div>

        {error && <p className="text-[11px] text-red-500 dark:text-red-400">{error}</p>}
      </div>
    </Modal>
  );
}
