import { Anchor, Button, Group, MultiSelect, Stack, Table, Text, Title } from '@mantine/core';
import { Link, useSearchParams } from 'react-router-dom';
import { useDeliveries } from '../api/hooks';
import type { DeliveryStatus } from '../api/types';
import { EmptyState, QueryState } from '../components/QueryState';
import { StatusBadge } from '../components/StatusBadge';
import { Time } from '../components/Time';

const STATUSES: DeliveryStatus[] = ['PENDING', 'RETRYING', 'IN_FLIGHT', 'DELIVERED', 'DEAD_LETTERED', 'CANCELLED'];

export function DeliveriesPage() {
  // Filters live in the URL so a filtered view survives reloads and can be shared.
  const [params, setParams] = useSearchParams();
  const statuses = params.getAll('status').filter((s): s is DeliveryStatus => STATUSES.includes(s as DeliveryStatus));
  const deliveries = useDeliveries(statuses);
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack>
      <Title order={2}>Deliveries</Title>
      <MultiSelect
        label="Status"
        placeholder="All statuses"
        data={STATUSES}
        value={statuses}
        onChange={(values) => setParams(values.map((v): [string, string] => ['status', v]))}
        clearable
        maw={480}
      />
      <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
        {rows.length === 0 ? (
          <EmptyState title="No deliveries">
            {statuses.length > 0 ? 'Nothing matches these filters.' : 'Submit an event or send a test event from Endpoints.'}
          </EmptyState>
        ) : (
          <>
            <Table.ScrollContainer minWidth={900}>
              <Table striped highlightOnHover>
                <Table.Thead>
                  <Table.Tr>
                    <Table.Th>Delivery</Table.Th>
                    <Table.Th>Event</Table.Th>
                    <Table.Th>Endpoint</Table.Th>
                    <Table.Th>Type</Table.Th>
                    <Table.Th>Status</Table.Th>
                    <Table.Th>Attempts</Table.Th>
                    <Table.Th>Last HTTP</Table.Th>
                    <Table.Th>Last attempt</Table.Th>
                    <Table.Th>Next retry</Table.Th>
                  </Table.Tr>
                </Table.Thead>
                <Table.Tbody>
                  {rows.map((d) => (
                    <Table.Tr key={d.id}>
                      <Table.Td>
                        <Anchor component={Link} to={`/deliveries/${d.id}`} ff="monospace" size="sm">
                          {d.id.slice(0, 8)}
                        </Anchor>
                      </Table.Td>
                      <Table.Td>
                        <Text ff="monospace" size="sm">
                          {d.eventId.slice(0, 8)}
                        </Text>
                      </Table.Td>
                      <Table.Td>{d.endpointName}</Table.Td>
                      <Table.Td>{d.eventType}</Table.Td>
                      <Table.Td>
                        <StatusBadge status={d.status} />
                      </Table.Td>
                      <Table.Td>
                        {d.attemptCount}/{d.maxAttempts}
                      </Table.Td>
                      <Table.Td>{d.lastResponseStatus ?? (d.lastError ? '—' : '')}</Table.Td>
                      <Table.Td>
                        <Time value={d.lastAttemptAt} />
                      </Table.Td>
                      <Table.Td>
                        <Time value={d.status === 'RETRYING' ? d.nextAttemptAt : null} />
                      </Table.Td>
                    </Table.Tr>
                  ))}
                </Table.Tbody>
              </Table>
            </Table.ScrollContainer>
            {deliveries.hasNextPage && (
              <Group justify="center">
                <Button variant="default" loading={deliveries.isFetchingNextPage} onClick={() => deliveries.fetchNextPage()}>
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
