import { Button, Tooltip } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useReplay } from '../api/hooks';
import type { ReplayEligibility } from '../api/types';
import { REPLAY_REASONS } from './labels';

/**
 * Replays a dead-lettered delivery. When eligibility is known the button is disabled with the
 * reason; otherwise the server decides and a rejection is shown as a notification.
 */
export function ReplayButton({
  deliveryId,
  eligibility,
  openAfter = false,
  size = 'xs',
}: {
  deliveryId: string;
  eligibility?: ReplayEligibility;
  openAfter?: boolean;
  size?: 'xs' | 'sm';
}) {
  const replay = useReplay();
  const navigate = useNavigate();
  const blocked = eligibility && !eligibility.eligible;
  const button = (
    <Button
      size={size}
      variant="light"
      disabled={blocked}
      loading={replay.isPending}
      onClick={() =>
        replay.mutate(deliveryId, {
          onSuccess: (created) => {
            notifications.show({ color: 'green', message: 'Replay queued as a new delivery' });
            if (openAfter) navigate(`/deliveries/${created.id}`);
          },
          onError: (e) => notifications.show({ color: 'red', title: 'Replay rejected', message: errorMessage(e) }),
        })
      }
    >
      Replay
    </Button>
  );
  if (blocked && eligibility.reason) {
    return (
      <Tooltip label={REPLAY_REASONS[eligibility.reason]}>
        <span>{button}</span>
      </Tooltip>
    );
  }
  return button;
}
