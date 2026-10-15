import { Badge } from '@mantine/core';
import type { DeliveryStatus } from '../api/types';

const COLORS: Record<DeliveryStatus, string> = {
  PENDING: 'gray',
  RETRYING: 'yellow',
  IN_FLIGHT: 'blue',
  DELIVERED: 'green',
  DEAD_LETTERED: 'red',
  CANCELLED: 'dark',
};

export function StatusBadge({ status }: { status: DeliveryStatus }) {
  return (
    <Badge color={COLORS[status]} variant="light">
      {status.replace('_', ' ')}
    </Badge>
  );
}
