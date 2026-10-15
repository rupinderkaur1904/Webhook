import { Alert, Group, Select, Stack, Text, Title } from '@mantine/core';
import { useSearchParams } from 'react-router-dom';
import { useDeliveries, useEndpoints } from '../api/hooks';
import type { DeadLetterReason } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { DEAD_LETTER_REASONS } from '../components/labels';
import { EmptyState, QueryState } from '../components/QueryState';

const REASONS = Object.keys(DEAD_LETTER_REASONS) as DeadLetterReason[];

export function DeadLettersPage() {
  const [params, setParams] = useSearchParams();
  const reasonParam = params.get('reason');
  const reason = REASONS.includes(reasonParam as DeadLetterReason) ? (reasonParam as DeadLetterReason) : undefined;
  const endpointId = params.get('endpointId') ?? undefined;
  const deliveries = useDeliveries({ statuses: ['DEAD_LETTERED'], deadLetterReason: reason, endpointId });
  const endpoints = useEndpoints();
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];

  const set = (name: string, value: string | null) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (value) next.set(name, value);
      else next.delete(name);
      return next;
    });

  return (
    <Stack>
      <Title order={2}>Dead letters</Title>
      <Alert variant="light" color="gray">
        <Text size="sm">
          These deliveries stopped being retried. Replaying one sends the same event (same <code>webhook-id</code>) to the
          endpoint's current URL as a new delivery with a fresh retry budget. The dead letter and its attempts stay as they
          are.
        </Text>
      </Alert>
      <Group align="flex-end">
        <Select
          label="Reason"
          placeholder="All reasons"
          data={REASONS.map((r) => ({ value: r, label: DEAD_LETTER_REASONS[r] }))}
          value={reason ?? null}
          onChange={(value) => set('reason', value)}
          clearable
          w={240}
        />
        <Select
          label="Endpoint"
          placeholder="All endpoints"
          data={(endpoints.data ?? []).map((e) => ({ value: e.id, label: e.name }))}
          value={endpointId ?? null}
          onChange={(value) => set('endpointId', value)}
          clearable
          w={220}
        />
      </Group>
      <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
        {rows.length === 0 ? (
          <EmptyState title="No dead letters">
            {reason || endpointId ? 'Nothing matches these filters.' : 'Every delivery is either delivered or still being retried.'}
          </EmptyState>
        ) : (
          <DeliveriesTable
            rows={rows}
            show={['reason', 'replay']}
            hasMore={deliveries.hasNextPage}
            loadingMore={deliveries.isFetchingNextPage}
            onLoadMore={() => deliveries.fetchNextPage()}
          />
        )}
      </QueryState>
    </Stack>
  );
}
