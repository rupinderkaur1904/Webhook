import { Anchor, Badge, Card, Group, SegmentedControl, SimpleGrid, Stack, Table, Text, Title } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { useOverview } from '../api/hooks';
import type { DeadLetterReason, MetricsWindow } from '../api/types';
import { DEAD_LETTER_REASONS, percent, shortId } from '../components/labels';
import { EmptyState, QueryState } from '../components/QueryState';
import { CircuitBadge, HealthBadge } from '../components/StatusBadge';
import { Time } from '../components/Time';

const WINDOWS: MetricsWindow[] = ['1h', '24h', '7d'];

function Tile({ label, value, hint, to }: { label: string; value: ReactNode; hint?: ReactNode; to?: string }) {
  return (
    <Card withBorder padding="md">
      <Text size="xs" c="dimmed" tt="uppercase" fw={600}>
        {label}
      </Text>
      <Text size="xl" fw={700} mt={4}>
        {to ? (
          <Anchor component={Link} to={to} inherit>
            {value}
          </Anchor>
        ) : (
          value
        )}
      </Text>
      {hint && (
        <Text size="xs" c="dimmed" mt={4} component="div">
          {hint}
        </Text>
      )}
    </Card>
  );
}

export function OverviewPage() {
  const [params, setParams] = useSearchParams();
  const window = WINDOWS.includes(params.get('window') as MetricsWindow) ? (params.get('window') as MetricsWindow) : '24h';
  const query = useOverview(window);
  const o = query.data;

  return (
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Overview</Title>
        <SegmentedControl
          data={WINDOWS.map((w) => ({ value: w, label: `Last ${w}` }))}
          value={window}
          onChange={(value) => setParams({ window: value })}
        />
      </Group>
      <QueryState isLoading={query.isLoading} error={query.error} onRetry={() => query.refetch()}>
        {o && (
          <>
            <SimpleGrid cols={{ base: 1, xs: 2, md: 4 }}>
              <Tile label="Events submitted" value={o.eventsSubmitted} to="/events" />
              <Tile label="Delivered" value={o.deliveriesSucceeded} to="/deliveries?status=DELIVERED" />
              <Tile
                label="Dead-lettered"
                value={o.deliveriesDeadLettered.total}
                to="/dead-letters"
                hint={
                  Object.entries(o.deliveriesDeadLettered.byReason)
                    .map(([reason, n]) => `${DEAD_LETTER_REASONS[reason as DeadLetterReason]}: ${n}`)
                    .join(' · ') || 'none'
                }
              />
              <Tile
                label="Delivery success rate"
                value={percent(o.deliverySuccessRate)}
                hint="Delivered ÷ (delivered + dead-lettered) in the window"
              />
              <Tile
                label="Attempt success rate"
                value={percent(o.attempts.successRate)}
                hint={`${o.attempts.succeeded} succeeded · ${o.attempts.failed} failed`}
              />
              <Tile
                label="Attempt latency"
                value={o.attempts.latencyP50Ms == null ? '—' : `${o.attempts.latencyP50Ms} ms`}
                hint={o.attempts.latencyP95Ms == null ? 'p50' : `p50 · p95 ${o.attempts.latencyP95Ms} ms`}
              />
              <Tile
                label="Waiting to retry"
                value={o.current.retrying}
                to="/deliveries?status=RETRYING"
                hint="current, not windowed"
              />
              <Tile
                label="Queued / in flight"
                value={`${o.current.pending} / ${o.current.inFlight}`}
                to="/deliveries?status=PENDING&status=IN_FLIGHT"
                hint="current, not windowed"
              />
            </SimpleGrid>
            <Text size="xs" c="dimmed">
              Window <Time value={o.windowStart} /> to <Time value={o.windowEnd} />. Refreshes every 5 s.
            </Text>
            <Title order={4}>Endpoint health</Title>
            {o.endpointHealth.length === 0 ? (
              <EmptyState title="No endpoints yet">Register an endpoint to see its health here.</EmptyState>
            ) : (
              <Table.ScrollContainer minWidth={700}>
                <Table striped>
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
                        <Table.Td>
                          {percent(item.health.successRate)}
                          {item.health.attempts > 0 && (
                            <Text span size="xs" c="dimmed">
                              {' '}
                              of {item.health.attempts}
                            </Text>
                          )}
                        </Table.Td>
                        <Table.Td>{item.health.latencyP95Ms == null ? '—' : `${item.health.latencyP95Ms} ms`}</Table.Td>
                        <Table.Td>{item.health.deadLetters}</Table.Td>
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
            <Title order={4}>Recent attempts</Title>
            {o.recentActivity.length === 0 ? (
              <EmptyState title="No delivery attempts yet">Send a test event from an endpoint to see activity here.</EmptyState>
            ) : (
              <Table.ScrollContainer minWidth={800}>
                <Table striped>
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
                        <Table.Td>
                          <Time value={a.startedAt} />
                        </Table.Td>
                        <Table.Td>
                          <Anchor component={Link} to={`/deliveries/${a.deliveryId}`} ff="monospace" size="sm">
                            {shortId(a.deliveryId)}
                          </Anchor>
                        </Table.Td>
                        <Table.Td>
                          <Anchor component={Link} to={`/endpoints/${a.endpointId}`} size="sm">
                            {a.endpointName}
                          </Anchor>
                        </Table.Td>
                        <Table.Td>{a.eventType}</Table.Td>
                        <Table.Td>#{a.attemptNumber}</Table.Td>
                        <Table.Td>
                          <Badge
                            variant="light"
                            color={a.status === 'SUCCEEDED' ? 'green' : a.status === 'STARTED' ? 'blue' : a.status === 'ABANDONED' ? 'orange' : 'red'}
                          >
                            {a.outcome ?? a.status}
                          </Badge>
                        </Table.Td>
                        <Table.Td>{a.responseStatus ?? '—'}</Table.Td>
                        <Table.Td>{a.durationMs == null ? '—' : `${a.durationMs} ms`}</Table.Td>
                      </Table.Tr>
                    ))}
                  </Table.Tbody>
                </Table>
              </Table.ScrollContainer>
            )}
          </>
        )}
      </QueryState>
    </Stack>
  );
}
