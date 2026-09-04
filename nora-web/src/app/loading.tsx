import { Loader2 } from "lucide-react";

export default function RouteLoading() {
  return (
    <div className="flex-1 flex flex-col items-center justify-center gap-3">
      <Loader2 className="w-8 h-8 text-blue-500 dark:text-blue-400 animate-spin" />
      <span className="text-xs text-gray-400 dark:text-gray-500">正在加载...</span>
    </div>
  );
}
