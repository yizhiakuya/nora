import { useEffect, useMemo, useRef, useState } from "react";
import { estimateTextTokens, formatTokenCount } from "@/lib/tokenEstimate";

export function ReasoningTokens({ text, active }: { text: string; active: boolean }) {
  const latestText = useRef(text);
  const [liveTokens, setLiveTokens] = useState(() => estimateTextTokens(text));

  useEffect(() => { latestText.current = text; }, [text]);
  useEffect(() => {
    if (!active) return;
    const timer = setInterval(() => setLiveTokens(estimateTextTokens(latestText.current)), 1000);
    return () => clearInterval(timer);
  }, [active]);

  const tokens = useMemo(() => active ? liveTokens : estimateTextTokens(text), [active, liveTokens, text]);
  return (
    <span
      className="text-[11px] text-muted-foreground/70 tabular-nums whitespace-nowrap"
      title="根据已收到的思考文本估算；整轮精确用量以上游统计为准"
    >
      ≈{formatTokenCount(tokens)} tokens
    </span>
  );
}
