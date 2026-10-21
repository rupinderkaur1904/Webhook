import { Anchor, Group, SegmentedControl, Table, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useDeliveries, useOverview } from '../api/hooks';
import type { DeadLetterReason, MetricsWindow, RecentAttempt } from '../api/types';
import { DEAD_LETTER_SHORT, percent, shortId } from '../components/labels';
import { PageHeader } from '../components/PageHeader';
import { PipelineLane } from '../components/PipelineLane';
import { EmptyState, QueryState } from '../components/QueryState';
import { ReplayButton } from '../components/ReplayButton';
import { CircuitBadge, HealthBadge, Pill, StatusBadge, type Tone } from '../components/StatusBadge';
import { Time } from '../components/Time';

const WINDOWS: MetricsWindow[] = ['1h', '24h', '7d'];

function Metric({ label, value, hint, rate }: { label: string; value: ReactNode; hint?: ReactNode; rate?: number | null }) {
  return (
    <div className="panel metric">
      <p className="metric-label">{label}</p>
      <p className="metric-value">{value}</p>
      {rate !== undefined && (
        <div className="metric-bar" aria-hidden="true">
          <span className="metric-bar-ok" style={{ width: `${(rate ?? 0) * 100}%` }} />
          {rate != null && <span className="metric-bar-bad" style={{ width: `${(1 - rate) * 100}%` }} />}
        </div>
      )}
      {hint && <div className="metric-hint">{hint}</div>}
    </div>
  );
}

const ATTEMPT_TONES: Record<RecentAttempt['status'], Tone> = {
  SUCCEEDED: 'delivered',
  STARTED: 'in-flight',
  ABANDONED: 'retrying',
  FAILED: 'dead-letter',
};

/** Dead letters waiting for a replay, newest first, and how many deliveries are queued for a retry. */
function NeedsAttention({ retrying }: { retrying: number }) {
  const deadLetters = useDeliveries({ statuses: ['DEAD_LETTERED'] });
  const rows = deadLetters.data?.pages.flatMap((p) => p.items) ?? [];
  const count = deadLetters.hasNextPage ? `${rows.length}+` : String(rows.length);
  return (
    <section className="panel attention" aria-labelledby="attention-title">
      <div className="panel-head">
        <h3 id="attention-title" className="panel-title">
          Needs attention
        </h3>
        {rows.length > 0 && <span className="count-badge">{count}</span>}
      </div>
      <div className="attention-body">
        {deadLetters.error ? (
          <Text size="sm" c="dimmed">
            Dead letters could not be loaded.
          </Text>
        ) : rows.length === 0 ? (
          <Text size="sm" c="dimmed">
            {deadLetters.isLoading ? 'Loading…' : 'No dead letters. Every delivery is delivered or still being retried.'}
          </Text>
        ) : (
          rows.slice(0, 3).map((d) => (
            <div key={d.id} className="attention-item">
              <div className="attention-row">
                <Anchor component={Link} to={`/deliveries/${d.id}`} className="mono" size="sm">
                  {shortId(d.id)}
                </Anchor>
                <StatusBadge status={d.status} />
              </div>
              <p className="attention-meta">
                {d.endpointName} · {d.eventType}
              </p>
              <p className="attention-reason">
                {d.deadLetterReason} · {d.attemptCount}/{d.maxAttempts}
                {d.lastResponseStatus != null && ` · last HTTP ${d.lastResponseStatus}`}
              </p>
              <ReplayButton deliveryId={d.id} />
            </div>
          ))
        )}
        {rows.length > 3 && (
          <Anchor component={Link} to="/dead-letters" size="sm">
            All dead letters →
          </Anchor>
        )}
      </div>
      <Link to="/deliveries?status=RETRYING" className="attention-foot">
        <Pill tone="retrying">Retrying</Pill>
        <span>
          {retrying} {retrying === 1 ? 'delivery' : 'deliveries'} waiting to retry
        </span>
      </Link>
    </section>
  );
}

export function OverviewPage() {
  const [params, setParams] = useSearchParams();
  const window = WINDOWS.includes(params.get('window') as MetricsWindow) ? (params.get('window') as MetricsWindow) : '24h';
  const query = useOverview(window);
  const o = query.data;
  const byReason = o
    ? Object.entries(o.deliveriesDeadLettered.byReason)
        .map(([reason, n]) => `${n} ${DEAD_LETTER_SHORT[reason as DeadLetterReason]}`)
        .join(' · ')
    : '';

  return (
    <div className="page">
      <PageHeader
        title="Overview"
        description={
          o ? (
            <span className="live">
              <span className="live-dot" aria-hidden="true" />
              Refreshes every 5 s · window <Time value={o.windowStart} format="short" /> → <Time value={o.windowEnd} format="short" /> (last{' '}
              {window})
            </span>
          ) : (
            'Delivery health across every endpoint.'
          )
        }
        actions={
          <SegmentedControl
            aria-label="Metrics window"
            data={WINDOWS.map((w) => ({ value: w, label: w }))}
            value={window}
            onChange={(value) => setParams({ window: value })}
          />
        }
      />
      <QueryState isLoading={query.isLoading} error={query.error} onRetry={() => query.refetch()}>
        {o && (
          <>
            <section className="panel lane-panel" aria-labelledby="pipeline-title">
              <div className="panel-head">
                <h3 id="pipeline-title" className="panel-title">
                  Delivery pipeline
                </h3>
                <span className="panel-note">current counts · not windowed</span>
              </div>
              <PipelineLane overview={o} />
            </section>

            <div className="metrics">
              <Metric
                label="Delivery success rate"
                value={percent(o.deliverySuccessRate)}
                rate={o.deliverySuccessRate}
                hint="delivered ÷ (delivered + dead-lettered)"
              />
              <Metric
                label="Attempt success rate"
                value={percent(o.attempts.successRate)}
                rate={o.attempts.successRate}
                hint={`${o.attempts.succeeded} succeeded · ${o.attempts.failed} failed`}
              />
              <Metric
                label="Attempt latency p50"
                value={o.attempts.latencyP50Ms == null ? '—' : `${o.attempts.latencyP50Ms} ms`}
                hint={o.attempts.latencyP95Ms == null ? 'p95 —' : `p95 ${o.attempts.latencyP95Ms} ms`}
              />
              <Metric label={`Dead-lettered · ${window}`} value={o.deliveriesDeadLettered.total} hint={byReason || 'none'} />
            </div>

            <div className="overview-columns">
              <section className="panel" aria-labelledby="recent-title">
                <div className="panel-head">
                  <h3 id="recent-title" className="panel-title">
                    Recent attempts
                  </h3>
                  <Anchor component={Link} to="/deliveries" size="sm">
                    View all deliveries →
                  </Anchor>
                </div>
                {o.recentActivity.length === 0 ? (
                  <EmptyState title="No delivery attempts yet">Send a test event from an endpoint to see activity here.</EmptyState>
                ) : (
                  <Table.ScrollContainer minWidth={720}>
                    <Table className="data-table">
                      <Table.Thead>
                        <Table.Tr>
                          <Table.Th>Started</Table.Th>
                          <Table.Th>Delivery</Table.Th>
                          <Table.Th>Endpoint</Table.Th>
                          <Table.Th>Type</Table.Th>
                          <Table.Th>Attempt</Table.Th>
                          <Table.Th>Outcome</Table.Th>
                          <Table.Th>HTTP</Table.Th>
                          <Table.Th>Latency</Table.Th>
                        </Table.Tr>
                      </Table.Thead>
                      <Table.Tbody>
                        {o.recentActivity.map((a) => (
                          <Table.Tr key={a.attemptId}>
                            <Table.Td className="mono dim">
                              <Time value={a.startedAt} format="compact" />
                            </Table.Td>
                            <Table.Td>
                              <Anchor component={Link} to={`/deliveries/${a.deliveryId}`} className="mono" size="sm">
                                {shortId(a.deliveryId)}
                              </Anchor>
                            </Table.Td>
                            <Table.Td>
                              <Anchor component={Link} to={`/endpoints/${a.endpointId}`} size="sm" c="inherit">
                                {a.endpointName}
                              </Anchor>
                            </Table.Td>
                            <Table.Td className="mono dim">{a.eventType}</Table.Td>
                            <Table.Td className="mono">#{a.attemptNumber}</Table.Td>
                            <Table.Td>
                              <Pill tone={ATTEMPT_TONES[a.status]}>{a.outcome ?? a.status}</Pill>
                            </Table.Td>
                            <Table.Td className="mono">{a.responseStatus ?? '—'}</Table.Td>
                            <Table.Td className="mono">{a.durationMs == null ? '—' : `${a.durationMs.toLocaleString()} ms`}</Table.Td>
                          </Table.Tr>
                        ))}
                      </Table.Tbody>
                    </Table>
                  </Table.ScrollContainer>
                )}
              </section>
              <NeedsAttention retrying={o.current.retrying} />
            </div>

            <section className="panel" aria-labelledby="health-title">
              <div className="panel-head">
                <h3 id="health-title" className="panel-title">
                  Endpoint health
                </h3>
                <span className="panel-note">attempts in the last {window}</span>
              </div>
              {o.endpointHealth.length === 0 ? (
                <EmptyState title="No endpoints yet">Register an endpoint to see its health here.</EmptyState>
              ) : (
                <Table.ScrollContainer minWidth={700}>
                  <Table className="data-table">
                    <Table.Thead>
                      <Table.Tr>
                        <Table.Th>Endpoint</Table.Th>
                        <Table.Th>Health</Table.Th>
                        <Table.Th>Attempt success</Table.Th>
                        <Table.Th>p95 latency</Table.Th>
                        <Table.Th>Dead letters</Table.Th>
                        <Table.Th>Last failure</Table.Th>
                      </Table.Tr>
                    </Table.Thead>
                    <Table.Tbody>
                      {o.endpointHealth.map((item) => (
                        <Table.Tr key={item.endpointId}>
                          <Table.Td>
                            <Anchor component={Link} to={`/endpoints/${item.endpointId}`} size="sm">
                              {item.name}
                            </Anchor>
                          </Table.Td>
                          <Table.Td>
                            <Group gap={6} wrap="nowrap">
                              <HealthBadge status={item.health.status} />
                              {item.circuit.state !== 'CLOSED' && <CircuitBadge state={item.circuit.state} />}
                            </Group>
                          </Table.Td>
                          <Table.Td className="mono">
                            {percent(item.health.successRate)}
                            {item.health.attempts > 0 && <span className="dim"> of {item.health.attempts}</span>}
                          </Table.Td>
                          <Table.Td className="mono">{item.health.latencyP95Ms == null ? '—' : `${item.health.latencyP95Ms} ms`}</Table.Td>
                          <Table.Td className="mono">{item.health.deadLetters}</Table.Td>
                          <Table.Td>
                            {item.health.lastFailure ? (
                              <Text size="sm">
                                <Time value={item.health.lastFailure.at} />{' '}
                                <Text span size="xs" c="dimmed">
                                  {item.health.lastFailure.responseStatus != null
                                    ? `HTTP ${item.health.lastFailure.responseStatus}`
                                    : item.health.lastFailure.outcome}
                                </Text>
                              </Text>
                            ) : (
                              '—'
                            )}
                          </Table.Td>
                        </Table.Tr>
                      ))}
                    </Table.Tbody>
                  </Table>
                </Table.ScrollContainer>
              )}
            </section>
          </>
        )}
      </QueryState>
    </div>
  );
}
