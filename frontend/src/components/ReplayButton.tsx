import { Button, Tooltip } from '@mantine/core';
import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import type { ReplayEligibility } from '../api/types';
import { REPLAY_REASONS } from './labels';
import { ReplayDrawer } from './ReplayDrawer';

/**
 * Opens the replay confirmation panel for a dead-lettered delivery, so every Replay button in the
 * dashboard asks before sending. When eligibility is known the button is disabled with the reason;
 * otherwise the panel shows it.
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
  const [confirming, setConfirming] = useState(false);
  const navigate = useNavigate();
  const blocked = eligibility && !eligibility.eligible;
  const button = (
    <Button size={size} variant="light" disabled={blocked} onClick={() => setConfirming(true)}>
      Replay
    </Button>
  );
  return (
    <>
      {blocked && eligibility.reason ? (
        <Tooltip label={REPLAY_REASONS[eligibility.reason]}>
          <span>{button}</span>
        </Tooltip>
      ) : (
        button
      )}
      <ReplayDrawer
        deliveryId={confirming ? deliveryId : null}
        onClose={() => setConfirming(false)}
        onReplayed={(created) => openAfter && navigate(`/deliveries/${created.id}`)}
      />
    </>
  );
}
