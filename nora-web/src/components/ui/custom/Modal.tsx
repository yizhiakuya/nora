'use client';

import * as Dialog from "@radix-ui/react-dialog";
import { X } from "lucide-react";
import { Button } from "@/components/ui/button";

interface ModalProps {
  isOpen: boolean;
  onClose: () => void;
  title?: React.ReactNode;
  children: React.ReactNode;
  width?: string;
  footer?: React.ReactNode;
  closeOnOutsideClick?: boolean;
  closeOnEscape?: boolean;
  hideCloseButton?: boolean;
}

/**
 * 通用弹窗(2026-09-21 迁移到 Radix Dialog)。
 *
 * 此前自研实现缺无障碍基础设施:无 role="dialog"/aria-modal、无焦点陷阱
 * (Tab 会跑到弹窗后面)、关闭后焦点不还原。Radix Dialog 补齐这三项。
 * 外部 API 保持不变(19 个使用点零改动);视觉结构逐类名对齐旧实现:
 * 居中定位改用 inset-0 + m-auto(不再需要 transform,避免与 zoom-in
 * 动画的 transform 冲突)。
 *
 * closeOnOutsideClick=false 时同时拦截 pointer/focus outside 两类
 * (模态语义下 Tab 到外部会先触发 focus outside)。
 */
export function Modal({
  isOpen,
  onClose,
  title,
  children,
  width = "w-[600px]",
  footer,
  closeOnOutsideClick = true,
  closeOnEscape = true,
  hideCloseButton = false,
}: ModalProps) {
  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-black/40 backdrop-blur-sm animate-in fade-in duration-200" />
        <Dialog.Content
          className={`fixed inset-0 m-auto z-50 h-fit max-h-[90vh] ${width} bg-card rounded-xl shadow-xl overflow-hidden animate-in zoom-in-95 duration-200 flex flex-col relative`}
          onPointerDownOutside={closeOnOutsideClick ? undefined : (e) => e.preventDefault()}
          onFocusOutside={closeOnOutsideClick ? undefined : (e) => e.preventDefault()}
          onEscapeKeyDown={closeOnEscape ? undefined : (e) => e.preventDefault()}
          aria-describedby={undefined}
        >
          {title ? (
            <div className="flex items-center justify-between p-4 border-b border-border shrink-0">
              <Dialog.Title className="text-sm font-bold text-foreground">{title}</Dialog.Title>
              {!hideCloseButton && (
                <Button variant="ghost" size="icon" className="h-6 w-6 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-full transition-colors" onClick={onClose}>
                  <X className="w-4 h-4" />
                </Button>
              )}
            </div>
          ) : (
            <>
              {/* 无标题弹窗:隐藏 Title 满足 Radix 无障碍要求(读屏播报) */}
              <Dialog.Title className="sr-only">对话框</Dialog.Title>
              {!hideCloseButton && (
                <Button variant="ghost" size="icon" className="absolute top-3 right-3 h-6 w-6 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-full transition-colors z-10" onClick={onClose}>
                  <X className="w-4 h-4" />
                </Button>
              )}
            </>
          )}

          <div className="p-6 overflow-y-auto custom-scroll">
            {children}
          </div>

          {footer && (
            <div className="p-4 border-t border-border bg-muted flex justify-end gap-2 shrink-0">
              {footer}
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
