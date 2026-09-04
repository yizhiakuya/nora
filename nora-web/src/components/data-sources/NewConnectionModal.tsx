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
  { value: "sqlite", label: "SQLite（本地文件）", defaultPort: 0 },
  { value: "redis", label: "Redis", defaultPort: 6379 },
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
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (isOpen) {
      setEngine("postgresql");
      setName("");
      setHost("localhost");
      setPort("5432");
      setDatabase("");
      setError(null);
    }
  }, [isOpen]);

  const handleEngineChange = (v: string) => {
    setEngine(v);
    const found = ENGINES.find((e) => e.value === v);
    if (found) setPort(String(found.defaultPort));
    if (v === "sqlite") setHost("—");
    else if (host === "—") setHost("localhost");
  };

  const handleSubmit = () => {
    if (!name.trim()) {
      setError("连接名称不能为空");
      return;
    }
    if (engine !== "sqlite" && !host.trim()) {
      setError("主机不能为空");
      return;
    }
    if (!database.trim()) {
      setError(engine === "sqlite" ? "数据库文件路径不能为空" : "数据库名不能为空");
      return;
    }
    const conn = addConnection({
      name: name.trim(),
      engine: engine as DbConnection["engine"],
      host: host.trim(),
      port: Number(port) || 0,
      database: database.trim(),
    });
    toast.success(`连接「${conn.name}」已建立`);
    onCreated?.(conn.id);
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="新建数据源连接"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleSubmit}>
            测试并连接
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="space-y-1.5">
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200">数据库类型</label>
          <Select value={engine} onValueChange={handleEngineChange}>
            <SelectTrigger className="w-full">
              <SelectValue />
            </SelectTrigger>
            <SelectContent>
              {ENGINES.map((e) => (
                <SelectItem key={e.value} value={e.value}>{e.label}</SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200">连接名称</label>
          <Input placeholder="例如：本地开发库 / 测试环境" className="h-9 text-sm" value={name} onChange={(e) => { setName(e.target.value); setError(null); }} />
        </div>

        <div className="grid grid-cols-3 gap-3">
          <div className="space-y-1.5 col-span-2">
            <label className="text-xs font-bold text-gray-700 dark:text-gray-200">主机</label>
            <Input placeholder="localhost" className="h-9 text-sm font-mono" value={host} disabled={engine === "sqlite"} onChange={(e) => setHost(e.target.value)} />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-gray-700 dark:text-gray-200">端口</label>
            <Input placeholder="5432" className="h-9 text-sm font-mono" value={engine === "sqlite" ? "—" : port} disabled={engine === "sqlite"} onChange={(e) => setPort(e.target.value)} />
          </div>
        </div>

        <div className="space-y-1.5">
          <label className="text-xs font-bold text-gray-700 dark:text-gray-200">
            {engine === "sqlite" ? "数据库文件路径" : "数据库名"}
          </label>
          <Input
            placeholder={engine === "sqlite" ? "./data/app.db" : "myapp_dev"}
            className="h-9 text-sm font-mono"
            value={database}
            onChange={(e) => { setDatabase(e.target.value); setError(null); }}
          />
        </div>

        {error && <p className="text-[11px] text-red-500 dark:text-red-400">{error}</p>}
      </div>
    </Modal>
  );
}
