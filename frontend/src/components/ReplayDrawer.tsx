import { Alert, Button, Drawer, Group, Loader, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { errorMessage } from '../api/client';
import { useDelivery, useReplay } from '../api/hooks';
import { InfoIcon } from './Icons';
import { REPLAY_REASONS, shortId } from './labels';
import { Pill, StatusBadge } from './StatusBadge';

/**
 * Confirms a replay before sending it: what stays the same (the dead letter, the webhook-id) and
 * what is new (a delivery with a fresh retry budget, sent to the endpoint's current URL).
 */
export function ReplayDrawer({ deliveryId, onClose }: { deliveryId: string | null; onClose: () => void }) {
  return (
    <Drawer
      opened={deliveryId != null}
      onClose={onClose}
      position="right"
      size={420}
      title={deliveryId ? `Replay ${shortId(deliveryId)}?` : 'Replay'}
      classNames={{ title: 'drawer-title', content: 'replay-drawer' }}
    >
      {deliveryId && <ReplayDetails deliveryId={deliveryId} onClose={onClose} />}
    </Drawer>
  );
}

function ReplayDetails({ deliveryId, onClose }: { deliveryId: string; onClose: () => void }) {
  const detail = useDelivery(deliveryId);
  const replay = useReplay();
  const d = detail.data;

  if (detail.isLoading) {
    return <Loader size="sm" aria-label="Loading" />;
  }
  if (!d) {
    return <Alert color="red">{errorMessage(detail.error)}</Alert>;
  }
  const { delivery, replayEligibility: eligibility } = d;
  return (
    <Stack gap="lg">
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
      {!eligibility.eligible && eligibility.reason && (
        <Alert color="yellow" variant="light">
          {REPLAY_REASONS[eligibility.reason]}
        </Alert>
      )}
      <Group grow>
        <Button
          disabled={!eligibility.eligible}
          loading={replay.isPending}
          onClick={() =>
            replay.mutate(delivery.id, {
              onSuccess: () => {
                notifications.show({ color: 'green', message: 'Replay queued as a new delivery' });
                onClose();
              },
              onError: (e) => notifications.show({ color: 'red', title: 'Replay rejected', message: errorMessage(e) }),
            })
          }
        >
          Replay delivery
        </Button>
        <Button variant="default" onClick={onClose}>
          Cancel
        </Button>
      </Group>
    </Stack>
  );
}
