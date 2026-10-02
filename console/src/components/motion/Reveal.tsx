import type { CSSProperties, ElementType, ReactNode } from 'react';
import { useInView } from './hooks';

/** Fades and lifts its children in as they scroll into view. `delay` staggers siblings (ms). */
export function Reveal({
  children,
  delay = 0,
  as: Tag = 'div',
  className = '',
}: {
  children: ReactNode;
  delay?: number;
  as?: ElementType;
  className?: string;
}) {
  const [ref, seen] = useInView<HTMLElement>();
  const style = { '--reveal-delay': `${delay}ms` } as CSSProperties;
  return (
    <Tag ref={ref} style={style} className={`reveal${seen ? ' reveal-in' : ''}${className ? ` ${className}` : ''}`}>
      {children}
    </Tag>
  );
}
