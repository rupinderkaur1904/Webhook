import { Alert, Button, Drawer, Group, Loader, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { errorMessage } from '../api/client';
import { useDelivery, useReplay } from '../api/hooks';
import { useSingleFlight } from '../api/useSingleFlight';
import { InfoIcon } from './Icons';
import { REPLAY_REASONS, shortId } from './labels';
import { Pill, StatusBadge } from './StatusBadge';
import { Time } from './Time';
import type { DeliverySummary } from '../api/types';

/**
 * Confirms a replay before sending it: what stays the same (the dead letter, the webhook-id) and
 * what is new (a delivery with a fresh retry budget, sent to the endpoint's current URL).
 */
export function ReplayDrawer({
  deliveryId,
  onClose,
  onReplayed,
}: {
  deliveryId: string | null;
  onClose: () => void;
  /** Called with the new delivery once the server has accepted the replay. */
  onReplayed?: (created: DeliverySummary) => void;
}) {
  return (
    <Drawer
      opened={deliveryId != null}
      onClose={onClose}
      position="right"
      size={420}
      title={deliveryId ? `Replay ${shortId(deliveryId)}?` : 'Replay'}
      classNames={{ title: 'drawer-title', content: 'replay-drawer' }}
    >
      {deliveryId && <ReplayDetails deliveryId={deliveryId} onClose={onClose} onReplayed={onReplayed} />}
    </Drawer>
  );
}

function ReplayDetails({
  deliveryId,
  onClose,
  onReplayed,
}: {
  deliveryId: string;
  onClose: () => void;
  onReplayed?: (created: DeliverySummary) => void;
}) {
  const detail = useDelivery(deliveryId);
  const replay = useReplay();
  const replayOnce = useSingleFlight(replay);
  const d = detail.data;

  if (detail.isLoading) {
    return <Loader size="sm" aria-label="Loading" />;
  }
  if (!d) {
    return <Alert color="red">{errorMessage(detail.error)}</Alert>;
  }
  const { delivery, replayEligibility: eligibility } = d;
  // Replaying is allowed after a replay succeeded, but it sends the event to the receiver once more.
  const delivered = d.chain.filter((c) => c.id !== delivery.id && c.status === 'DELIVERED').at(-1);
  const send = () =>
    replayOnce(delivery.id, {
      onSuccess: (created) => {
        notifications.show({ color: 'green', message: 'Replay queued as a new delivery' });
        onClose();
        onReplayed?.(created);
      },
      onError: (e) => notifications.show({ color: 'red', title: 'Replay rejected', message: errorMessage(e) }),
    });
  return (
    <Stack gap="lg">
      {!eligibility.eligible && eligibility.reason && (
        <Alert color="yellow" variant="light" title="This delivery can't be replayed now">
          {REPLAY_REASONS[eligibility.reason]}
        </Alert>
      )}
      {eligibility.eligible && delivered && (
        <Alert color="yellow" variant="light" title="Already delivered by a replay">
          Replay {shortId(delivered.id)} delivered this event (<Time value={delivered.deliveredAt} format="compact" />
          ). Replaying again sends it to the receiver once more.
        </Alert>
      )}
      <Text size="sm" c="dimmed">
        This sends the same event again as a new delivery. The dead letter and its {delivery.attemptCount}{' '}
        {delivery.attemptCount === 1 ? 'attempt stays' : 'attempts stay'} exactly as they are.
      </Text>
      <div className="replay-compare">
        <div>
          <div>
            <p className="mono">{shortId(delivery.id)}</p>
            <p className="dim">original · unchanged</p>
          </div>
          <StatusBadge status={delivery.status} />
        </div>
        <div>
          <div>
            <p>new delivery</p>
            <p className="dim">fresh budget of {delivery.maxAttempts} attempts</p>
          </div>
          <Pill tone="pending">Pending</Pill>
        </div>
      </div>
      <dl className="replay-facts">
        <dt>Sent to</dt>
        <dd>
          <span className="mono">{d.endpointUrl}</span>
          <span className="dim">current endpoint URL</span>
        </dd>
        <dt>webhook-id</dt>
        <dd>
          <span className="mono">{delivery.eventId}</span>
          <span className="dim">same as the original</span>
        </dd>
        <dt>Signed with</dt>
        <dd>current signing secret</dd>
        <dt>Replays used</dt>
        <dd className="mono">
          {eligibility.replayCount} of {eligibility.maxReplays}
        </dd>
      </dl>
      <p className="hint-box">
        <InfoIcon size={16} />
        Receivers that already processed this event can de-duplicate on webhook-id.
      </p>
      <Group grow>
        <Button disabled={!eligibility.eligible} loading={replay.isPending} onClick={send}>
          Replay delivery
        </Button>
        <Button variant="default" onClick={onClose}>
          Cancel
        </Button>
      </Group>
    </Stack>
  );
}
