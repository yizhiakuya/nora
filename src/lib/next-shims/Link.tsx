import { ReactNode, AnchorHTMLAttributes } from "react";
import { Link as RRLink } from "react-router-dom";

interface NextLinkProps extends AnchorHTMLAttributes<HTMLAnchorElement> {
  href: string;
  prefetch?: boolean;
  replace?: boolean;
  children?: ReactNode;
}

export function Link({ href, prefetch, replace, children, ...rest }: NextLinkProps) {
  return (
    <RRLink to={href} replace={replace} {...rest}>
      {children}
    </RRLink>
  );
}

export default Link;
