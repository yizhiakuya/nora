import { useEffect, useRef, useState } from "react";
import { browserApi } from "@/lib/services/browserApi";
import { humanizeError, type FriendlyError } from "@/lib/errorMessages";
import type { BrowserResultPage, BrowserTask } from "@/types";

export function useBrowserResults(sessionId: string, task: BrowserTask) {
  const [page, setPage] = useState<BrowserResultPage | null>(null);
  const [offset, setOffset] = useState(0);
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [receipt, setReceipt] = useState(task.latestReceipt);
  const [seenReceipts, setSeenReceipts] = useState(() => new Set(task.latestReceipt ? [task.latestReceipt.id] : []));
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<FriendlyError | null>(null);
  const selectionFor = useRef("");
  const changed = !!page && (selected.size !== page.selectedIds.length || page.selectedIds.some(id => !selected.has(id)));
  // 保存返回的新收据可能先于轮询到达，不能被晚到的旧快照切回。
  const newReceipt = task.latestReceipt && task.latestReceipt.id !== receipt?.id && !seenReceipts.has(task.latestReceipt.id)
    ? task.latestReceipt : null;
  const loadLatest = () => {
    if (!newReceipt) return;
    setSeenReceipts(current => new Set([...Array.from(current), ...(receipt ? [receipt.id] : []), newReceipt.id]));
    setReceipt(newReceipt); setOffset(0); setPage(null); setError(null);
  };
  useEffect(() => {
    if (!newReceipt || changed || busy || loading) return;
    setSeenReceipts(current => new Set([...Array.from(current), ...(receipt ? [receipt.id] : []), newReceipt.id]));
    setReceipt(newReceipt); setOffset(0); setPage(null);
  }, [newReceipt, receipt, changed, busy, loading]);
  useEffect(() => {
    const abort = new AbortController();
    setLoading(true); setError(null);
    void browserApi.rows(sessionId, task.id, receipt?.id, offset, abort.signal).then(next => {
      if (abort.signal.aborted) return;
      setPage(next);
      const key = receipt?.id ?? task.id;
      if (selectionFor.current !== key) { setSelected(new Set(next.selectedIds)); selectionFor.current = key; }
    }).catch(failure => { if (!abort.signal.aborted) setError(humanizeError(failure)); })
      .finally(() => { if (!abort.signal.aborted) setLoading(false); });
    return () => abort.abort();
  }, [sessionId, task.id, receipt?.id, offset]);
  const save = async () => {
    if (!receipt || newReceipt || busy) return null;
    setBusy(true); setError(null);
    try {
      const next = await browserApi.adjust(sessionId, receipt.id, Array.from(selected));
      setSeenReceipts(current => new Set([...Array.from(current), receipt.id, next.id]));
      setReceipt(next); setOffset(0); setPage(null);
      return next;
    } catch (failure) { setError(humanizeError(failure)); return null; }
    finally { setBusy(false); }
  };
  const dedupe = () => setSelected(current => {
    const next = new Set(current);
    for (const group of page?.duplicateGroups ?? []) {
      const keep = group.find(id => next.has(id));
      for (const id of group) if (id !== keep) next.delete(id);
    }
    return next;
  });
  const toggle = (id: string, value: boolean) => setSelected(current => {
    const next = new Set(current); if (value) next.add(id); else next.delete(id); return next;
  });
  const currentReceipt = task.latestReceipt?.id === receipt?.id ? task.latestReceipt : receipt;
  return { page, offset, setOffset, selected, toggle, changed, receipt: currentReceipt, newReceipt, loadLatest, loading, busy, error, setError, save, dedupe };
}
