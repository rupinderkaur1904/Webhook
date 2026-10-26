import { Alert, Button, Code, CopyButton, Group, Modal, Stack, Table, Text, TextInput } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { useApiKeys, useCreateApiKey, useRevokeApiKey } from '../api/hooks';
import { useSingleFlight } from '../api/useSingleFlight';
import type { ApiKey } from '../api/types';
import { PageHeader } from '../components/PageHeader';
import { EmptyState, QueryState } from '../components/QueryState';
import { Pill } from '../components/StatusBadge';
import { Time } from '../components/Time';

function CreateApiKeyModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const [name, setName] = useState('');
  const [key, setKey] = useState<string | null>(null);
  const create = useCreateApiKey();
  const createOnce = useSingleFlight(create);
  // The form resets once the closing animation has finished, so the shown-once value never flashes back to the form.
  const reset = () => {
    setName('');
    setKey(null);
    create.reset();
  };

  return (
    <Modal opened={opened} onClose={onClose} onExitTransitionEnd={reset} title={key ? 'API key created' : 'Create API key'}>
      {key ? (
        <Stack>
          <Alert color="yellow" title="Copy the key now">
            This is the only time the full key is shown. Store it in your producer's secret store.
          </Alert>
          <Code block>{key}</Code>
          <Group>
            <CopyButton value={key}>{({ copied, copy }) => <Button onClick={copy}>{copied ? 'Copied' : 'Copy key'}</Button>}</CopyButton>
            <Button variant="default" onClick={onClose}>
              Done
            </Button>
          </Group>
        </Stack>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            createOnce(name.trim(), { onSuccess: (r) => setKey(r.key) });
          }}
        >
          <Stack>
            <Text size="sm" c="dimmed">
              API keys let your services send events and read data. They cannot change endpoints or keys.
            </Text>
            <TextInput label="Name" placeholder="orders-service (production)" value={name} onChange={(e) => setName(e.currentTarget.value)} required maxLength={100} />
            {create.isError && <Alert color="red">{errorMessage(create.error)}</Alert>}
            <Button type="submit" loading={create.isPending} disabled={!name.trim()}>
              Create key
            </Button>
          </Stack>
        </form>
      )}
    </Modal>
  );
}

function RevokeButton({ apiKey }: { apiKey: ApiKey }) {
  const [confirming, setConfirming] = useState(false);
  const revoke = useRevokeApiKey();
  const revokeOnce = useSingleFlight(revoke);
  return (
    <>
      <Button size="xs" color="red" variant="light" onClick={() => setConfirming(true)}>
        Revoke
      </Button>
      <Modal opened={confirming} onClose={() => setConfirming(false)} title={`Revoke "${apiKey.name}"?`}>
        <Stack>
          <Text size="sm">Requests using this key will be rejected immediately. This cannot be undone.</Text>
          <Group justify="flex-end">
            <Button variant="default" onClick={() => setConfirming(false)}>
              Cancel
            </Button>
            <Button
              color="red"
              loading={revoke.isPending}
              onClick={() =>
                revokeOnce(apiKey.id, {
                  onSuccess: () => {
                    setConfirming(false);
                    notifications.show({ color: 'green', message: `Revoked ${apiKey.name}` });
                  },
                  onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }),
                })
              }
            >
              Revoke key
            </Button>
          </Group>
        </Stack>
      </Modal>
    </>
  );
}

export function ApiKeysPage() {
  const keys = useApiKeys();
  const [creating, setCreating] = useState(false);

  return (
    <div className="page">
      <PageHeader
        title="API keys"
        description="Keys your services use to send events. Each key is shown only once."
        actions={<Button onClick={() => setCreating(true)}>Create API key</Button>}
      />
      <CreateApiKeyModal opened={creating} onClose={() => setCreating(false)} />
      <QueryState isLoading={keys.isLoading} error={keys.error} onRetry={() => keys.refetch()}>
        {keys.data?.length === 0 ? (
          <div className="panel">
            <EmptyState title="No API keys">Create one for each system that submits events.</EmptyState>
          </div>
        ) : (
          <div className="panel table-panel">
            <Table className="data-table">
              <Table.Thead>
                <Table.Tr>
                  <Table.Th>Name</Table.Th>
                  <Table.Th>Key</Table.Th>
                  <Table.Th>Created</Table.Th>
                  <Table.Th>Last used</Table.Th>
                  <Table.Th>Status</Table.Th>
                  <Table.Th />
                </Table.Tr>
              </Table.Thead>
              <Table.Tbody>
                {keys.data?.map((k) => (
                  <Table.Tr key={k.id}>
                    <Table.Td>{k.name}</Table.Td>
                    <Table.Td>
                      <Code>{k.prefix}…</Code>
                    </Table.Td>
                    <Table.Td className="mono dim">
                      <Time value={k.createdAt} />
                    </Table.Td>
                    <Table.Td>{k.lastUsedAt ? <Time value={k.lastUsedAt} /> : <Text c="dimmed" size="sm">never</Text>}</Table.Td>
                    <Table.Td>
                      {k.revokedAt ? <Pill tone="pending">revoked</Pill> : <Pill tone="delivered">active</Pill>}
                    </Table.Td>
                    <Table.Td>{!k.revokedAt && <RevokeButton apiKey={k} />}</Table.Td>
                  </Table.Tr>
                ))}
              </Table.Tbody>
            </Table>
          </div>
        )}
      </QueryState>
    </div>
  );
}
