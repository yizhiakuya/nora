'use client';

import * as Dialog from "@radix-ui/react-dialog";
import { useRef } from "react";
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
  onOpenAutoFocus?: React.ComponentProps<typeof Dialog.Content>["onOpenAutoFocus"];
}

/** Controlled Radix dialog; callers open it without a Dialog.Trigger. */
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
  onOpenAutoFocus,
}: ModalProps) {
  const opener = useRef<HTMLElement | null>(null);
  return (
    <Dialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose(); }}>
      <Dialog.Portal>
        <Dialog.Overlay className="fixed inset-0 z-50 bg-slate-950/35 backdrop-blur-[2px] animate-in fade-in duration-150" />
        <Dialog.Content
          // 注意:不要再加 relative——它与 fixed 同为 position 工具类,生成
          // 顺序上 relative 在后会覆盖 fixed,对话框会掉进文档流被推到页尾
          // (2026-09-21 实测踩坑:只剩遮罩、弹窗在视口外)。fixed 本身就是
          // 绝对定位子元素的包含块,关闭按钮的 absolute 定位不受影响。
          className={`fixed inset-0 m-auto z-50 h-fit max-h-[90dvh] max-w-[calc(100vw-24px)] ${width} bg-card border border-border rounded-2xl shadow-float overflow-hidden animate-in zoom-in-95 duration-150 flex flex-col`}
          onPointerDownOutside={closeOnOutsideClick ? undefined : (e) => e.preventDefault()}
          onFocusOutside={closeOnOutsideClick ? undefined : (e) => e.preventDefault()}
          onEscapeKeyDown={closeOnEscape ? undefined : (e) => e.preventDefault()}
          onOpenAutoFocus={(event) => {
            opener.current = document.activeElement as HTMLElement | null;
            onOpenAutoFocus?.(event);
          }}
          onCloseAutoFocus={(e) => {
            if (opener.current?.isConnected) {
              e.preventDefault();
              opener.current.focus({ preventScroll: true });
            }
          }}
          aria-describedby={undefined}
        >
          {title ? (
            <div className="flex items-center justify-between gap-3 px-5 py-4 sm:px-6 border-b border-border shrink-0">
              <Dialog.Title className="min-w-0 text-base font-semibold text-foreground">{title}</Dialog.Title>
              {!hideCloseButton && (
                <Button variant="ghost" size="icon" aria-label="关闭对话框" className="h-8 w-8 shrink-0 text-muted-foreground" onClick={onClose}>
                  <X className="w-4 h-4" />
                </Button>
              )}
            </div>
          ) : (
            <>
              {/* 无标题弹窗:隐藏 Title 满足 Radix 无障碍要求(读屏播报) */}
              <Dialog.Title className="sr-only">对话框</Dialog.Title>
              {!hideCloseButton && (
                <Button variant="ghost" size="icon" aria-label="关闭对话框" className="absolute top-3 right-3 h-8 w-8 text-muted-foreground z-10" onClick={onClose}>
                  <X className="w-4 h-4" />
                </Button>
              )}
            </>
          )}

          <div className="min-h-0 p-5 sm:p-6 overflow-y-auto custom-scroll">
            {children}
          </div>

          {footer && (
            <div className="px-5 py-4 sm:px-6 border-t border-border bg-muted/40 flex flex-wrap justify-end gap-2 shrink-0">
              {footer}
            </div>
          )}
        </Dialog.Content>
      </Dialog.Portal>
    </Dialog.Root>
  );
}
