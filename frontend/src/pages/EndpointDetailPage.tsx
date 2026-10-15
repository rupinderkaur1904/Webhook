import { Alert, Anchor, Badge, Button, Card, Code, CopyButton, Group, Modal, NumberInput, SimpleGrid, Stack, Switch, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useDeliveries, useEndpoint, useRotateSecret, useSendTestEvent, useSetEndpointEnabled } from '../api/hooks';
import type { Endpoint, SecretRotated } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { EndpointHealthCard } from '../components/EndpointHealthCard';
import { Field } from '../components/Field';
import { EmptyState, QueryState } from '../components/QueryState';
import { Time } from '../components/Time';

function RotateSecretModal({ endpoint, opened, onClose }: { endpoint: Endpoint; opened: boolean; onClose: () => void }) {
  const [graceHours, setGraceHours] = useState<number | ''>(24);
  const [result, setResult] = useState<SecretRotated | null>(null);
  const rotate = useRotateSecret();
  // The form resets once the closing animation has finished, so the shown-once value never flashes back to the form.
  const reset = () => {
    setResult(null);
    setGraceHours(24);
    rotate.reset();
  };

  return (
    <Modal opened={opened} onClose={onClose} onExitTransitionEnd={reset} title={result ? 'New signing secret' : 'Rotate signing secret'}>
      {result ? (
        <Stack>
          <Alert color="yellow" title="Copy the new secret now">
            This is the only time it is shown.{' '}
            {result.previousSecretExpiresAt
              ? `Until ${new Date(result.previousSecretExpiresAt).toLocaleString()} every delivery is signed with both the new and the old secret, so update your receiver before then.`
              : 'The old secret no longer signs deliveries.'}
          </Alert>
          <Code block>{result.secret}</Code>
          <Group>
            <CopyButton value={result.secret}>{({ copied, copy }) => <Button onClick={copy}>{copied ? 'Copied' : 'Copy secret'}</Button>}</CopyButton>
            <Button variant="default" onClick={onClose}>
              Done
            </Button>
          </Group>
        </Stack>
      ) : (
        <Stack>
          <Text size="sm">
            A new secret is generated for <b>{endpoint.name}</b>. During the grace period deliveries carry two signatures, so the
            receiver keeps verifying while you deploy the new secret.
          </Text>
          <NumberInput
            label="Grace period (hours)"
            description="0 drops the old secret immediately; at most 72."
            min={0}
            max={72}
            allowDecimal={false}
            value={graceHours}
            onChange={(v) => setGraceHours(typeof v === 'number' ? v : v.trim() === '' ? '' : Number.parseInt(v, 10) || 0)}
          />
          {rotate.isError && <Alert color="red">{errorMessage(rotate.error)}</Alert>}
          <Button
            color="orange"
            loading={rotate.isPending}
            disabled={graceHours === '' || graceHours < 0 || graceHours > 72}
            onClick={() => graceHours !== '' && rotate.mutate({ id: endpoint.id, gracePeriodHours: graceHours }, { onSuccess: setResult })}
          >
            Rotate secret
          </Button>
        </Stack>
      )}
    </Modal>
  );
}

export function EndpointDetailPage() {
  const { id = '' } = useParams();
  const endpoint = useEndpoint(id);
  const deliveries = useDeliveries({ endpointId: id });
  const setEnabled = useSetEndpointEnabled();
  const sendTest = useSendTestEvent();
  const [rotating, setRotating] = useState(false);
  const e = endpoint.data;
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack>
      <Anchor component={Link} to="/endpoints" size="sm">
        ← Endpoints
      </Anchor>
      <QueryState isLoading={endpoint.isLoading} error={endpoint.error} onRetry={() => endpoint.refetch()}>
        {e && (
          <>
            <Group justify="space-between">
              <Group>
                <Title order={2}>{e.name}</Title>
                <Badge color={e.enabled ? 'green' : 'gray'} variant="light">
                  {e.enabled ? 'enabled' : 'disabled'}
                </Badge>
              </Group>
              <Group>
                <Switch
                  label="Enabled"
                  checked={e.enabled}
                  onChange={(ev) =>
                    setEnabled.mutate(
                      { id: e.id, enabled: ev.currentTarget.checked },
                      { onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }) },
                    )
                  }
                />
                <Button
                  variant="light"
                  disabled={!e.enabled}
                  loading={sendTest.isPending}
                  onClick={() =>
                    sendTest.mutate(e.id, {
                      onSuccess: () => notifications.show({ color: 'green', message: 'Test event queued' }),
                      onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }),
                    })
                  }
                >
                  Send test event
                </Button>
              </Group>
            </Group>
            <RotateSecretModal endpoint={e} opened={rotating} onClose={() => setRotating(false)} />
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="URL">
                  <Text size="sm" style={{ wordBreak: 'break-all' }}>
                    {e.url}
                  </Text>
                </Field>
                <Field label="Event types">
                  {e.eventTypes.length === 0
                    ? 'all events'
                    : e.eventTypes.map((t) => (
                        <Badge key={t} variant="outline" mr={4}>
                          {t}
                        </Badge>
                      ))}
                </Field>
                <Field label="Signing secret">
                  <Group gap="xs">
                    <Code>{e.secretPrefix}…</Code>
                    <Button size="compact-xs" variant="subtle" onClick={() => setRotating(true)}>
                      Rotate
                    </Button>
                  </Group>
                  {e.previousSecretExpiresAt && (
                    <Text size="xs" c="dimmed">
                      Previous secret also signs until <Time value={e.previousSecretExpiresAt} />
                    </Text>
                  )}
                </Field>
                {e.description && <Field label="Description">{e.description}</Field>}
                <Field label="Created">
                  <Time value={e.createdAt} />
                </Field>
                <Field label="Updated">
                  <Time value={e.updatedAt} />
                </Field>
              </SimpleGrid>
            </Card>
            <EndpointHealthCard endpoint={e} />
            <Group justify="space-between">
              <Title order={4}>Deliveries to this endpoint</Title>
              <Anchor component={Link} to={`/dead-letters?endpointId=${e.id}`} size="sm">
                Dead letters for this endpoint →
              </Anchor>
            </Group>
            <QueryState isLoading={deliveries.isLoading} error={deliveries.error} onRetry={() => deliveries.refetch()}>
              {rows.length === 0 ? (
                <EmptyState title="No deliveries yet">Send a test event to try this endpoint.</EmptyState>
              ) : (
                <DeliveriesTable
                  rows={rows}
                  hide={['endpoint']}
                  show={['replay']}
                  hasMore={deliveries.hasNextPage}
                  loadingMore={deliveries.isFetchingNextPage}
                  onLoadMore={() => deliveries.fetchNextPage()}
                />
              )}
            </QueryState>
          </>
        )}
      </QueryState>
    </Stack>
  );
}
