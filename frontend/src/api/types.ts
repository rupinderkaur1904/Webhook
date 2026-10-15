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
}

export interface DeliveryDetail {
  delivery: DeliverySummary;
  endpointUrl: string;
  eventBody: unknown;
  attempts: Attempt[];
}

export interface Problem {
  status: number;
  code: string;
  detail: string;
  errors?: { field: string; message: string }[];
}
