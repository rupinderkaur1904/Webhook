import { Tooltip } from '@mantine/core';

const SHORT: Intl.DateTimeFormatOptions = { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' };

/**
 * A timestamp in the viewer's locale, with the exact ISO instant on hover. `time` shows only the
 * clock; `compact` shows the clock for today and a short date otherwise, for table cells.
 */
export function Time({ value, format = 'full' }: { value: string | null; format?: 'full' | 'time' | 'compact' }) {
  if (!value) return <>—</>;
  const date = new Date(value);
  const today = date.toDateString() === new Date().toDateString();
  const text =
    format === 'time' || (format === 'compact' && today)
      ? date.toLocaleTimeString()
      : format === 'compact'
        ? date.toLocaleString(undefined, SHORT)
        : date.toLocaleString();
  return (
    <Tooltip label={date.toISOString()}>
      <span>{text}</span>
    </Tooltip>
  );
}
