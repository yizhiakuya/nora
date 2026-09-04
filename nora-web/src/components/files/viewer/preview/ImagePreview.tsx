import { FilePreview } from "@/types";

export function ImagePreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-[#f0f2f5] dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-800 p-6 flex items-center justify-center min-h-[380px]">
      <img src={preview.imageUrl} alt="图片预览" className="max-w-full max-h-[420px] rounded-lg shadow-md" />
    </div>
  );
}

