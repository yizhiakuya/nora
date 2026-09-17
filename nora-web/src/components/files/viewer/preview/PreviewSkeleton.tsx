export function PreviewSkeleton() {
  return (
    <div className="bg-white/5 backdrop-blur rounded-xl border border-white/10 p-8 min-h-[380px] space-y-4 animate-in fade-in">
      <div className="h-5 w-1/3 bg-white/10 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-full bg-white/10 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-11/12 bg-white/10 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-4/5 bg-white/10 rounded-full animate-pulse"></div>
      <div className="h-32 w-full bg-white/10 rounded-lg animate-pulse mt-6"></div>
      <div className="h-2.5 w-3/4 bg-white/10 rounded-full animate-pulse"></div>
      <div className="h-2.5 w-2/3 bg-white/10 rounded-full animate-pulse"></div>
    </div>
  );
}
