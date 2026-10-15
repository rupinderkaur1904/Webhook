import { Group, MultiSelect, Select, Stack, TextInput, Title } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useSearchParams } from 'react-router-dom';
import { useDeliveries, useEndpoints } from '../api/hooks';
import type { DeliveryFilter, DeliveryStatus } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { EmptyState, QueryState } from '../components/QueryState';

const STATUSES: DeliveryStatus[] = ['PENDING', 'RETRYING', 'IN_FLIGHT', 'DELIVERED', 'DEAD_LETTERED', 'CANCELLED'];

export function DeliveriesPage() {
  // Filters live in the URL so a filtered view survives reloads and can be shared.
  const [params, setParams] = useSearchParams();
  const filter: DeliveryFilter = {
    statuses: params.getAll('status').filter((s): s is DeliveryStatus => STATUSES.includes(s as DeliveryStatus)),
    endpointId: params.get('endpointId') ?? undefined,
    eventId: params.get('eventId') ?? undefined,
    eventType: params.get('eventType') ?? undefined,
  };
  const deliveries = useDeliveries(filter);
  const endpoints = useEndpoints();
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];
  const filtered = Boolean(filter.statuses?.length || filter.endpointId || filter.eventId || filter.eventType);

  const update = (name: string, values: string[]) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      next.delete(name);
      values.filter(Boolean).forEach((v) => next.append(name, v));
      return next;
    });
  const updateEventType = useDebouncedCallback((value: string) => update('eventType', [value.trim()]), 300);

  return (
    <Stack>
      <Title order={2}>Deliveries</Title>
      <Group align="flex-end">
        <MultiSelect
          label="Status"
          placeholder="All statuses"
          data={STATUSES}
          value={filter.statuses}
          onChange={(values) => update('status', values)}
          clearable
          w={360}
        />
        <Select
          label="Endpoint"
          placeholder="All endpoints"
          data={(endpoints.data ?? []).map((e) => ({ value: e.id, label: e.name }))}
          value={filter.endpointId ?? null}
          onChange={(value) => update('endpointId', value ? [value] : [])}
          clearable
          w={220}
        />
        <TextInput
          label="Event type"
          placeholder="e.g. order.created"
          defaultValue={filter.eventType}
          onChange={(e) => updateEventType(e.currentTarget.value)}
          w={200}
        />
      </Group>
      <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
        {rows.length === 0 ? (
          <EmptyState title="No deliveries">
            {filtered ? 'Nothing matches these filters.' : 'Submit an event or send a test event from Endpoints.'}
          </EmptyState>
        ) : (
          <DeliveriesTable
            rows={rows}
            hasMore={deliveries.hasNextPage}
            loadingMore={deliveries.isFetchingNextPage}
            onLoadMore={() => deliveries.fetchNextPage()}
          />
        )}
      </QueryState>
    </Stack>
  );
}
