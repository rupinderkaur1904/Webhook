import { Alert, Anchor, Button, CopyButton, Group, Modal, Stack, Switch, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useCreateEndpoint, useEndpoints, useSendTestEvent, useSetEndpointEnabled } from '../api/hooks';
import type { EndpointCreated } from '../api/types';
import { CheckIcon, ChevronRightIcon, LockIcon } from '../components/Icons';
import { percent } from '../components/labels';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, QueryState } from '../components/QueryState';
import { CircuitBadge, HealthBadge, Pill, Tag } from '../components/StatusBadge';

/** The one moment the signing secret is visible: shown once, then only its prefix. */
export function SecretShownOnce({ secret, prefix, children }: { secret: string; prefix: string; children?: ReactNode }) {
  return (
    <Stack gap="md">
      <Text size="sm" c="dimmed">
        <strong className="secret-callout">Copy the signing secret now.</strong> It is shown only once and cannot be retrieved later — your
        receiver uses it to verify webhook signatures.
      </Text>
      <div className="secret-box">
        <code>{secret}</code>
        <CopyButton value={secret}>
          {({ copied, copy }) => (
            <Button size="xs" variant="default" onClick={copy}>
              {copied ? 'Copied' : 'Copy'}
            </Button>
          )}
        </CopyButton>
      </div>
      <p className="secret-note">
        <LockIcon />
        Stored encrypted (AES-256-GCM). Only the prefix {prefix} is shown afterwards.
      </p>
      {children}
    </Stack>
  );
}

function CreateEndpointModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const create = useCreateEndpoint();
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [eventTypes, setEventTypes] = useState('');
  const [created, setCreated] = useState<EndpointCreated | null>(null);

  const close = () => {
    setName('');
    setUrl('');
    setEventTypes('');
    setCreated(null);
    create.reset();
    onClose();
  };

  return (
    <Modal
      opened={opened}
      onClose={close}
      size={500}
      title={
        created ? (
          <span className="modal-title-icon">
            <span className="modal-icon-ok" aria-hidden="true">
              <CheckIcon size={16} />
            </span>
            Endpoint registered
          </span>
        ) : (
          'Register endpoint'
        )
      }
    >
      {created ? (
        <SecretShownOnce secret={created.secret} prefix={created.endpoint.secretPrefix}>
          <Group justify="flex-end">
            <Button component={Link} to={`/endpoints/${created.endpoint.id}`} variant="default">
              Open endpoint
            </Button>
            <Button onClick={close}>Done</Button>
          </Group>
        </SecretShownOnce>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const types = eventTypes
              .split(',')
              .map((t) => t.trim())
              .filter(Boolean);
            create.mutate({ name, url, eventTypes: types }, { onSuccess: (r) => setCreated(r) });
          }}
        >
          <Stack>
            <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
            <TextInput
              label="URL"
              placeholder="https://example.com/webhooks"
              value={url}
              onChange={(e) => setUrl(e.currentTarget.value)}
              required
            />
            <TextInput
              label="Event types"
              description="Comma-separated, e.g. order.created, payment.*. Leave empty to receive all events."
              value={eventTypes}
              onChange={(e) => setEventTypes(e.currentTarget.value)}
            />
            {create.error && <Alert color="red">{errorMessage(create.error)}</Alert>}
            <Button type="submit" loading={create.isPending}>
              Register
            </Button>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

export function EndpointsPage() {
  const endpoints = useEndpoints();
  const sendTest = useSendTestEvent();
  const setEnabled = useSetEndpointEnabled();
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <PageHeader
        title="Endpoints"
        description="Where events are delivered. Each endpoint has its own signing secret and event-type filter."
        actions={<Button onClick={() => setCreating(true)}>Register endpoint</Button>}
      />
      <CreateEndpointModal opened={creating} onClose={() => setCreating(false)} />
      <QueryState isLoading={endpoints.isLoading} error={endpoints.error} onRetry={() => endpoints.refetch()}>
        {endpoints.data?.length === 0 ? (
          <div className="panel">
            <EmptyState title="No endpoints yet">Register an endpoint to start receiving webhooks.</EmptyState>
          </div>
        ) : (
          <ul className="endpoint-list">
            {endpoints.data?.map((endpoint) => (
              <li key={endpoint.id} className="panel endpoint-row">
                <div className="endpoint-main">
                  <div className="endpoint-name">
                    <Anchor component={Link} to={`/endpoints/${endpoint.id}`} c="inherit" fw={600}>
                      {endpoint.name}
                    </Anchor>
                    <Pill tone={endpoint.enabled ? 'delivered' : 'pending'}>{endpoint.enabled ? 'Enabled' : 'Disabled'}</Pill>
                  </div>
                  <p className="endpoint-url mono">{endpoint.url}</p>
                  <div className="endpoint-types">
                    {endpoint.eventTypes.length === 0 ? <Tag>all events</Tag> : endpoint.eventTypes.map((t) => <Tag key={t}>{t}</Tag>)}
                  </div>
                </div>
                <dl className="endpoint-stats">
                  <div>
                    <dt>Health · 24h</dt>
                    <dd>
                      <Group gap={6} wrap="nowrap">
                        <HealthBadge status={endpoint.health.status} />
                        {endpoint.circuit.state !== 'CLOSED' && <CircuitBadge state={endpoint.circuit.state} />}
                      </Group>
                      {endpoint.health.attempts > 0 && (
                        <span className="endpoint-rate mono">
                          {percent(endpoint.health.successRate)} of {endpoint.health.attempts} attempts
                        </span>
                      )}
                    </dd>
                  </div>
                  <div>
                    <dt>Dead letters</dt>
                    <dd className="mono">{endpoint.health.deadLetters}</dd>
                  </div>
                  <div>
                    <dt>Signing secret</dt>
                    <dd className="mono">{endpoint.secretPrefix}••••••••</dd>
                  </div>
                </dl>
                <div className="endpoint-actions">
                  <Switch
                    aria-label={`Enable ${endpoint.name}`}
                    checked={endpoint.enabled}
                    onChange={(e) => setEnabled.mutate({ id: endpoint.id, enabled: e.currentTarget.checked })}
                  />
                  <Button
                    size="xs"
                    variant="default"
                    disabled={!endpoint.enabled}
                    loading={sendTest.isPending && sendTest.variables === endpoint.id}
                    onClick={() =>
                      sendTest.mutate(endpoint.id, {
                        onSuccess: () => notifications.show({ color: 'green', message: `Test event queued for ${endpoint.name}` }),
                        onError: (e) => notifications.show({ color: 'red', message: errorMessage(e) }),
                      })
                    }
                  >
                    Send test event
                  </Button>
                  <Anchor component={Link} to={`/endpoints/${endpoint.id}`} c="dimmed" aria-label={`Open ${endpoint.name}`}>
                    <ChevronRightIcon size={18} />
                  </Anchor>
                </div>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </div>
  );
}
