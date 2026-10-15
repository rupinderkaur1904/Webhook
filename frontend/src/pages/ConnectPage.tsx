import { Button, Paper, PasswordInput, Stack, Text, Title } from '@mantine/core';
import { useState } from 'react';
import { setApiKey } from '../api/client';

export function ConnectPage({ onConnected }: { onConnected: () => void }) {
  const [key, setKey] = useState('');
  return (
    <Paper maw={420} mx="auto" mt={80} p="xl" withBorder>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          setApiKey(key.trim());
          onConnected();
        }}
      >
        <Stack>
          <Title order={3}>Webhook Platform</Title>
          <Text size="sm" c="dimmed">
            Enter an API key to open the dashboard. Locally, the bootstrap key is in
            backend/src/main/resources/application-local.yml.
          </Text>
          <PasswordInput label="API key" placeholder="whk_..." value={key} onChange={(e) => setKey(e.currentTarget.value)} required />
          <Button type="submit" disabled={!key.trim().startsWith('whk_')}>
            Connect
          </Button>
        </Stack>
      </form>
    </Paper>
  );
}
