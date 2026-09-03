import { Bell } from "lucide-react";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export function NotificationBell() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <div className="relative cursor-pointer ml-1 sm:ml-2">
          <div className="w-8 h-8 flex items-center justify-center rounded-full hover:bg-gray-100 border border-transparent hover:border-gray-200 transition-colors">
            <Bell className="w-4 h-4 text-gray-500" />
          </div>
          <span className="absolute top-1.5 right-1.5 w-2 h-2 bg-red-500 rounded-full border border-white animate-pulse"></span>
        </div>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="end" className="w-80 rounded-xl shadow-lg border-gray-200 p-0">
        <div className="bg-gray-50 px-4 py-3 border-b border-gray-100 flex items-center justify-between rounded-t-xl">
          <span className="text-xs font-bold text-gray-700">系统通知 (2)</span>
          <span className="text-[10px] text-blue-600 hover:underline cursor-pointer">全部标记为已读</span>
        </div>
        <div className="max-h-[300px] overflow-y-auto">
          <DropdownMenuItem className="p-3 cursor-pointer flex items-start gap-3 border-b border-gray-50 rounded-none focus:bg-blue-50/50">
            <div className="w-2 h-2 bg-blue-500 rounded-full mt-1.5 shrink-0"></div>
            <div>
              <div className="text-xs font-bold text-gray-800 mb-0.5">任务执行完成</div>
              <div className="text-[11px] text-gray-500 line-clamp-2">智能体 &quot;数据分析专家&quot; 已成功完成「竞品分析数据.xlsx 数据清洗」任务。</div>
              <div className="text-[9px] text-gray-400 mt-1">10 分钟前</div>
            </div>
          </DropdownMenuItem>
          <DropdownMenuItem className="p-3 cursor-pointer flex items-start gap-3 border-b border-gray-50 rounded-none focus:bg-blue-50/50">
            <div className="w-2 h-2 bg-blue-500 rounded-full mt-1.5 shrink-0"></div>
            <div>
              <div className="text-xs font-bold text-gray-800 mb-0.5">系统升级提醒</div>
              <div className="text-[11px] text-gray-500 line-clamp-2">AI 工作台后端模型服务已升级至 GPT-4o 最新版本，生成速度提升 30%。</div>
              <div className="text-[9px] text-gray-400 mt-1">2 小时前</div>
            </div>
          </DropdownMenuItem>
        </div>
        <div className="bg-gray-50 px-4 py-2 border-t border-gray-100 flex items-center justify-center rounded-b-xl cursor-pointer hover:bg-gray-100 transition-colors">
          <span className="text-xs text-gray-500 font-medium">查看历史通知</span>
        </div>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
