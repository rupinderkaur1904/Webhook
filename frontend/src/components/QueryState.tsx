import { Alert, Button, Center, Loader, Stack, Text } from '@mantine/core';
import type { ReactNode } from 'react';
import { ApiError } from '../api/client';

/** Shared loading and error rendering so every data view handles both states the same way. */
export function QueryState({
  isLoading,
  error,
  onRetry,
  children,
}: {
  isLoading: boolean;
  error: unknown;
  onRetry?: () => void;
  children: ReactNode;
}) {
  if (isLoading) {
    return (
      <Center py="xl" role="status" aria-label="Loading">
        <Loader />
      </Center>
    );
  }
  if (error) {
    const message = error instanceof ApiError ? error.problem.detail : 'Something went wrong';
    return (
      <Alert color="red" title="Could not load data">
        <Stack gap="xs" align="flex-start">
          <Text size="sm">{message}</Text>
          {onRetry && (
            <Button size="xs" variant="light" color="red" onClick={onRetry}>
              Try again
            </Button>
          )}
        </Stack>
      </Alert>
    );
  }
  return <>{children}</>;
}

export function EmptyState({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <Stack align="center" py="xl" gap="xs">
      <Text fw={500}>{title}</Text>
      {children && (
        <Text c="dimmed" size="sm" ta="center">
          {children}
        </Text>
      )}
    </Stack>
  );
}
