import type { ReactNode } from 'react';
import type { CircuitState, DeliveryStatus, HealthStatus } from '../api/types';

/** The status palette maps 1:1 to DeliveryStatus; health and circuit reuse the same tones. */
export type Tone = 'delivered' | 'retrying' | 'in-flight' | 'dead-letter' | 'pending' | 'replay';

export function Pill({
  tone,
  children,
  outline = false,
  dot = true,
  testId,
}: {
  tone: Tone;
  children: ReactNode;
  outline?: boolean;
  dot?: boolean;
  testId?: string;
}) {
  return (
    <span className={`pill pill-${tone}${outline ? ' pill-outline' : ''}`} data-testid={testId}>
      {dot && <span className="pill-dot" aria-hidden="true" />}
      {children}
    </span>
  );
}

export const STATUS_TONES: Record<DeliveryStatus, Tone> = {
  PENDING: 'pending',
  RETRYING: 'retrying',
  IN_FLIGHT: 'in-flight',
  DELIVERED: 'delivered',
  DEAD_LETTERED: 'dead-letter',
  CANCELLED: 'pending',
};

export const STATUS_LABELS: Record<DeliveryStatus, string> = {
  PENDING: 'Pending',
  RETRYING: 'Retrying',
  IN_FLIGHT: 'In flight',
  DELIVERED: 'Delivered',
  DEAD_LETTERED: 'Dead letter',
  CANCELLED: 'Cancelled',
};

export function StatusBadge({ status }: { status: DeliveryStatus }) {
  return <Pill tone={STATUS_TONES[status]}>{STATUS_LABELS[status]}</Pill>;
}

const HEALTH_TONES: Record<HealthStatus, Tone> = {
  HEALTHY: 'delivered',
  DEGRADED: 'retrying',
  FAILING: 'dead-letter',
  NO_DATA: 'pending',
  DISABLED: 'pending',
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
    <Pill tone={HEALTH_TONES[status]} testId="health-badge">
      {HEALTH_LABELS[status]}
    </Pill>
  );
}

const CIRCUIT_TONES: Record<CircuitState, Tone> = { CLOSED: 'delivered', OPEN: 'dead-letter', HALF_OPEN: 'retrying' };
export const CIRCUIT_LABELS: Record<CircuitState, string> = {
  CLOSED: 'Closed',
  OPEN: 'Open',
  HALF_OPEN: 'Half-open',
};

export function CircuitBadge({ state }: { state: CircuitState }) {
  return (
    <Pill tone={CIRCUIT_TONES[state]} outline dot={false}>
      Circuit {CIRCUIT_LABELS[state].toLowerCase()}
    </Pill>
  );
}

/** A small monospace label, e.g. an outcome code or an event-type filter. */
export function Tag({ children, tone }: { children: ReactNode; tone?: Tone }) {
  return <span className={`tag${tone ? ` tag-${tone}` : ''}`}>{children}</span>;
}

/** One segment per allowed attempt: used ones are coloured by how the delivery stands. */
export function AttemptMeter({ used, max, status }: { used: number; max: number; status: DeliveryStatus }) {
  const segments = Math.min(max, 12);
  const filled = Math.round((Math.min(used, max) / max) * segments);
  return (
    <span className="meter" aria-hidden="true">
      {Array.from({ length: segments }, (_, i) => {
        const done = i < filled;
        // The latest attempt shows where the delivery stands; every earlier attempt failed.
        const tone = !done ? 'off' : i === filled - 1 ? STATUS_TONES[status] : 'dead-letter';
        return <span key={i} className={`meter-seg meter-${tone}`} />;
      })}
    </span>
  );
}
