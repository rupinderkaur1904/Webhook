import type { DeliveryDetail, DeliverySummary, Endpoint } from '../api/types';

export const endpoint: Endpoint = {
  id: 'eeeeeeee-2222-3333-4444-555555555555',
  name: 'orders-service',
  description: null,
  url: 'https://orders.example.com/hooks',
  eventTypes: ['order.*'],
  enabled: true,
  secretPrefix: 'whsec_abcd',
  createdAt: '2026-10-15T03:00:00Z',
  updatedAt: '2026-10-15T03:00:00Z',
};

export const deadLetter: DeliverySummary = {
  id: '11111111-2222-3333-4444-555555555555',
  eventId: 'aaaaaaaa-2222-3333-4444-555555555555',
  endpointId: endpoint.id,
  endpointName: 'orders-service',
  eventType: 'order.created',
  status: 'DEAD_LETTERED',
  attemptCount: 1,
  maxAttempts: 1,
  lastResponseStatus: 503,
  lastError: 'HTTP 503',
  lastAttemptAt: '2026-10-15T04:00:00Z',
  nextAttemptAt: null,
  deadLetterReason: 'RETRIES_EXHAUSTED',
  deliveredAt: null,
  deadLetteredAt: '2026-10-15T04:00:01Z',
  replayOfDeliveryId: null,
  rootDeliveryId: '11111111-2222-3333-4444-555555555555',
  createdAt: '2026-10-15T04:00:00Z',
};

export const delivered: DeliverySummary = {
  ...deadLetter,
  id: 'd1d1d1d1-0000-0000-0000-000000000000',
  rootDeliveryId: 'd1d1d1d1-0000-0000-0000-000000000000',
  endpointName: 'billing',
  eventType: 'payment.succeeded',
  status: 'DELIVERED',
  maxAttempts: 1,
  lastResponseStatus: 200,
  lastError: null,
  deadLetterReason: null,
  deliveredAt: '2026-10-15T04:00:01Z',
  deadLetteredAt: null,
};

export const deliveredDetail: DeliveryDetail = {
  delivery: delivered,
  endpointUrl: 'https://billing.example.com/hooks',
  lease: null,
  eventBody: { id: 'e1e1e1e1', type: 'payment.succeeded', data: { amount: 100 } },
  attempts: [
    {
      id: 'a1',
      attemptNumber: 1,
      status: 'SUCCEEDED',
      outcome: 'SUCCESS',
      retryable: null,
      requestUrl: 'https://billing.example.com/hooks',
      responseStatus: 200,
      responseHeaders: {},
      responseBodyExcerpt: '{"ok":true}',
      errorMessage: null,
      retryAfterSeconds: null,
      startedAt: '2026-10-15T04:00:00Z',
      finishedAt: '2026-10-15T04:00:01Z',
      durationMs: 42,
      reportedAfterLeaseExpiry: false,
    },
  ],
  replayEligibility: { eligible: false, reason: 'NOT_DEAD_LETTERED', replayCount: 0, maxReplays: 10 },
  chain: [delivered],
};
