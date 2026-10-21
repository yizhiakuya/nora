import * as React from "react"
import { Slot } from "@radix-ui/react-slot"
import { cva, type VariantProps } from "class-variance-authority"

import { cn } from "@/lib/utils"

const buttonVariants = cva(
  "app-button inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-lg text-[13px] font-medium select-none transition-[background-color,border-color,color,box-shadow,transform] focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring/40 focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-45",
  {
    variants: {
      variant: {
        default:
          "bg-primary text-primary-foreground shadow-sm hover:bg-primary/90 active:bg-primary/80",
        destructive:
          "bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90 active:bg-destructive/80",
        outline:
          "border border-input/80 bg-card shadow-surface hover:border-input hover:bg-muted/60 active:bg-muted data-[state=open]:bg-muted",
        secondary:
          "bg-secondary text-secondary-foreground hover:bg-accent active:bg-accent/80",
        ghost: "hover:bg-accent hover:text-accent-foreground active:bg-muted data-[state=open]:bg-accent data-[state=open]:text-accent-foreground",
        composer: "rounded-full bg-foreground text-background hover:bg-foreground/85 active:bg-foreground/75 disabled:bg-muted disabled:text-muted-foreground disabled:opacity-100",
        link: "text-primary underline-offset-4 hover:underline",
      },
      size: {
        default: "h-9 px-4 py-2",
        sm: "h-8 px-3 text-[13px]",
        lg: "h-10 px-5 text-sm",
        icon: "h-9 w-9",
      },
    },
    defaultVariants: {
      variant: "default",
      size: "default",
    },
  }
)

interface ButtonProps
  extends React.ButtonHTMLAttributes<HTMLButtonElement>,
    VariantProps<typeof buttonVariants> {
  asChild?: boolean
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant, size, asChild = false, ...props }, ref) => {
    const Comp = asChild ? Slot : "button"
    return (
      <Comp
        className={cn(buttonVariants({ variant, size, className }))}
        ref={ref}
        {...props}
      />
    )
  }
)
Button.displayName = "Button"

export { Button,  }
