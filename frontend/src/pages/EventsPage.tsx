import { Anchor, Badge, Button, Group, Stack, Table, Text, TextInput, Title } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { Link, useSearchParams } from 'react-router-dom';
import { useEvents } from '../api/hooks';
import { shortId } from '../components/labels';
import { EmptyState, QueryState } from '../components/QueryState';
import { Time } from '../components/Time';

export function EventsPage() {
  const [params, setParams] = useSearchParams();
  const eventType = params.get('eventType') ?? undefined;
  const events = useEvents(eventType);
  const rows = events.data?.pages.flatMap((p) => p.items) ?? [];
  const updateType = useDebouncedCallback(
    (value: string) => setParams(value.trim() ? { eventType: value.trim() } : {}),
    300,
  );

  return (
    <Stack>
      <Title order={2}>Events</Title>
      <TextInput
        label="Event type"
        placeholder="e.g. order.created"
        defaultValue={eventType}
        onChange={(e) => updateType(e.currentTarget.value)}
        w={240}
      />
      <QueryState isLoading={events.isLoading} error={events.error} onRetry={() => events.refetch()}>
        {rows.length === 0 ? (
          <EmptyState title="No events">
            {eventType ? 'No events of this type.' : 'Submit an event with POST /api/v1/events, or send a test event from an endpoint.'}
          </EmptyState>
        ) : (
          <>
            <Table.ScrollContainer minWidth={800}>
              <Table striped highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Event</Table.Th>
                    <Table.Th>Type</Table.Th>
                    <Table.Th>Idempotency key</Table.Th>
                    <Table.Th>Received</Table.Th>
                    <Table.Th>Deliveries</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((e) => (
                    <Table.Tr key={e.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/events/${e.id}`} ff="monospace" size="sm">
                          {shortId(e.id)}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>{e.eventType}</Table.Td>
                      <Table.Td>
                        <Text size="sm" ff="monospace" truncate maw={240}>
                          {e.idempotencyKey}
                        </Text>
                      </Table.Td>
                      <Table.Td>
                        <Time value={e.createdAt} />
                      </Table.Td>
                      <Table.Td>
                        <Group gap={4}>
                          {e.deliveryCount === 0 && (
                            <Text size="sm" c="dimmed">
                              no matching endpoint
                            </Text>
                          )}
                          {e.deliveredCount > 0 && <Badge color="green" variant="light">{e.deliveredCount} delivered</Badge>}
                          {e.activeCount > 0 && <Badge color="yellow" variant="light">{e.activeCount} in progress</Badge>}
                          {e.deadLetteredCount > 0 && <Badge color="red" variant="light">{e.deadLetteredCount} dead-lettered</Badge>}
                        </Group>
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            {events.hasNextPage && (
              <Group justify="center">
                <Button variant="default" loading={events.isFetchingNextPage} onClick={() => events.fetchNextPage()}>
                  Load more
                </Button>
              </Group>
            )}
          </>
        )}
      </QueryState>
    </Stack>
  );
}
