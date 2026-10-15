// Mirrors the backend contract in backend/src/main/java/dev/webhooks/platform/**/*Dtos.java.

export type DeliveryStatus = 'PENDING' | 'RETRYING' | 'IN_FLIGHT' | 'DELIVERED' | 'DEAD_LETTERED' | 'CANCELLED';
export type DeadLetterReason = 'RETRIES_EXHAUSTED' | 'NON_RETRYABLE_RESPONSE' | 'DESTINATION_BLOCKED';

export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export interface Endpoint {
  id: string;
  name: string;
  description: string | null;
  url: string;
  eventTypes: string[];
  enabled: boolean;
  secretPrefix: string;
  createdAt: string;
  updatedAt: string;
}

export interface EndpointCreated {
  endpoint: Endpoint;
  secret: string;
}

export interface EventAccepted {
  id: string;
  eventType: string;
  idempotencyKey: string;
  createdAt: string;
  deliveries: { id: string; endpointId: string; status: DeliveryStatus }[];
}

export interface DeliverySummary {
  id: string;
  eventId: string;
  endpointId: string;
  endpointName: string;
  eventType: string;
  status: DeliveryStatus;
  attemptCount: number;
  maxAttempts: number;
  lastResponseStatus: number | null;
  lastError: string | null;
  lastAttemptAt: string | null;
  nextAttemptAt: string | null;
  deadLetterReason: DeadLetterReason | null;
  deliveredAt: string | null;
  deadLetteredAt: string | null;
  replayOfDeliveryId: string | null;
  rootDeliveryId: string;
  createdAt: string;
}

export interface Attempt {
  id: string;
  attemptNumber: number;
  status: 'STARTED' | 'SUCCEEDED' | 'FAILED' | 'ABANDONED';
  outcome: string | null;
  retryable: boolean | null;
  requestUrl: string;
  responseStatus: number | null;
  responseHeaders: Record<string, string>;
  responseBodyExcerpt: string | null;
  errorMessage: string | null;
  retryAfterSeconds: number | null;
  startedAt: string;
  finishedAt: string | null;
  durationMs: number | null;
  /** The worker reported after its lease had expired; shown for honesty, not applied to the delivery. */
  reportedAfterLeaseExpiry: boolean;
}

export type ReplayIneligibleReason =
  | 'NOT_DEAD_LETTERED'
  | 'ENDPOINT_DELETED'
  | 'ENDPOINT_DISABLED'
  | 'REPLAY_IN_PROGRESS'
  | 'REPLAY_LIMIT_REACHED';

export interface ReplayEligibility {
  eligible: boolean;
  reason: ReplayIneligibleReason | null;
  replayCount: number;
  maxReplays: number;
}

export interface DeliveryDetail {
  delivery: DeliverySummary;
  endpointUrl: string;
  /** Set only while the delivery is in flight. */
  lease: { owner: string; expiresAt: string } | null;
  eventBody: unknown;
  attempts: Attempt[];
  replayEligibility: ReplayEligibility;
  /** The original delivery and every replay of it, oldest first. */
  chain: DeliverySummary[];
}

export interface DeliveryFilter {
  statuses?: DeliveryStatus[];
  endpointId?: string;
  eventId?: string;
  eventType?: string;
  deadLetterReason?: DeadLetterReason;
}

export interface EventSummary {
  id: string;
  eventType: string;
  idempotencyKey: string;
  createdAt: string;
  deliveryCount: number;
  deliveredCount: number;
  deadLetteredCount: number;
  activeCount: number;
}

export interface EventDetail {
  id: string;
  eventType: string;
  idempotencyKey: string;
  createdAt: string;
  occurredAt: string | null;
  body: unknown;
  deliveries: DeliverySummary[];
}

export type MetricsWindow = '1h' | '24h' | '7d';

export interface RecentAttempt {
  attemptId: string;
  deliveryId: string;
  endpointId: string;
  endpointName: string;
  eventType: string;
  attemptNumber: number;
  status: Attempt['status'];
  outcome: string | null;
  responseStatus: number | null;
  durationMs: number | null;
  startedAt: string;
}

export interface Overview {
  window: MetricsWindow;
  windowStart: string;
  windowEnd: string;
  eventsSubmitted: number;
  deliveriesSucceeded: number;
  deliveriesDeadLettered: { total: number; byReason: Partial<Record<DeadLetterReason, number>> };
  current: { pending: number; retrying: number; inFlight: number };
  deliverySuccessRate: number | null;
  attempts: {
    succeeded: number;
    failed: number;
    successRate: number | null;
    latencyP50Ms: number | null;
    latencyP95Ms: number | null;
  };
  recentActivity: RecentAttempt[];
}

export interface Problem {
  status: number;
  code: string;
  detail: string;
  errors?: { field: string; message: string }[];
  reason?: string;
}
