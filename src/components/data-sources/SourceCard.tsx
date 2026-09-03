import { Server, ArrowRightLeft, Key, Settings, Lock } from "lucide-react";
import { Button } from "@/components/ui/button";
import { DataSource } from "@/types";

export function SourceCard({ source }: { source: DataSource }) {
  const Icon = source.icon;
  return (
    <div className={`bg-white border ${source.status === "error" ? "border-red-200" : "border-gray-200"} rounded-xl p-5 hover:shadow-md transition-all relative flex flex-col group`}>
      <div className="flex justify-between items-start mb-4">
        <div className="flex items-center gap-3">
          <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${source.bg} ${source.color}`}>
            <Icon className="w-5 h-5" />
          </div>
          <div>
            <h3 className="text-sm font-bold text-gray-800">{source.name}</h3>
            <div className="text-xs text-gray-500 mt-0.5 font-mono">{source.type}</div>
          </div>
        </div>
        {source.status === "connected" ? (
          <div className="flex items-center gap-1.5 px-2 py-1 bg-green-50 text-green-700 border border-green-100 rounded text-[10px] font-bold">
            <div className="w-1.5 h-1.5 bg-green-500 rounded-full animate-pulse"></div>
            已连接
          </div>
        ) : (
          <div className="flex items-center gap-1.5 px-2 py-1 bg-red-50 text-red-700 border border-red-100 rounded text-[10px] font-bold">
            <div className="w-1.5 h-1.5 bg-red-500 rounded-full"></div>
            连接失败
          </div>
        )}
      </div>

      <div className="p-3 bg-gray-50 rounded-lg border border-gray-100 space-y-2 mb-4">
        <div className="flex items-center justify-between text-xs">
          <span className="text-gray-500 flex items-center gap-1.5"><Server className="w-3 h-3" /> 主机/路径:</span>
          <span className="text-gray-800 font-mono">{source.host}</span>
        </div>
        <div className="flex items-center justify-between text-xs">
          <span className="text-gray-500 flex items-center gap-1.5"><ArrowRightLeft className="w-3 h-3" /> 同步策略:</span>
          <span className={`${source.status === "error" ? "text-red-600" : "text-blue-600"}`}>{source.syncStatus}</span>
        </div>
      </div>

      <div className="flex items-center justify-between mt-auto pt-2">
        <div className="flex items-center gap-2 text-[10px] text-gray-400">
          <Lock className="w-3 h-3" /> 加密传输激活
        </div>
        <div className="flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
          <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600 hover:bg-blue-50">
            <Settings className="w-4 h-4" />
          </Button>
          <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600 hover:bg-blue-50">
            <Key className="w-4 h-4" />
          </Button>
        </div>
      </div>
    </div>
  );
}
