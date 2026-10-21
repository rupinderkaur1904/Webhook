import { MultiSelect, Select, TextInput, UnstyledButton } from '@mantine/core';
import { useDebouncedCallback } from '@mantine/hooks';
import { useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useDeliveries, useEndpoints, useOverview } from '../api/hooks';
import type { DeliveryFilter, DeliveryStatus, Overview } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { SearchIcon } from '../components/Icons';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, QueryState } from '../components/QueryState';
import { STATUS_LABELS, StatusBadge } from '../components/StatusBadge';

const STATUSES: DeliveryStatus[] = ['PENDING', 'RETRYING', 'IN_FLIGHT', 'DELIVERED', 'DEAD_LETTERED', 'CANCELLED'];
const CHIPS: DeliveryStatus[] = ['PENDING', 'IN_FLIGHT', 'RETRYING', 'DELIVERED', 'DEAD_LETTERED'];

/** Live counts for the waiting states; delivered and dead-lettered cover the last 24 hours. */
function chipCount(o: Overview | undefined, status: DeliveryStatus) {
  if (!o) return null;
  switch (status) {
    case 'PENDING':
      return o.current.pending;
    case 'IN_FLIGHT':
      return o.current.inFlight;
    case 'RETRYING':
      return o.current.retrying;
    case 'DELIVERED':
      return o.deliveriesSucceeded;
    case 'DEAD_LETTERED':
      return o.deliveriesDeadLettered.total;
    default:
      return null;
  }
}

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
  const overview = useOverview('24h');
  const [cleared, setCleared] = useState(0);
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
  const toggleStatus = (status: DeliveryStatus) => {
    const current = filter.statuses ?? [];
    update('status', current.includes(status) ? current.filter((s) => s !== status) : [...current, status]);
  };

  return (
    <div className="page">
      <PageHeader
        title="Deliveries"
        description="One row per event × endpoint. Filters live in the URL, so a filtered view can be shared."
      />
      <div className="filters">
        <MultiSelect
          label="Status"
          placeholder="All statuses"
          data={STATUSES.map((s) => ({ value: s, label: STATUS_LABELS[s] }))}
          value={filter.statuses}
          onChange={(values) => update('status', values)}
          clearable
          w={300}
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
          // Remount when "Clear filters" empties the URL, so the uncontrolled input clears too.
          key={cleared}
          label="Event type"
          placeholder="e.g. order.*"
          leftSection={<SearchIcon />}
          defaultValue={filter.eventType}
          onChange={(e) => updateEventType(e.currentTarget.value)}
          w={240}
        />
        {filtered && (
          <UnstyledButton
            className="link-button"
            onClick={() => {
              setParams(new URLSearchParams());
              setCleared((n) => n + 1);
            }}
          >
            Clear filters
          </UnstyledButton>
        )}
      </div>
      <div className="status-chips" role="group" aria-label="Filter by status">
        {CHIPS.map((status) => {
          const count = chipCount(overview.data, status);
          const active = filter.statuses?.includes(status) ?? false;
          return (
            <UnstyledButton
              key={status}
              className={`status-chip${active ? ' active' : ''}`}
              aria-pressed={active}
              onClick={() => toggleStatus(status)}
              title={status === 'DELIVERED' || status === 'DEAD_LETTERED' ? 'Last 24 hours' : 'Right now'}
            >
              <StatusBadge status={status} />
              {count != null && <span className="status-chip-count">{count.toLocaleString()}</span>}
            </UnstyledButton>
          );
        })}
      </div>
      <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
        {rows.length === 0 ? (
          <div className="panel">
            <EmptyState title="No deliveries">
              {filtered ? 'Nothing matches these filters.' : 'Submit an event or send a test event from Endpoints.'}
            </EmptyState>
          </div>
        ) : (
          <DeliveriesTable
            rows={rows}
            hasMore={deliveries.hasNextPage}
            loadingMore={deliveries.isFetchingNextPage}
            onLoadMore={() => deliveries.fetchNextPage()}
          />
        )}
      </QueryState>
    </div>
  );
}
