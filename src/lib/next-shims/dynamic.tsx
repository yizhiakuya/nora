import { lazy, Suspense, ComponentType, ReactNode } from "react";

interface DynamicOptions {
  loading?: () => ReactNode;
  ssr?: boolean;
}


export function dynamic(
  factory: () => Promise<any>,
  options?: DynamicOptions
): ComponentType<any> {
  const Lazy = lazy(async () => {
    const mod: any = await factory();
    const Comp = mod?.default ?? mod;
    return { default: Comp };
  });
  return function DynamicComponent(props: any) {
    return (
      <Suspense fallback={options?.loading ? options.loading() : null}>
        <Lazy {...props} />
      </Suspense>
    );
  };
}

export default dynamic;

