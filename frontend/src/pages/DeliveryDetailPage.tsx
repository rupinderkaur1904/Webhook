import { Alert, Anchor, Badge, Card, Code, Group, SimpleGrid, Stack, Text, Timeline, Title } from '@mantine/core';
import { Link, useParams } from 'react-router-dom';
import { useDelivery } from '../api/hooks';
import type { Attempt, DeliveryDetail } from '../api/types';
import { Field } from '../components/Field';
import { DEAD_LETTER_REASONS, REPLAY_REASONS, shortId } from '../components/labels';
import { QueryState } from '../components/QueryState';
import { ReplayButton } from '../components/ReplayButton';
import { StatusBadge } from '../components/StatusBadge';
import { Time } from '../components/Time';

const ATTEMPT_COLORS: Record<Attempt['status'], string> = {
  SUCCEEDED: 'green',
  STARTED: 'blue',
  FAILED: 'red',
  ABANDONED: 'orange',
};

function AttemptItem({ attempt }: { attempt: Attempt }) {
  return (
    <Stack gap={4}>
      <Group gap="xs">
        <Text fw={500}>Attempt {attempt.attemptNumber}</Text>
        <Badge color={ATTEMPT_COLORS[attempt.status]} variant="light">
          {attempt.status === 'STARTED' ? 'IN PROGRESS' : attempt.outcome ?? attempt.status}
        </Badge>
        {attempt.status === 'ABANDONED' && (
          <Badge color="orange" variant="outline">
            abandoned
          </Badge>
        )}
        {attempt.reportedAfterLeaseExpiry && (
          <Badge color="gray" variant="outline">
            late result, not applied
          </Badge>
        )}
        {attempt.responseStatus != null && <Badge variant="outline">HTTP {attempt.responseStatus}</Badge>}
        {attempt.retryable != null && (
          <Badge color={attempt.retryable ? 'yellow' : 'gray'} variant="dot">
            {attempt.retryable ? 'retryable' : 'not retryable'}
          </Badge>
        )}
        {attempt.durationMs != null && (
          <Text size="xs" c="dimmed">
            {attempt.durationMs} ms
          </Text>
        )}
      </Group>
      <Text size="xs" c="dimmed">
        <Time value={attempt.startedAt} /> · {attempt.requestUrl}
      </Text>
      {attempt.retryAfterSeconds != null && (
        <Text size="xs" c="dimmed">
          Receiver asked to retry after {attempt.retryAfterSeconds}s (Retry-After)
        </Text>
      )}
      {attempt.reportedAfterLeaseExpiry && (
        <Text size="xs" c="dimmed">
          The worker's lease expired before it reported, so this attempt was abandoned and the delivery moved on. The
          worker later reported what it saw; it is shown here but did not change the delivery.
        </Text>
      )}
      {attempt.errorMessage && (
        <Text size="sm" c="red">
          {attempt.errorMessage}
        </Text>
      )}
      {attempt.responseBodyExcerpt && <Code block>{attempt.responseBodyExcerpt}</Code>}
    </Stack>
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
          <Timeline bulletSize={12} lineWidth={2} active={chain.findIndex((d) => d.id === delivery.id)}>
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

  return (
    <Stack>
      <Anchor component={Link} to="/deliveries" size="sm">
        ← Deliveries
      </Anchor>
      <QueryState isLoading={query.isLoading} error={query.error} onRetry={() => query.refetch()}>
        {detail && (
          <>
            <Group>
              <Title order={2}>Delivery</Title>
              <StatusBadge status={detail.delivery.status} />
              {detail.delivery.replayOfDeliveryId && (
                <Badge variant="outline" color="grape">
                  replay of {shortId(detail.delivery.replayOfDeliveryId)}
                </Badge>
              )}
            </Group>
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="Delivery ID">
                  <Code>{detail.delivery.id}</Code>
                </Field>
                <Field label="Event ID (webhook-id)">
                  <Anchor component={Link} to={`/events/${detail.delivery.eventId}`} ff="monospace" size="sm">
                    {detail.delivery.eventId}
                  </Anchor>
                </Field>
                <Field label="Event type">{detail.delivery.eventType}</Field>
                <Field label="Endpoint">
                  <Anchor component={Link} to={`/endpoints/${detail.delivery.endpointId}`} size="sm">
                    {detail.delivery.endpointName}
                  </Anchor>{' '}
                  ({detail.endpointUrl})
                </Field>
                <Field label="Attempts">
                  {detail.delivery.attemptCount} of {detail.delivery.maxAttempts}
                </Field>
                <Field label="Created">
                  <Time value={detail.delivery.createdAt} />
                </Field>
                {detail.lease && (
                  <Field label="In flight on">
                    {detail.lease.owner} · lease until <Time value={detail.lease.expiresAt} />
                  </Field>
                )}
                {detail.delivery.status === 'RETRYING' && (
                  <Field label="Next attempt">
                    <Time value={detail.delivery.nextAttemptAt} />
                  </Field>
                )}
                {detail.delivery.deadLetterReason && (
                  <Field label="Dead-letter reason">{DEAD_LETTER_REASONS[detail.delivery.deadLetterReason]}</Field>
                )}
                {detail.delivery.lastError && <Field label="Last error">{detail.delivery.lastError}</Field>}
                {detail.delivery.deliveredAt && (
                  <Field label="Delivered">
                    <Time value={detail.delivery.deliveredAt} />
                  </Field>
                )}
              </SimpleGrid>
            </Card>
            <ReplaySection detail={detail} />
            <Title order={4}>Attempt history</Title>
            {detail.attempts.length === 0 ? (
              <Text c="dimmed" size="sm">
                No attempts yet. The worker picks up due deliveries within a second.
              </Text>
            ) : (
              <Timeline bulletSize={14} lineWidth={2}>
                {detail.attempts.map((a) => (
                  <Timeline.Item key={a.id} color={ATTEMPT_COLORS[a.status]}>
                    <AttemptItem attempt={a} />
                  </Timeline.Item>
                ))}
              </Timeline>
            )}
            <Title order={4}>Payload sent</Title>
            <Code block>{JSON.stringify(detail.eventBody, null, 2)}</Code>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
