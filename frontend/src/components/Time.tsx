import { Tooltip } from '@mantine/core';

export function Time({ value }: { value: string | null }) {
  if (!value) return <>—</>;
  const date = new Date(value);
  return (
    <Tooltip label={date.toISOString()}>
      <span>{date.toLocaleString()}</span>
    </Tooltip>
  );
}
