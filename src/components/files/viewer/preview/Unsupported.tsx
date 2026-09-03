import { FileQuestion, Download } from "lucide-react";
import { Button } from "@/components/ui/button";
import { toast } from "sonner";

export function Unsupported({ fileName }: { fileName: string }) {
  return (
    <div className="py-16 flex flex-col items-center justify-center text-center">
      <div className="w-16 h-16 bg-gray-50 dark:bg-gray-900 border border-gray-100 dark:border-gray-800 rounded-full flex items-center justify-center mb-4">
        <FileQuestion className="w-8 h-8 text-gray-300 dark:text-gray-600" />
      </div>
      <h3 className="text-sm font-bold text-gray-700 dark:text-gray-200 mb-1">该文件类型暂不支持在线预览</h3>
      <p className="text-xs text-gray-500 dark:text-gray-400 mb-5">下载后使用本地应用打开：{fileName}</p>
      <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => toast.success(`已开始下载 ${fileName}`)}>
        <Download className="w-3.5 h-3.5 mr-1.5" /> 下载文件
      </Button>
    </div>
  );
}
