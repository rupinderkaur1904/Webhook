import type { DeadLetterReason, ReplayIneligibleReason } from '../api/types';

export const DEAD_LETTER_REASONS: Record<DeadLetterReason, string> = {
  RETRIES_EXHAUSTED: 'Retries exhausted',
  NON_RETRYABLE_RESPONSE: 'Non-retryable response',
  DESTINATION_BLOCKED: 'Destination blocked',
};

export const REPLAY_REASONS: Record<ReplayIneligibleReason, string> = {
  NOT_DEAD_LETTERED: 'Only dead-lettered deliveries can be replayed.',
  ENDPOINT_DELETED: 'The endpoint has been deleted.',
  ENDPOINT_DISABLED: 'The endpoint is disabled. Enable it to replay.',
  REPLAY_IN_PROGRESS: 'A replay of this delivery is already in progress.',
  REPLAY_LIMIT_REACHED: 'This delivery has been replayed the maximum number of times.',
};

export function shortId(id: string) {
  return id.slice(0, 8);
}

export function percent(rate: number | null) {
  return rate == null ? '—' : `${(rate * 100).toFixed(1)}%`;
}

/** Compact reason names for counts, e.g. "5 exhausted · 2 non-retryable". */
export const DEAD_LETTER_SHORT: Record<DeadLetterReason, string> = {
  RETRIES_EXHAUSTED: 'exhausted',
  NON_RETRYABLE_RESPONSE: 'non-retryable',
  DESTINATION_BLOCKED: 'blocked',
};
