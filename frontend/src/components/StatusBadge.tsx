import { Badge } from '@mantine/core';
import type { CircuitState, DeliveryStatus, HealthStatus } from '../api/types';

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

const HEALTH_COLORS: Record<HealthStatus, string> = {
  HEALTHY: 'green',
  DEGRADED: 'yellow',
  FAILING: 'red',
  NO_DATA: 'gray',
  DISABLED: 'dark',
};

export const HEALTH_LABELS: Record<HealthStatus, string> = {
  HEALTHY: 'Healthy',
  DEGRADED: 'Degraded',
  FAILING: 'Failing',
  NO_DATA: 'No data',
  DISABLED: 'Disabled',
};

export function HealthBadge({ status }: { status: HealthStatus }) {
  return (
    <Badge color={HEALTH_COLORS[status]} variant="light" data-testid="health-badge">
      {HEALTH_LABELS[status]}
    </Badge>
  );
}

const CIRCUIT_COLORS: Record<CircuitState, string> = { CLOSED: 'green', OPEN: 'red', HALF_OPEN: 'orange' };
export const CIRCUIT_LABELS: Record<CircuitState, string> = {
  CLOSED: 'Closed',
  OPEN: 'Open',
  HALF_OPEN: 'Half-open',
};

export function CircuitBadge({ state }: { state: CircuitState }) {
  return (
    <Badge color={CIRCUIT_COLORS[state]} variant="outline">
      Circuit {CIRCUIT_LABELS[state].toLowerCase()}
    </Badge>
  );
}
