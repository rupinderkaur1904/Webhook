import { Alert, Anchor, Badge, Button, Code, CopyButton, Group, Modal, Stack, Switch, Table, Text, TextInput, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useCreateEndpoint, useEndpoints, useSendTestEvent, useSetEndpointEnabled } from '../api/hooks';
import { EmptyState, QueryState } from '../components/QueryState';
import { CircuitBadge, HealthBadge } from '../components/StatusBadge';
import { percent } from '../components/labels';

function CreateEndpointModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const create = useCreateEndpoint();
  const [name, setName] = useState('');
  const [url, setUrl] = useState('');
  const [eventTypes, setEventTypes] = useState('');
  const [secret, setSecret] = useState<string | null>(null);

  const close = () => {
    setName('');
    setUrl('');
    setEventTypes('');
    setSecret(null);
    create.reset();
    onClose();
  };

  return (
    <Modal opened={opened} onClose={close} title={secret ? 'Endpoint created' : 'Register endpoint'}>
      {secret ? (
        <Stack>
          <Alert color="yellow" title="Copy the signing secret now">
            It is shown only once. Your receiver uses it to verify webhook signatures.
          </Alert>
          <Code block>{secret}</Code>
          <CopyButton value={secret}>{({ copied, copy }) => <Button onClick={copy}>{copied ? 'Copied' : 'Copy secret'}</Button>}</CopyButton>
          <Button variant="default" onClick={close}>
            Done
          </Button>
        </Stack>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            const types = eventTypes.split(',').map((t) => t.trim()).filter(Boolean);
            create.mutate({ name, url, eventTypes: types }, { onSuccess: (r) => setSecret(r.secret) });
          }}
        >
          <Stack>
            <TextInput label="Name" value={name} onChange={(e) => setName(e.currentTarget.value)} required />
            <TextInput label="URL" placeholder="https://example.com/webhooks" value={url} onChange={(e) => setUrl(e.currentTarget.value)} required />
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
    <Stack>
      <Group justify="space-between">
        <Title order={2}>Endpoints</Title>
        <Button onClick={() => setCreating(true)}>Register endpoint</Button>
      </Group>
      <CreateEndpointModal opened={creating} onClose={() => setCreating(false)} />
      <QueryState isLoading={endpoints.isLoading} error={endpoints.error} onRetry={() => endpoints.refetch()}>
        {endpoints.data?.length === 0 ? (
          <EmptyState title="No endpoints yet">Register an endpoint to start receiving webhooks.</EmptyState>
        ) : (
          <Table striped highlightOnHover>
            <Table.Thead>
              <Table.Tr>
                <Table.Th>Name</Table.Th>
                <Table.Th>URL</Table.Th>
                <Table.Th>Health (24h)</Table.Th>
                <Table.Th>Event types</Table.Th>
                <Table.Th>Secret</Table.Th>
                <Table.Th>Enabled</Table.Th>
                <Table.Th />
              </Table.Tr>
            </Table.Thead>
            <Table.Tbody>
              {endpoints.data?.map((endpoint) => (
                <Table.Tr key={endpoint.id}>
                  <Table.Td>
                    <Anchor component={Link} to={`/endpoints/${endpoint.id}`}>
                      {endpoint.name}
                    </Anchor>
                  </Table.Td>
                  <Table.Td>
                    <Text size="sm" style={{ wordBreak: 'break-all' }}>
                      {endpoint.url}
                    </Text>
                  </Table.Td>
                  <Table.Td>
                    <Group gap={6} wrap="nowrap">
                      <HealthBadge status={endpoint.health.status} />
                      {endpoint.circuit.state !== 'CLOSED' && <CircuitBadge state={endpoint.circuit.state} />}
                    </Group>
                    {endpoint.health.attempts > 0 && (
                      <Text size="xs" c="dimmed">
                        {percent(endpoint.health.successRate)} of {endpoint.health.attempts} attempts
                      </Text>
                    )}
                  </Table.Td>
                  <Table.Td>
                    {endpoint.eventTypes.length === 0 ? (
                      <Text size="sm" c="dimmed">
                        all
                      </Text>
                    ) : (
                      endpoint.eventTypes.map((t) => (
                        <Badge key={t} variant="outline" mr={4}>
                          {t}
                        </Badge>
                      ))
                    )}
                  </Table.Td>
                  <Table.Td>
                    <Code>{endpoint.secretPrefix}…</Code>
                  </Table.Td>
                  <Table.Td>
                    <Switch
                      aria-label={`Enable ${endpoint.name}`}
                      checked={endpoint.enabled}
                      onChange={(e) => setEnabled.mutate({ id: endpoint.id, enabled: e.currentTarget.checked })}
                    />
                  </Table.Td>
                  <Table.Td>
                    <Button
                      size="xs"
                      variant="light"
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
                  </Table.Td>
                </Table.Tr>
              ))}
            </Table.Tbody>
          </Table>
        )}
      </QueryState>
    </Stack>
  );
}
