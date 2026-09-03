'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Sparkles, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { AgentConfigPanel } from "@/components/agents/AgentConfigPanel";
import { AgentDebugPanel } from "@/components/agents/AgentDebugPanel";
import { useTimedSequence } from "@/hooks/useTimedSequence";

export default function AgentsPage() {
  const [published, setPublished] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();

  const handlePublish = () => {
    cancelAll();
    setPublished(true);
    schedule(() => setPublished(false), 2000);
  };

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "AI 工作台", isCurrent: false },
          { label: "智能体", isCurrent: false },
          { label: "数据分析专家", isCurrent: true },
        ]}
        actions={
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white text-gray-700">
              测试运行
            </Button>
            <Button
              size="sm"
              className={"h-8 text-xs transition-colors " + (published ? "bg-green-600 hover:bg-green-700" : "bg-blue-600 hover:bg-blue-700")}
              onClick={handlePublish}
            >
              {published ? <><Check className="w-3.5 h-3.5 mr-1.5" /> 发布成功</> : <><Sparkles className="w-3.5 h-3.5 mr-1.5" /> 发布更新</>}
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-hidden flex">
        <AgentConfigPanel />
        <AgentDebugPanel />
      </div>
    </>
  );
}
