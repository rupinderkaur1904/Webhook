import { Alert, Button, PasswordInput, Stack, TextInput, Title } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useLogin } from '../api/hooks';
import { Brand } from '../components/Brand';

export function LoginPage() {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const login = useLogin();

  return (
    <div className="connect">
      <Link to="/" className="connect-back">
        ← Back to home
      </Link>
      <div className="connect-glow" aria-hidden="true" />
      <form
        className="connect-card"
        onSubmit={(e) => {
          e.preventDefault();
          login.mutate({ email: email.trim(), password });
        }}
      >
        <Stack gap="lg">
          <Brand size={26} className="connect-brand" />
          <div>
            <Title order={3} className="connect-title">
              Sign in to your workspace
            </Title>
            <p className="connect-text">Manage your endpoints, deliveries and replays.</p>
          </div>
          {login.isError && (
            <Alert color="red" variant="light" role="alert">
              {errorMessage(login.error)}
            </Alert>
          )}
          <Stack gap="sm">
            <TextInput
              label="Email"
              type="email"
              autoComplete="username"
              value={email}
              onChange={(e) => setEmail(e.currentTarget.value)}
              required
            />
            <PasswordInput
              label="Password"
              autoComplete="current-password"
              value={password}
              onChange={(e) => setPassword(e.currentTarget.value)}
              required
            />
          </Stack>
          <Button type="submit" size="md" fullWidth loading={login.isPending} disabled={!email.trim() || !password}>
            Sign in
          </Button>
        </Stack>
      </form>
    </div>
  );
}
