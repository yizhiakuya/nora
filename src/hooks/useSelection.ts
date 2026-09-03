import { useState, useCallback } from 'react';

export function useSelection<T>(items: T[], key: keyof T) {
  const [selectedIds, setSelectedIds] = useState<T[keyof T][]>([]);

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
