export function PreviewSkeleton() {
  return (
    <div className="bg-card border border-border rounded-lg p-8 min-h-[380px] space-y-4 animate-in fade-in">
      <div className="h-5 w-1/3 bg-muted rounded-full animate-pulse"></div>
      <div className="h-2.5 w-full bg-muted rounded-full animate-pulse"></div>
      <div className="h-2.5 w-11/12 bg-muted rounded-full animate-pulse"></div>
      <div className="h-2.5 w-4/5 bg-muted rounded-full animate-pulse"></div>
      <div className="h-32 w-full bg-muted rounded-lg animate-pulse mt-6"></div>
      <div className="h-2.5 w-3/4 bg-muted rounded-full animate-pulse"></div>
      <div className="h-2.5 w-2/3 bg-muted rounded-full animate-pulse"></div>
    </div>
  );
}
