import { ListCheck, Clock, CheckCircle2, XCircle, AlertCircle, MoreHorizontal, Calendar, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { ErrorState } from "@/components/ui/custom/States";
import { Task } from "@/types";

const getStatusIcon = (status: string) => {
  switch (status) {
    case "completed": return <CheckCircle2 className="w-4 h-4 text-green-500 dark:text-green-400" />;
    case "running": return <Loader2 className="w-4 h-4 text-blue-500 dark:text-blue-400 animate-spin" />;
    case "scheduled": return <Clock className="w-4 h-4 text-orange-500 dark:text-orange-400" />;
    case "failed": return <XCircle className="w-4 h-4 text-red-500 dark:text-red-400" />;
    default: return <AlertCircle className="w-4 h-4 text-gray-500 dark:text-gray-400" />;
  }
};

const getStatusBadge = (status: string) => {
  switch (status) {
    case "completed": return <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-400 font-medium border border-green-100 dark:border-green-900">已完成</span>;
    case "running": return <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-400 font-medium border border-blue-100 dark:border-blue-900">运行中</span>;
    case "scheduled": return <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs bg-orange-50 dark:bg-orange-950/40 text-orange-700 dark:text-orange-400 font-medium border border-orange-100 dark:border-orange-900">已排期</span>;
    case "failed": return <span className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-xs bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-400 font-medium border border-red-100 dark:border-red-900">执行失败</span>;
    default: return null;
  }
};

interface TaskTableProps {
  tasks: Task[];
  isLoading: boolean;
  error: string | null;
  onRetry: () => void;
}

export function TaskTable({ tasks, isLoading, error, onRetry }: TaskTableProps) {
  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl shadow-sm overflow-hidden animate-in fade-in slide-in-from-bottom-4 duration-500 min-h-[400px] relative">
      {isLoading && (
        <div className="absolute inset-0 z-10 bg-white/60 dark:bg-gray-900/60 backdrop-blur-[1px] flex flex-col items-center justify-center animate-in fade-in">
          <Loader2 className="w-8 h-8 text-blue-500 dark:text-blue-400 animate-spin mb-2" />
          <span className="text-xs text-gray-500 dark:text-gray-400 font-medium">正在加载任务数据...</span>
        </div>
      )}

      {error ? (
        <ErrorState
          title="任务数据加载失败"
          description={error}
          action={
            <Button variant="outline" size="sm" className="h-7 text-xs bg-white dark:bg-gray-900" onClick={onRetry}>
              重试
            </Button>
          }
        />
      ) : (
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-gray-50 dark:bg-gray-900 border-b border-gray-200 dark:border-gray-800 text-xs text-gray-500 dark:text-gray-400 font-medium">
              <th className="p-4">任务名称</th>
              <th className="p-4">执行智能体</th>
              <th className="p-4">状态</th>
              <th className="p-4">触发时间</th>
              <th className="p-4">耗时</th>
              <th className="p-4 text-right">操作</th>
            </tr>
          </thead>
          <tbody className="text-sm">
            {!isLoading && tasks.length === 0 ? (
              <tr>
                <td colSpan={6} className="p-16 text-center text-gray-400 dark:text-gray-500">
                  <div className="flex flex-col items-center justify-center">
                    <ListCheck className="w-10 h-10 mb-3 opacity-20" />
                    <div className="text-sm">没有找到相关的任务记录</div>
                  </div>
                </td>
              </tr>
            ) : tasks.map((task) => (
              <tr key={task.id} className="border-b border-gray-100 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors group">
                <td className="p-4">
                  <div className="flex flex-col gap-1">
                    <span className="font-medium text-gray-800 dark:text-gray-100">{task.name}</span>
                    <span className="text-[10px] text-gray-400 dark:text-gray-500 font-mono">{task.id}</span>
                  </div>
                </td>
                <td className="p-4 text-gray-600 dark:text-gray-300 text-xs">
                  <span className="inline-flex items-center gap-1 bg-gray-100 dark:bg-gray-800 px-2 py-0.5 rounded text-gray-600 dark:text-gray-300">
                    {task.agent}
                  </span>
                </td>
                <td className="p-4">
                  <div className="flex items-center gap-2">
                    {getStatusIcon(task.status)}
                    {getStatusBadge(task.status)}
                  </div>
                </td>
                <td className="p-4 text-gray-500 dark:text-gray-400 text-xs flex items-center gap-1.5 mt-2">
                  <Calendar className="w-3 h-3 text-gray-400 dark:text-gray-500" /> {task.time}
                </td>
                <td className="p-4 text-gray-500 dark:text-gray-400 text-xs font-mono">{task.duration}</td>
                <td className="p-4 text-right">
                  <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 dark:text-gray-500 opacity-0 group-hover:opacity-100 transition-opacity hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40">
                    <MoreHorizontal className="w-4 h-4" />
                  </Button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}
