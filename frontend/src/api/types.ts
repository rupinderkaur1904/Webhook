// The API's types, generated from the backend's OpenAPI document. Do not edit schema.ts by hand:
// after an API change, regenerate frontend/openapi.json (see OpenApiContractTest) and run
// `npm run gen:api`. This file only gives the generated shapes the names the dashboard uses.
import type { components } from './schema';

type Schemas = components['schemas'];

export type DeliverySummary = Schemas['DeliverySummary'];
export type DeliveryStatus = DeliverySummary['status'];
export type DeadLetterReason = NonNullable<DeliverySummary['deadLetterReason']>;

/** One page of a keyset-paginated list (the backend's generic Page<T>; OpenAPI has one schema per T). */
export interface Page<T> {
  items: T[];
  nextCursor: string | null;
}

export type Endpoint = Schemas['EndpointResponse'];
export type Circuit = Schemas['CircuitView'];
export type CircuitState = Circuit['state'];
export type EndpointHealth = Schemas['EndpointHealth'];
export type HealthStatus = EndpointHealth['status'];
export type EndpointHealthItem = Schemas['EndpointHealthItem'];
export type EndpointCreated = Schemas['EndpointCreatedResponse'];
export type SecretRotated = Schemas['RotateSecretResponse'];

export type User = Schemas['UserResponse'];
export type ApiKey = Schemas['ApiKey'];
export type ApiKeyCreated = Schemas['ApiKeyCreatedResponse'];

export type EventAccepted = Schemas['EventAcceptedResponse'];
export type EventSummary = Schemas['EventSummary'];
export type EventDetail = Schemas['EventDetail'];

export type Attempt = Schemas['AttemptView'];
export type ReplayEligibility = Schemas['ReplayEligibility'];
export type ReplayIneligibleReason = NonNullable<ReplayEligibility['reason']>;
export type DeliveryDetail = Schemas['DeliveryDetail'];

export type Overview = Schemas['Overview'];
export type MetricsWindow = Overview['window'];
export type RecentAttempt = Schemas['RecentAttempt'];

/** Query filters for the deliveries list (client-side only). */
export interface DeliveryFilter {
  statuses?: DeliveryStatus[];
  endpointId?: string;
  eventId?: string;
  eventType?: string;
  deadLetterReason?: DeadLetterReason;
}

/** RFC 9457 problem details, as every error response is shaped (GlobalExceptionHandler). */
export interface Problem {
  status: number;
  code: string;
  detail: string;
  errors?: { field: string; message: string }[];
  reason?: string;
  requestId?: string;
}

// Compile-time check that the generic Page matches the generated page schemas.
type Assert<T extends true> = T;
type SameShape<A, B> = [A] extends [B] ? ([B] extends [A] ? true : false) : false;
export type _PageMatchesContract = Assert<SameShape<Page<DeliverySummary>, Schemas['PageDeliverySummary']>>;
