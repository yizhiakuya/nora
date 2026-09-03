export function PreviewSkeleton() {
  return (
    <div className="bg-white border border-gray-200 rounded-lg p-8 min-h-[380px] space-y-4 animate-in fade-in">
      <div className="h-5 w-1/3 bg-gray-100 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-full bg-gray-50 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-11/12 bg-gray-50 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-4/5 bg-gray-50 rounded-full animate-pulse"></div>
      <div className="h-32 w-full bg-gray-50 rounded-lg animate-pulse mt-6"></div>
      <div className="h-2.5 w-3/4 bg-gray-50 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-2/3 bg-gray-50 rounded-full animate-pulse"></div>
    </div>
  );
}
