import { Anchor, Badge, Button, Group, Table, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { DeliverySummary } from '../api/types';
import { DEAD_LETTER_REASONS, shortId } from './labels';
import { ReplayButton } from './ReplayButton';
import { StatusBadge } from './StatusBadge';
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
  const cell = (c: Column, node: ReactNode) => (visible(c) ? <Table.Td>{node}</Table.Td> : null);
  return (
    <>
      <Table.ScrollContainer minWidth={900}>
        <Table striped highlightOnHover>
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
                    <Anchor component={Link} to={`/deliveries/${d.id}`} ff="monospace" size="sm">
                      {shortId(d.id)}
                    </Anchor>
                    {d.replayOfDeliveryId && (
                      <Badge size="xs" variant="outline" color="grape">
                        replay
                      </Badge>
                    )}
                  </Group>
                </Table.Td>
                {cell(
                  'event',
                  <Anchor component={Link} to={`/events/${d.eventId}`} ff="monospace" size="sm">
                    {shortId(d.eventId)}
                  </Anchor>,
                )}
                {cell(
                  'endpoint',
                  <Anchor component={Link} to={`/endpoints/${d.endpointId}`} size="sm">
                    {d.endpointName}
                  </Anchor>,
                )}
                {cell('type', d.eventType)}
                <Table.Td>
                  <StatusBadge status={d.status} />
                </Table.Td>
                {cell('reason', d.deadLetterReason ? DEAD_LETTER_REASONS[d.deadLetterReason] : '—')}
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
                {cell('replay', d.status === 'DEAD_LETTERED' ? <ReplayButton deliveryId={d.id} /> : null)}
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      </Table.ScrollContainer>
      {hasMore && onLoadMore && (
        <Group justify="center">
          <Button variant="default" loading={loadingMore} onClick={onLoadMore}>
            Load more
          </Button>
        </Group>
      )}
      {rows.length > 0 && !hasMore && (
        <Text size="xs" c="dimmed" ta="center">
          {rows.length} shown · refreshes every 5 s
        </Text>
      )}
    </>
  );
}
