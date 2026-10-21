import { Anchor, Card, Code, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { Link, useParams } from 'react-router-dom';
import { useEvent } from '../api/hooks';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { Field } from '../components/Field';
import { QueryState } from '../components/QueryState';
import { Time } from '../components/Time';

export function EventDetailPage() {
  const { id = '' } = useParams();
  const query = useEvent(id);
  const event = query.data;

  return (
    <Stack className="page">
      <Anchor component={Link} to="/events" size="sm" className="back-link">
        ← Events
      </Anchor>
      <QueryState isLoading={query.isLoading} error={query.error} onRetry={() => query.refetch()}>
        {event && (
          <>
            <Title order={2}>Event {event.eventType}</Title>
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="Event ID (webhook-id)">
                  <Code>{event.id}</Code>
                </Field>
                <Field label="Idempotency key">
                  <Code>{event.idempotencyKey}</Code>
                </Field>
                <Field label="Received">
                  <Time value={event.createdAt} />
                </Field>
                {event.occurredAt && (
                  <Field label="Occurred">
                    <Time value={event.occurredAt} />
                  </Field>
                )}
              </SimpleGrid>
            </Card>
            <Title order={4}>Deliveries</Title>
            {event.deliveries.length === 0 ? (
              <Text size="sm" c="dimmed">
                No endpoint was subscribed to this event type when it was received.
              </Text>
            ) : (
              <DeliveriesTable rows={event.deliveries} hide={['event', 'type']} show={['reason', 'replay']} />
            )}
            <Title order={4}>Webhook body</Title>
            <Code block className="code-json">
              {JSON.stringify(event.body, null, 2)}
            </Code>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
