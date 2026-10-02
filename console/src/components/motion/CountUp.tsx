import { useInView, useCountUp } from './hooks';

/** A number that eases up to its value the first time it is seen. Ends on exactly `value`. */
export function CountUp({
  value,
  decimals = 0,
  suffix = '',
}: {
  value: number;
  decimals?: number;
  suffix?: string;
}) {
  const [ref, seen] = useInView<HTMLSpanElement>();
  const shown = useCountUp(value, seen);
  return (
    <span ref={ref} className="num">
      {shown.toLocaleString(undefined, { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}
      {suffix}
    </span>
  );
}
