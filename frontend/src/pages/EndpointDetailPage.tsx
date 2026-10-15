import { Anchor, Badge, Button, Card, Code, Group, SimpleGrid, Stack, Switch, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { Link, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useDeliveries, useEndpoint, useSendTestEvent, useSetEndpointEnabled } from '../api/hooks';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { Field } from '../components/Field';
import { EmptyState, QueryState } from '../components/QueryState';
import { Time } from '../components/Time';

export function EndpointDetailPage() {
  const { id = '' } = useParams();
  const endpoint = useEndpoint(id);
  const deliveries = useDeliveries({ endpointId: id });
  const setEnabled = useSetEndpointEnabled();
  const sendTest = useSendTestEvent();
  const e = endpoint.data;
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack>
      <Anchor component={Link} to="/endpoints" size="sm">
        ← Endpoints
      </Anchor>
      <QueryState isLoading={endpoint.isLoading} error={endpoint.error} onRetry={() => endpoint.refetch()}>
        {e && (
          <>
            <Group justify="space-between">
              <Group>
                <Title order={2}>{e.name}</Title>
                <Badge color={e.enabled ? 'green' : 'gray'} variant="light">
                  {e.enabled ? 'enabled' : 'disabled'}
                </Badge>
              </Group>
              <Group>
                <Switch
                  label="Enabled"
                  checked={e.enabled}
                  onChange={(ev) =>
                    setEnabled.mutate(
                      { id: e.id, enabled: ev.currentTarget.checked },
                      { onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }) },
                    )
                  }
                />
                <Button
                  variant="light"
                  disabled={!e.enabled}
                  loading={sendTest.isPending}
                  onClick={() =>
                    sendTest.mutate(e.id, {
                      onSuccess: () => notifications.show({ color: 'green', message: 'Test event queued' }),
                      onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }),
                    })
                  }
                >
                  Send test event
                </Button>
              </Group>
            </Group>
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="URL">
                  <Text size="sm" style={{ wordBreak: 'break-all' }}>
                    {e.url}
                  </Text>
                </Field>
                <Field label="Event types">
                  {e.eventTypes.length === 0
                    ? 'all events'
                    : e.eventTypes.map((t) => (
                        <Badge key={t} variant="outline" mr={4}>
                          {t}
                        </Badge>
                      ))}
                </Field>
                <Field label="Signing secret">
                  <Code>{e.secretPrefix}…</Code>
                </Field>
                {e.description && <Field label="Description">{e.description}</Field>}
                <Field label="Created">
                  <Time value={e.createdAt} />
                </Field>
                <Field label="Updated">
                  <Time value={e.updatedAt} />
                </Field>
              </SimpleGrid>
            </Card>
            <Group justify="space-between">
              <Title order={4}>Deliveries to this endpoint</Title>
              <Anchor component={Link} to={`/dead-letters?endpointId=${e.id}`} size="sm">
                Dead letters for this endpoint →
              </Anchor>
            </Group>
            <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
              {rows.length === 0 ? (
                <EmptyState title="No deliveries yet">Send a test event to try this endpoint.</EmptyState>
              ) : (
                <DeliveriesTable
                  rows={rows}
                  hide={['endpoint']}
                  show={['replay']}
                  hasMore={deliveries.hasNextPage}
                  loadingMore={deliveries.isFetchingNextPage}
                  onLoadMore={() => deliveries.fetchNextPage()}
                />
              )}
            </QueryState>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
