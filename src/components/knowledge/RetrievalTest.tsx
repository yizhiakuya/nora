'use client';

import { useState } from "react";
import { SearchCode, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useTimedSequence } from "@/hooks/useTimedSequence";

export function RetrievalTest() {
  const [isSearching, setIsSearching] = useState(false);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();

  const handleSearch = () => {
    if (!query) return;
    cancelAll();
    setIsSearching(true);
    setResults(false);
    schedule(() => {
      setIsSearching(false);
      setResults(true);
    }, 1200);
  };

  return (
    <div className="w-full lg:w-[35%] flex flex-col space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-gray-800">命中率测试</h2>
      </div>
      <div className="bg-white border border-gray-200 rounded-xl shadow-sm flex flex-col h-[400px]">
        <div className="p-4 border-b border-gray-100 bg-gray-50/50 rounded-t-xl shrink-0">
          <div className="relative">
            <textarea
              rows={2}
              placeholder="模拟提问：2023年Q4的净利润率？"
              className="w-full bg-white border border-gray-200 rounded-lg p-2.5 text-xs focus:outline-none focus:border-blue-400 focus:ring-1 focus:ring-blue-500 resize-none shadow-inner pr-10"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); handleSearch(); } }}
            ></textarea>
            <Button size="icon" className="absolute bottom-2 right-2 w-6 h-6 bg-blue-600 hover:bg-blue-700 disabled:opacity-50" onClick={handleSearch} disabled={isSearching || !query}>
              {isSearching ? <Loader2 className="w-3 h-3 animate-spin text-white" /> : <SearchCode className="w-3 h-3 text-white" />}
            </Button>
          </div>
        </div>
        <div className="flex-1 overflow-y-auto p-4 space-y-3 bg-[#f8fafc]">
          {!isSearching && !results && (
            <div className="h-full flex flex-col items-center justify-center text-gray-400 gap-2">
              <SearchCode className="w-8 h-8 opacity-20" />
              <span className="text-xs">输入内容以测试知识库检索效果</span>
            </div>
          )}

          {isSearching && (
            <div className="h-full flex flex-col items-center justify-center text-gray-400 gap-3">
              <Loader2 className="w-6 h-6 animate-spin text-blue-500" />
              <span className="text-xs animate-pulse">正在进行向量检索...</span>
            </div>
          )}

          {results && (
            <>
              <div className="text-[10px] font-medium text-gray-400 mb-2">召回结果 (3)</div>
              <div className="bg-white border border-green-200 rounded-lg p-3 shadow-sm relative overflow-hidden animate-in fade-in slide-in-from-bottom-2">
                <div className="absolute left-0 top-0 bottom-0 w-1 bg-green-500"></div>
                <div className="flex justify-between items-start mb-1.5 pl-1">
                  <div className="text-[10px] text-gray-500 flex items-center gap-1.5">财报_Final.pdf</div>
                  <div className="bg-green-50 text-green-600 text-[9px] px-1.5 py-0.5 rounded font-bold">92%</div>
                </div>
                <div className="text-xs text-gray-700 pl-1">
                  ...由于核心产品线涨价，<span className="bg-yellow-200/60 text-yellow-900 px-0.5">净利润率</span>跃升至创纪录的 <span className="bg-yellow-200/60 font-bold px-0.5">18.4%</span>...
                </div>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  );
}
