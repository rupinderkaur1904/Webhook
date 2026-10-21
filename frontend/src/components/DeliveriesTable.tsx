import { Anchor, Button, Group, Table, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { DeliverySummary } from '../api/types';
import { DEAD_LETTER_REASONS, shortId } from './labels';
import { ReplayButton } from './ReplayButton';
import { AttemptMeter, Pill, StatusBadge } from './StatusBadge';
import { Time } from './Time';

type Column = 'event' | 'endpoint' | 'type' | 'reason' | 'replay';

/** The delivery table shared by the deliveries, dead-letter, endpoint and event views. */
export function DeliveriesTable({
  rows,
  hide = [],
  show = [],
  hasMore,
  loadingMore,
  onLoadMore,
}: {
  rows: DeliverySummary[];
  hide?: Column[];
  show?: Column[];
  hasMore?: boolean;
  loadingMore?: boolean;
  onLoadMore?: () => void;
}) {
  const visible = (c: Column) => !hide.includes(c) && (c === 'reason' || c === 'replay' ? show.includes(c) : true);
  const cell = (c: Column, node: ReactNode, className?: string) => (visible(c) ? <Table.Td className={className}>{node}</Table.Td> : null);
  return (
    <>
      <div className="panel table-panel">
        <Table.ScrollContainer minWidth={900}>
          <Table className="data-table" highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Delivery</Table.Th>
                {visible('event') && <Table.Th>Event</Table.Th>}
                {visible('endpoint') && <Table.Th>Endpoint</Table.Th>}
                {visible('type') && <Table.Th>Type</Table.Th>}
                <Table.Th>Status</Table.Th>
                {visible('reason') && <Table.Th>Reason</Table.Th>}
                <Table.Th>Attempts</Table.Th>
                <Table.Th>Last HTTP</Table.Th>
                <Table.Th>Last attempt</Table.Th>
                <Table.Th>Next retry</Table.Th>
                {visible('replay') && <Table.Th />}
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {rows.map((d) => (
                <Table.Tr key={d.id}>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <Anchor component={Link} to={`/deliveries/${d.id}`} className="mono" size="sm">
                        {shortId(d.id)}
                      </Anchor>
                      {d.replayOfDeliveryId && (
                        <Pill tone="replay" dot={false}>
                          replay
                        </Pill>
                      )}
                    </Group>
                  </Table.Td>
                  {cell(
                    'event',
                    <Anchor component={Link} to={`/events/${d.eventId}`} className="mono" size="sm" c="dimmed">
                      {shortId(d.eventId)}
                    </Anchor>,
                  )}
                  {cell(
                    'endpoint',
                    <Anchor component={Link} to={`/endpoints/${d.endpointId}`} size="sm" c="inherit">
                      {d.endpointName}
                    </Anchor>,
                  )}
                  {cell('type', d.eventType, 'mono dim')}
                  <Table.Td>
                    <StatusBadge status={d.status} />
                  </Table.Td>
                  {cell('reason', d.deadLetterReason ? DEAD_LETTER_REASONS[d.deadLetterReason] : '—')}
                  <Table.Td>
                    <span className="attempts-cell">
                      <span className="mono">
                        {d.attemptCount}/{d.maxAttempts}
                      </span>
                      <AttemptMeter used={d.attemptCount} max={d.maxAttempts} status={d.status} />
                    </span>
                  </Table.Td>
                  <Table.Td className="mono">{d.lastResponseStatus ?? (d.lastError ? '—' : '')}</Table.Td>
                  <Table.Td className="mono dim">
                    <Time value={d.lastAttemptAt} format="compact" />
                  </Table.Td>
                  <Table.Td className="mono dim">
                    <Time value={d.status === 'RETRYING' ? d.nextAttemptAt : null} format="compact" />
                  </Table.Td>
                  {cell('replay', d.status === 'DEAD_LETTERED' ? <ReplayButton deliveryId={d.id} /> : null)}
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        </Table.ScrollContainer>
      </div>
      {hasMore && onLoadMore && (
        <Group justify="center">
          <Button variant="default" loading={loadingMore} onClick={onLoadMore}>
            Load more
          </Button>
        </Group>
      )}
      {rows.length > 0 && !hasMore && (
        <Text size="xs" c="dimmed" ta="center" className="mono">
          {rows.length} shown · refreshes every 5 s
        </Text>
      )}
    </>
  );
}
