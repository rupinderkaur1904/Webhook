import { Alert, Anchor, Button, Card, Code, CopyButton, Group, Modal, NumberInput, SimpleGrid, Stack, Switch, Text, Textarea, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { errorMessage } from '../api/client';
import {
  useDeleteEndpoint,
  useDeliveries,
  useEndpoint,
  useRotateSecret,
  useSendTestEvent,
  useSetEndpointEnabled,
  useUpdateEndpoint,
} from '../api/hooks';
import { useSingleFlight } from '../api/useSingleFlight';
import type { Endpoint, SecretRotated } from '../api/types';
import { DeliveriesTable } from '../components/DeliveriesTable';
import { EndpointHealthCard } from '../components/EndpointHealthCard';
import { Field } from '../components/Field';
import { EmptyState, QueryState } from '../components/QueryState';
import { Pill, Tag } from '../components/StatusBadge';
import { Time } from '../components/Time';

/** The form mounts each time the modal opens, so it always starts from the endpoint's current values. */
function EditEndpointForm({ endpoint, onDone }: { endpoint: Endpoint; onDone: () => void }) {
  const [name, setName] = useState(endpoint.name);
  const [url, setUrl] = useState(endpoint.url);
  const [description, setDescription] = useState(endpoint.description ?? '');
  const [eventTypes, setEventTypes] = useState(endpoint.eventTypes.join(', '));
  const update = useUpdateEndpoint();
  const updateOnce = useSingleFlight(update);
  return (
    <form
      onSubmit={(ev) => {
        ev.preventDefault();
        const types = eventTypes.split(',').map((t) => t.trim()).filter(Boolean);
        updateOnce(
          { id: endpoint.id, name: name.trim(), url: url.trim(), description: description.trim(), eventTypes: types },
          {
            onSuccess: () => {
              notifications.show({ color: 'green', message: 'Endpoint updated' });
              onDone();
            },
          },
        );
      }}
    >
      <Stack>
        <TextInput label="Name" value={name} onChange={(ev) => setName(ev.currentTarget.value)} required maxLength={100} />
        <TextInput
          label="URL"
          description="Queued and retrying deliveries go to the new URL. Dead letters are not retried; replay them after saving."
          value={url}
          onChange={(ev) => setUrl(ev.currentTarget.value)}
          required
          maxLength={2048}
        />
        <Textarea label="Description" value={description} onChange={(ev) => setDescription(ev.currentTarget.value)} maxLength={500} autosize minRows={2} />
        <TextInput
          label="Event types"
          description="Comma-separated, e.g. order.created, payment.*. Leave empty to receive all events."
          value={eventTypes}
          onChange={(ev) => setEventTypes(ev.currentTarget.value)}
        />
        {update.error && <Alert color="red">{errorMessage(update.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onDone}>
            Cancel
          </Button>
          <Button type="submit" loading={update.isPending}>
            Save changes
          </Button>
        </Group>
      </Stack>
    </form>
  );
}

function RotateSecretModal({ endpoint, opened, onClose }: { endpoint: Endpoint; opened: boolean; onClose: () => void }) {
  const [graceHours, setGraceHours] = useState<number | ''>(24);
  const [result, setResult] = useState<SecretRotated | null>(null);
  const rotate = useRotateSecret();
  const rotateOnce = useSingleFlight(rotate);
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
            onClick={() => graceHours !== '' && rotateOnce({ id: endpoint.id, gracePeriodHours: graceHours }, { onSuccess: setResult })}
          >
            Rotate secret
          </Button>
        </Stack>
      )}
    </Modal>
  );
}

/** Deleting is final: queued work is cancelled, the history stays readable but can no longer be replayed. */
function DeleteEndpointModal({ endpoint, opened, onClose }: { endpoint: Endpoint; opened: boolean; onClose: () => void }) {
  const remove = useDeleteEndpoint();
  const removeOnce = useSingleFlight(remove);
  const navigate = useNavigate();
  return (
    <Modal opened={opened} onClose={onClose} onExitTransitionEnd={() => remove.reset()} title={`Delete ${endpoint.name}?`}>
      <Stack>
        <Text size="sm">
          Pending and retrying deliveries to this endpoint are cancelled, and new events are no longer sent to it. Its deliveries and dead
          letters stay in the history, but they can&apos;t be replayed. This can&apos;t be undone.
        </Text>
        <Text size="sm" c="dimmed">
          To stop deliveries for a while instead, turn the endpoint off with the Enabled switch.
        </Text>
        {remove.isError && <Alert color="red">{errorMessage(remove.error)}</Alert>}
        <Group justify="flex-end">
          <Button variant="default" onClick={onClose}>
            Cancel
          </Button>
          <Button
            color="red"
            loading={remove.isPending}
            onClick={() =>
              removeOnce(endpoint.id, {
                onSuccess: () => {
                  notifications.show({ color: 'green', message: `Deleted ${endpoint.name}` });
                  navigate('/endpoints');
                },
              })
            }
          >
            Delete endpoint
          </Button>
        </Group>
      </Stack>
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
  const [editing, setEditing] = useState(false);
  const [deleting, setDeleting] = useState(false);
  const e = endpoint.data;
  const rows = deliveries.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <Stack className="page">
      <Anchor component={Link} to="/endpoints" size="sm" className="back-link">
        ← Endpoints
      </Anchor>
      <QueryState isLoading={endpoint.isLoading} error={endpoint.error} onRetry={() => endpoint.refetch()}>
        {e && (
          <>
            <Group justify="space-between">
              <Group>
                <Title order={2}>{e.name}</Title>
                <Pill tone={e.enabled ? 'delivered' : 'pending'}>{e.enabled ? 'Enabled' : 'Disabled'}</Pill>
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
                <Button variant="default" onClick={() => setEditing(true)}>
                  Edit
                </Button>
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
                <Button variant="subtle" color="red" onClick={() => setDeleting(true)}>
                  Delete
                </Button>
              </Group>
            </Group>
            <DeleteEndpointModal endpoint={e} opened={deleting} onClose={() => setDeleting(false)} />
            <RotateSecretModal endpoint={e} opened={rotating} onClose={() => setRotating(false)} />
            <Modal opened={editing} onClose={() => setEditing(false)} title="Edit endpoint">
              <EditEndpointForm endpoint={e} onDone={() => setEditing(false)} />
            </Modal>
            <Card withBorder>
              <SimpleGrid cols={{ base: 1, sm: 3 }}>
                <Field label="URL">
                  <Text size="sm" className="mono" style={{ wordBreak: 'break-all' }}>
                    {e.url}
                  </Text>
                </Field>
                <Field label="Event types">
                  {e.eventTypes.length === 0
                    ? 'all events'
                    : e.eventTypes.map((t) => (
                        <span key={t} className="tag-gap">
                          <Tag>{t}</Tag>
                        </span>
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
