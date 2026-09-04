'use client';

import React, { useEffect, useCallback } from 'react';
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

export function Modal({ 
  isOpen, 
  onClose, 
  title, 
  children, 
  width = "w-[600px]", 
  footer,
  closeOnOutsideClick = true,
  closeOnEscape = true,
  hideCloseButton = false
}: ModalProps) {
  
  // Handle Escape key
  const handleKeyDown = useCallback((e: KeyboardEvent) => {
    if (e.key === 'Escape' && closeOnEscape) {
      onClose();
    }
  }, [onClose, closeOnEscape]);

  useEffect(() => {
    if (isOpen) {
      document.addEventListener('keydown', handleKeyDown);
      document.body.style.overflow = 'hidden'; // Prevent background scrolling
    }
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      document.body.style.overflow = 'unset';
    };
  }, [isOpen, handleKeyDown]);

  if (!isOpen) return null;

  return (
    <div 
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm animate-in fade-in duration-200"
      onMouseDown={closeOnOutsideClick ? onClose : undefined}
    >
      <div 
        className={`bg-card rounded-xl shadow-xl ${width} overflow-hidden animate-in zoom-in-95 duration-200 flex flex-col max-h-[90vh] relative`}
        onMouseDown={(e) => e.stopPropagation()} // Prevent closing when clicking inside
      >
        {title && (
          <div className="flex items-center justify-between p-4 border-b border-border shrink-0">
            <h3 className="text-sm font-bold text-foreground">{title}</h3>
            {!hideCloseButton && (
              <Button variant="ghost" size="icon" className="h-6 w-6 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-full transition-colors" onClick={onClose}>
                <X className="w-4 h-4" />
              </Button>
            )}
          </div>
        )}

        {!title && !hideCloseButton && (
           <Button variant="ghost" size="icon" className="absolute top-3 right-3 h-6 w-6 text-muted-foreground hover:text-foreground hover:bg-muted/80 rounded-full transition-colors z-10" onClick={onClose}>
             <X className="w-4 h-4" />
           </Button>
        )}
        
        <div className="p-6 overflow-y-auto custom-scroll">
          {children}
        </div>

        {footer && (
          <div className="p-4 border-t border-border bg-muted flex justify-end gap-2 shrink-0">
            {footer}
          </div>
        )}
      </div>
    </div>
  );
}
