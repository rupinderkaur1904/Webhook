import { Anchor, Button, Group, Table, Text, TextInput } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { Link, useSearchParams } from 'react-router-dom';
import { useEvents } from '../api/hooks';
import { shortId } from '../components/labels';
import { SearchIcon } from '../components/Icons';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, QueryState } from '../components/QueryState';
import { Pill } from '../components/StatusBadge';
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
    <div className="page">
      <PageHeader title="Events" description="Every accepted event, newest first. Each fans out to one delivery per matching endpoint." />
      <TextInput
        label="Event type"
        placeholder="e.g. order.created"
        leftSection={<SearchIcon />}
        defaultValue={eventType}
        onChange={(e) => updateType(e.currentTarget.value)}
        w={240}
      />
      <QueryState isLoading={events.isLoading} error={events.error} onRetry={() => events.refetch()}>
        {rows.length === 0 ? (
          <div className="panel">
            <EmptyState title="No events">
              {eventType ? 'No events of this type.' : 'Submit an event with POST /api/v1/events, or send a test event from an endpoint.'}
            </EmptyState>
          </div>
        ) : (
          <>
            <div className="panel table-panel">
              <Table.ScrollContainer minWidth={800}>
                <Table className="data-table" highlightOnHover>
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
                          <Anchor component={Link} to={`/events/${e.id}`} className="mono" size="sm">
                            {shortId(e.id)}
                          </Anchor>
                        </Table.Td>
                        <Table.Td className="mono dim">{e.eventType}</Table.Td>
                        <Table.Td>
                          <Text size="sm" ff="monospace" truncate maw={240}>
                            {e.idempotencyKey}
                          </Text>
                        </Table.Td>
                        <Table.Td className="mono dim">
                          <Time value={e.createdAt} />
                        </Table.Td>
                        <Table.Td>
                          <Group gap={4}>
                            {e.deliveryCount === 0 && (
                              <Text size="sm" c="dimmed">
                                no matching endpoint
                              </Text>
                            )}
                            {e.deliveredCount > 0 && <Pill tone="delivered">{e.deliveredCount} delivered</Pill>}
                            {e.activeCount > 0 && <Pill tone="retrying">{e.activeCount} in progress</Pill>}
                            {e.deadLetteredCount > 0 && <Pill tone="dead-letter">{e.deadLetteredCount} dead-lettered</Pill>}
                          </Group>
                        </Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            </div>
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
    </div>
  );
}
