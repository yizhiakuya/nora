/**
 * next/image shim：Vite 下退化为原生 <img>，保留 width/height/priority 等属性签名。
 */
import { forwardRef } from "react";

interface ImageProps extends React.ImgHTMLAttributes<HTMLImageElement> {
  src: string;
  alt: string;
  width?: number;
  height?: number;
  priority?: boolean;
  fill?: boolean;
  sizes?: string;
  quality?: number;
  placeholder?: string;
}

const ImageBase = forwardRef<HTMLImageElement, ImageProps>(function Image(
  { src, alt, width, height, priority, fill, sizes, quality, placeholder, ...rest },
  ref
) {
  return <img ref={ref} src={src} alt={alt} width={width} height={height} {...rest} />;
});

export default ImageBase;

