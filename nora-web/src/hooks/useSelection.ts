import { useState, useCallback, useEffect } from 'react';

export function useSelection<T>(items: T[], key: keyof T) {
  const [selectedIds, setSelectedIds] = useState<T[keyof T][]>([]);

  // 列表变化（搜索过滤 / 删除）后剔除已不在当前列表中的失效选中项，
  // 避免 hasSelection / isAllSelected 基于陈旧 id 误判，进而误删不可见文件。
  useEffect(() => {
    setSelectedIds((prev) => {
      if (prev.length === 0) return prev;
      const validIds = new Set(items.map((item) => item[key]));
      const next = prev.filter((id) => validIds.has(id));
      return next.length === prev.length ? prev : next;
    });
  }, [items, key]);

  const toggleSelectAll = useCallback(() => {
    if (selectedIds.length === items.length && items.length > 0) {
      setSelectedIds([]);
    } else {
      setSelectedIds(items.map(item => item[key]));
    }
  }, [items, selectedIds, key]);

  const toggleSelect = useCallback((id: T[keyof T]) => {
    setSelectedIds(prev => 
      prev.includes(id) ? prev.filter(i => i !== id) : [...prev, id]
    );
  }, []);

  const clearSelection = useCallback(() => {
    setSelectedIds([]);
  }, []);

  return {
    selectedIds,
    toggleSelectAll,
    toggleSelect,
    clearSelection,
    isAllSelected: selectedIds.length === items.length && items.length > 0,
    hasSelection: selectedIds.length > 0
  };
}
