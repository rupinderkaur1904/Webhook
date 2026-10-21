import { Alert, Anchor, Code, Group, Stack, Text, Timeline, Title } from '@mantine/core';
import { Link, useParams } from 'react-router-dom';
import { useDelivery } from '../api/hooks';
import type { Attempt, DeliveryDetail } from '../api/types';
import { Field } from '../components/Field';
import { DEAD_LETTER_REASONS, REPLAY_REASONS, shortId } from '../components/labels';
import { QueryState } from '../components/QueryState';
import { ReplayButton } from '../components/ReplayButton';
import { Pill, StatusBadge, Tag, type Tone } from '../components/StatusBadge';
import { Time } from '../components/Time';

const ATTEMPT_TONES: Record<Attempt['status'], Tone> = {
  SUCCEEDED: 'delivered',
  STARTED: 'in-flight',
  FAILED: 'dead-letter',
  ABANDONED: 'retrying',
};

function AttemptItem({ attempt }: { attempt: Attempt }) {
  const tone = ATTEMPT_TONES[attempt.status];
  return (
    <li className={`timeline-item timeline-${tone}`}>
      <div className="timeline-head">
        <strong>Attempt {attempt.attemptNumber}</strong>
        <Tag tone={tone}>{attempt.status === 'STARTED' ? 'IN PROGRESS' : attempt.outcome ?? attempt.status}</Tag>
        {attempt.status === 'ABANDONED' && <Tag tone="retrying">abandoned</Tag>}
        {attempt.reportedAfterLeaseExpiry && <Tag>late result, not applied</Tag>}
        {attempt.responseStatus != null && <Tag>HTTP {attempt.responseStatus}</Tag>}
        {attempt.retryable != null && <Tag tone={attempt.retryable ? 'retrying' : undefined}>{attempt.retryable ? 'retryable' : 'not retryable'}</Tag>}
        {attempt.durationMs != null && <span className="timeline-ms">{attempt.durationMs} ms</span>}
      </div>
      <div className="timeline-meta">
        <Time value={attempt.startedAt} /> · {attempt.requestUrl}
      </div>
      {attempt.retryAfterSeconds != null && (
        <div className="timeline-note">Receiver asked to retry after {attempt.retryAfterSeconds}s (Retry-After)</div>
      )}
      {attempt.reportedAfterLeaseExpiry && (
        <div className="timeline-note dim">
          The worker's lease expired before it reported, so this attempt was abandoned and the delivery moved on. The
          worker later reported what it saw; it is shown here but did not change the delivery.
        </div>
      )}
      {attempt.errorMessage && <div className="timeline-error">{attempt.errorMessage}</div>}
      {attempt.responseBodyExcerpt && (
        <Code block className="timeline-body">
          {attempt.responseBodyExcerpt}
        </Code>
      )}
    </li>
  );
}

function ReplaySection({ detail }: { detail: DeliveryDetail }) {
  const { delivery, replayEligibility: eligibility, chain } = detail;
  const newerReplays = chain.filter((d) => d.replayOfDeliveryId && d.createdAt > delivery.createdAt);
  return (
    <Stack gap="xs">
      {delivery.status === 'DEAD_LETTERED' && (
        <Alert color={eligibility.eligible ? 'blue' : 'gray'} variant="light">
          <Group justify="space-between">
            <Text size="sm">
              {eligibility.eligible
                ? 'Replay sends this event again as a new delivery with a fresh retry budget. This dead letter stays unchanged.'
                : REPLAY_REASONS[eligibility.reason!]}{' '}
              Replays used: {eligibility.replayCount} of {eligibility.maxReplays}.
            </Text>
            <ReplayButton deliveryId={delivery.id} eligibility={eligibility} openAfter size="sm" />
          </Group>
        </Alert>
      )}
      {chain.length > 1 && (
        <>
          <Title order={4}>Replay chain</Title>
          <Timeline bulletSize={12} lineWidth={2} color="violet" active={chain.findIndex((d) => d.id === delivery.id)}>
            {chain.map((d) => (
              <Timeline.Item key={d.id}>
                <Group gap="xs">
                  {d.id === delivery.id ? (
                    <Text size="sm" ff="monospace" fw={600}>
                      {shortId(d.id)} (this delivery)
                    </Text>
                  ) : (
                    <Anchor component={Link} to={`/deliveries/${d.id}`} ff="monospace" size="sm">
                      {shortId(d.id)}
                    </Anchor>
                  )}
                  <Text size="xs" c="dimmed">
                    {d.replayOfDeliveryId ? `replay of ${shortId(d.replayOfDeliveryId)}` : 'original'}
                  </Text>
                  <StatusBadge status={d.status} />
                  <Text size="xs" c="dimmed">
                    {d.attemptCount}/{d.maxAttempts} attempts · <Time value={d.createdAt} />
                  </Text>
                </Group>
              </Timeline.Item>
            ))}
          </Timeline>
          {newerReplays.length > 0 && delivery.status === 'DEAD_LETTERED' && (
            <Text size="xs" c="dimmed">
              This delivery has been replayed; see the newer deliveries above for the outcome.
            </Text>
          )}
        </>
      )}
    </Stack>
  );
}

export function DeliveryDetailPage() {
  const { id = '' } = useParams();
  const query = useDelivery(id);
  const detail = query.data;
  const d = detail?.delivery;

  return (
    <div className="page">
      <p className="crumbs">
        <Anchor component={Link} to="/deliveries" c="dimmed" size="sm">
          Deliveries
        </Anchor>
        <span aria-hidden="true">/</span>
        <span className="mono">{shortId(id)}</span>
      </p>
      <QueryState isLoading={query.isLoading} error={query.error} onRetry={() => query.refetch()}>
        {detail && d && (
          <>
            <div className="detail-title">
              <Title order={2}>
                Delivery <span className="mono-title">{shortId(d.id)}</span>
              </Title>
              <StatusBadge status={d.status} />
              {d.replayOfDeliveryId && (
                <Pill tone="replay" dot={false}>
                  replay of {shortId(d.replayOfDeliveryId)}
                </Pill>
              )}
            </div>
            <div className="summary-strip">
              <div>
                <span>Event type</span>
                <strong className="mono">{d.eventType}</strong>
              </div>
              <div>
                <span>Endpoint</span>
                <strong>
                  <Anchor component={Link} to={`/endpoints/${d.endpointId}`} size="sm" fw={500}>
                    {d.endpointName}
                  </Anchor>
                </strong>
              </div>
              <div>
                <span>Attempts</span>
                <strong className="mono">
                  {d.attemptCount} of {d.maxAttempts}
                </strong>
              </div>
              {d.status === 'RETRYING' ? (
                <div>
                  <span>Next attempt</span>
                  <strong className="mono">
                    <Time value={d.nextAttemptAt} format="time" />
                  </strong>
                </div>
              ) : d.deliveredAt ? (
                <div>
                  <span>Delivered</span>
                  <strong className="mono">
                    <Time value={d.deliveredAt} format="time" />
                  </strong>
                </div>
              ) : d.deadLetterReason ? (
                <div>
                  <span>Dead-letter reason</span>
                  <strong>{DEAD_LETTER_REASONS[d.deadLetterReason]}</strong>
                </div>
              ) : (
                <div>
                  <span>Created</span>
                  <strong className="mono">
                    <Time value={d.createdAt} format="time" />
                  </strong>
                </div>
              )}
              <div>
                <span>Event ID (webhook-id)</span>
                <strong>
                  <Anchor component={Link} to={`/events/${d.eventId}`} className="mono" size="sm" c="inherit">
                    {d.eventId}
                  </Anchor>
                </strong>
              </div>
            </div>
            <div className="detail-facts">
              <Field label="Delivery ID">
                <span className="mono">{d.id}</span>
              </Field>
              <Field label="Endpoint URL">
                <span className="mono">{detail.endpointUrl}</span>
              </Field>
              <Field label="Created">
                <Time value={d.createdAt} />
              </Field>
              {detail.lease && (
                <Field label="In flight on">
                  {detail.lease.owner} · lease until <Time value={detail.lease.expiresAt} />
                </Field>
              )}
              {d.lastError && <Field label="Last error">{d.lastError}</Field>}
            </div>
            <ReplaySection detail={detail} />
            <div className="detail-columns">
              <section aria-labelledby="attempts-title">
                <h3 id="attempts-title" className="section-title">
                  Attempt history
                </h3>
                {detail.attempts.length === 0 ? (
                  <Text c="dimmed" size="sm">
                    No attempts yet. The worker picks up due deliveries within a second.
                  </Text>
                ) : (
                  <ol className="timeline">
                    {detail.attempts.map((a) => (
                      <AttemptItem key={a.id} attempt={a} />
                    ))}
                    {d.status === 'RETRYING' && d.nextAttemptAt && (
                      <li className="timeline-item">
                        <div className="timeline-head">
                          <strong>Scheduled retry</strong>
                          <Tag tone="pending">#{d.attemptCount + 1}</Tag>
                        </div>
                        <div className="timeline-meta">
                          <Time value={d.nextAttemptAt} /> · {detail.endpointUrl}
                        </div>
                        <div className="timeline-note dim">Waiting for its next_attempt_at</div>
                      </li>
                    )}
                  </ol>
                )}
              </section>
              <section className="code-panel" aria-labelledby="payload-title">
                <div className="code-panel-head">
                  <h3 id="payload-title">Payload sent</h3>
                  <span className="mono dim">application/json</span>
                </div>
                <pre className="code-json">{JSON.stringify(detail.eventBody, null, 2)}</pre>
                <div className="code-panel-sub">Signed headers</div>
                <dl className="kv-mono">
                  <dt>webhook-id</dt>
                  <dd>{d.eventId}</dd>
                  <dt>X-Webhook-Delivery-Id</dt>
                  <dd>{d.id}</dd>
                  <dt>webhook-signature</dt>
                  <dd>v1,… (HMAC-SHA256)</dd>
                </dl>
              </section>
            </div>
          </>
        )}
      </QueryState>
    </div>
  );
}
