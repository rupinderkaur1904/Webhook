import { Anchor, Badge, Card, Code, Group, SimpleGrid, Stack, Text, Timeline, Title } from '@mantine/core';
import { Link, useParams } from 'react-router-dom';
import { useDelivery } from '../api/hooks';
import type { Attempt } from '../api/types';
import { QueryState } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { Time } from '../components/Time';

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div>
      <Text size="xs" c="dimmed">
        {label}
      </Text>
      <Text size="sm" component="div">
        {children}
      </Text>
    </div>
  );
}

function AttemptItem({ attempt }: { attempt: Attempt }) {
  return (
    <Stack gap={4}>
      <Group gap="xs">
        <Text fw={500}>Attempt {attempt.attemptNumber}</Text>
        <Badge color={attempt.status === 'SUCCEEDED' ? 'green' : attempt.status === 'STARTED' ? 'blue' : 'red'} variant="light">
          {attempt.outcome ?? attempt.status}
        </Badge>
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
      {attempt.errorMessage && (
        <Text size="sm" c="red">
          {attempt.errorMessage}
        </Text>
      )}
      {attempt.responseBodyExcerpt && <Code block>{attempt.responseBodyExcerpt}</Code>}
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
            </Group>
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="Delivery ID">
                  <Code>{detail.delivery.id}</Code>
                </Field>
                <Field label="Event ID">
                  <Code>{detail.delivery.eventId}</Code>
                </Field>
                <Field label="Event type">{detail.delivery.eventType}</Field>
                <Field label="Endpoint">
                  {detail.delivery.endpointName} ({detail.endpointUrl})
                </Field>
                <Field label="Attempts">
                  {detail.delivery.attemptCount} of {detail.delivery.maxAttempts}
                </Field>
                <Field label="Created">
                  <Time value={detail.delivery.createdAt} />
                </Field>
                {detail.delivery.status === 'RETRYING' && (
                  <Field label="Next attempt">
                    <Time value={detail.delivery.nextAttemptAt} />
                  </Field>
                )}
                {detail.delivery.deadLetterReason && <Field label="Dead-letter reason">{detail.delivery.deadLetterReason}</Field>}
                {detail.delivery.lastError && <Field label="Last error">{detail.delivery.lastError}</Field>}
                {detail.delivery.deliveredAt && (
                  <Field label="Delivered">
                    <Time value={detail.delivery.deliveredAt} />
                  </Field>
                )}
              </SimpleGrid>
            </Card>
            <Title order={4}>Attempt history</Title>
            {detail.attempts.length === 0 ? (
              <Text c="dimmed" size="sm">
                No attempts yet. The worker picks up due deliveries within a second.
              </Text>
            ) : (
              <Timeline bulletSize={14} lineWidth={2}>
                {detail.attempts.map((a) => (
                  <Timeline.Item key={a.id} color={a.status === 'SUCCEEDED' ? 'green' : a.status === 'STARTED' ? 'blue' : 'red'}>
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
