import { Anchor, Button, Group, Select } from '@mantine/core';
import { useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useDeliveries, useEndpoints, useOverview } from '../api/hooks';
import type { DeadLetterReason, DeliverySummary } from '../api/types';
import { DEAD_LETTER_REASONS, shortId } from '../components/labels';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, QueryState } from '../components/QueryState';
import { ReplayDrawer } from '../components/ReplayDrawer';
import { StatusBadge, Tag } from '../components/StatusBadge';
import { Time } from '../components/Time';

const REASONS = Object.keys(DEAD_LETTER_REASONS) as DeadLetterReason[];

function DeadLetterCard({ d, onReplay }: { d: DeliverySummary; onReplay: () => void }) {
  return (
    <article className="panel dl-card" aria-label={`Dead letter ${shortId(d.id)}`}>
      <div className="dl-head">
        <Anchor component={Link} to={`/deliveries/${d.id}`} className="mono dl-id">
          {shortId(d.id)}
        </Anchor>
        <StatusBadge status={d.status} />
        {d.deadLetterReason && <Tag tone="dead-letter">{d.deadLetterReason}</Tag>}
        <span className="dl-rule" aria-hidden="true" />
        <span className="mono dim dl-time">
          dead-lettered <Time value={d.deadLetteredAt} />
        </span>
      </div>
      <dl className="dl-fields">
        <div>
          <dt>Endpoint</dt>
          <dd>
            <Anchor component={Link} to={`/endpoints/${d.endpointId}`} c="inherit" size="sm">
              {d.endpointName}
            </Anchor>
          </dd>
        </div>
        <div>
          <dt>Event type</dt>
          <dd className="mono">{d.eventType}</dd>
        </div>
        <div>
          <dt>Attempts</dt>
          <dd className="mono">
            {d.attemptCount} / {d.maxAttempts}
          </dd>
        </div>
        <div>
          <dt>Last HTTP</dt>
          <dd className="mono">{d.lastResponseStatus ?? '—'}</dd>
        </div>
        <div>
          <dt>Reason</dt>
          <dd>{d.deadLetterReason ? DEAD_LETTER_REASONS[d.deadLetterReason] : '—'}</dd>
        </div>
      </dl>
      <div className="dl-foot">
        <span className="mono dim dl-error">{d.lastError ?? ''}</span>
        <Button size="xs" variant="default" onClick={onReplay}>
          Replay
        </Button>
      </div>
    </article>
  );
}

export function DeadLettersPage() {
  const [params, setParams] = useSearchParams();
  const reasonParam = params.get('reason');
  const reason = REASONS.includes(reasonParam as DeadLetterReason) ? (reasonParam as DeadLetterReason) : undefined;
  const endpointId = params.get('endpointId') ?? undefined;
  const deliveries = useDeliveries({ statuses: ['DEAD_LETTERED'], deadLetterReason: reason, endpointId });
  const endpoints = useEndpoints();
  const overview = useOverview('24h');
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];
  const [replaying, setReplaying] = useState<string | null>(null);
  const byReason = overview.data?.deliveriesDeadLettered;

  const set = (name: string, value: string | null) =>
    setParams((current) => {
      const next = new URLSearchParams(current);
      if (value) next.set(name, value);
      else next.delete(name);
      return next;
    });

  const reasonCards: { value: DeadLetterReason | null; label: string; count: number | undefined }[] = [
    { value: null, label: 'All reasons', count: byReason?.total },
    ...REASONS.map((r) => ({ value: r, label: DEAD_LETTER_REASONS[r], count: byReason ? (byReason.byReason[r] ?? 0) : undefined })),
  ];

  return (
    <div className="page">
      <PageHeader
        title="Dead letters"
        description={
          <>
            Deliveries that stopped retrying. Fix the endpoint, then replay to send the same event again.
          </>
        }
      />
      <div className="reason-cards" role="group" aria-label="Filter by reason">
        {reasonCards.map((card) => {
          const active = (reason ?? null) === card.value;
          return (
            <button
              key={card.label}
              type="button"
              className={`panel reason-card${active ? ' active' : ''}`}
              aria-pressed={active}
              onClick={() => set('reason', card.value)}
            >
              <span className="reason-card-label">{card.label}</span>
              <span className="reason-card-count">{card.count ?? '—'}</span>
            </button>
          );
        })}
      </div>
      <Group justify="space-between" align="flex-end">
        <Select
          label="Endpoint"
          placeholder="All endpoints"
          data={(endpoints.data ?? []).map((e) => ({ value: e.id, label: e.name }))}
          value={endpointId ?? null}
          onChange={(value) => set('endpointId', value)}
          clearable
          w={240}
        />
        <span className="panel-note">counts above: dead-lettered in the last 24 h</span>
      </Group>
      <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
        {rows.length === 0 ? (
          <div className="panel">
            <EmptyState title="No dead letters">
              {reason || endpointId ? 'Nothing matches these filters.' : 'Every delivery is either delivered or still being retried.'}
            </EmptyState>
          </div>
        ) : (
          <div className="dl-list">
            {rows.map((d) => (
              <DeadLetterCard key={d.id} d={d} onReplay={() => setReplaying(d.id)} />
            ))}
            {deliveries.hasNextPage && (
              <Group justify="center">
                <Button variant="default" loading={deliveries.isFetchingNextPage} onClick={() => deliveries.fetchNextPage()}>
                  Load more
                </Button>
              </Group>
            )}
          </div>
        )}
      </QueryState>
      <ReplayDrawer deliveryId={replaying} onClose={() => setReplaying(null)} />
    </div>
  );
}
