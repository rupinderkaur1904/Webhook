import { Alert, Button, Paper, PasswordInput, Stack, Text, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { useLogin } from '../api/hooks';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const login = useLogin();

  return (
    <Paper maw={420} mx="auto" mt={80} p="xl" withBorder>
      <form
        onSubmit={(e) => {
          e.preventDefault();
          login.mutate({ email: email.trim(), password });
        }}
      >
        <Stack>
          <Title order={3}>Webhook Platform</Title>
          <Text size="sm" c="dimmed">
            Sign in to the dashboard. Locally, the bootstrap account is in backend/src/main/resources/application-local.yml.
          </Text>
          {login.isError && (
            <Alert color="red" role="alert">
              {errorMessage(login.error)}
            </Alert>
          )}
          <TextInput label="Email" type="email" autoComplete="username" value={email} onChange={(e) => setEmail(e.currentTarget.value)} required />
          <PasswordInput
            label="Password"
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.currentTarget.value)}
            required
          />
          <Button type="submit" loading={login.isPending} disabled={!email.trim() || !password}>
            Sign in
          </Button>
        </Stack>
      </form>
    </Paper>
  );
}
