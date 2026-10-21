import { Alert, Button, Card, Group, SegmentedControl, SimpleGrid, Stack, Text, Title } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useState } from 'react';
import { errorMessage } from '../api/client';
import { useEndpointHealth, useResetCircuit } from '../api/hooks';
import type { Endpoint, MetricsWindow } from '../api/types';
import { Field } from './Field';
import { percent } from './labels';
import { CircuitBadge, HealthBadge } from './StatusBadge';
import { Time } from './Time';

const WINDOWS: MetricsWindow[] = ['1h', '24h', '7d'];

function CircuitExplanation({ endpoint }: { endpoint: Endpoint }) {
  const c = endpoint.circuit;
  if (c.state === 'OPEN') {
    return (
      <>
        Deliveries to this endpoint are paused after {c.consecutiveFailures} consecutive failures. Queued deliveries keep
        their retry budget. One probe delivery is sent after <Time value={c.retryAt} />; if it succeeds, delivery resumes.
      </>
    );
  }
  if (c.state === 'HALF_OPEN') {
    return <>One probe delivery is being sent. Success closes the circuit and the backlog drains; a failure pauses again for longer.</>;
  }
  return c.consecutiveFailures > 0 ? (
    <>{c.consecutiveFailures} consecutive failed attempts. The circuit opens at the configured threshold.</>
  ) : (
    <>Circuit closed: deliveries to this endpoint are not paused. It only tracks failures that suggest the receiver is down; the health figures above count every failed attempt and dead letter.</>
  );
}

/** Health over a selectable window, plus the circuit breaker's state and a manual reset. */
export function EndpointHealthCard({ endpoint }: { endpoint: Endpoint }) {
  const [window, setWindow] = useState<MetricsWindow>('24h');
  const health = useEndpointHealth(endpoint.id, window);
  const reset = useResetCircuit();
  const h = health.data;

  return (
    <Card withBorder>
      <Stack gap="sm">
        <Group justify="space-between">
          <Group gap="xs">
            <Title order={4}>Health</Title>
            {h && <HealthBadge status={h.status} />}
            <CircuitBadge state={endpoint.circuit.state} />
          </Group>
          <SegmentedControl size="xs" data={WINDOWS.map((w) => ({ value: w, label: w }))} value={window} onChange={(v) => setWindow(v as MetricsWindow)} />
        </Group>
        {health.error && <Alert color="red">{errorMessage(health.error)}</Alert>}
        {h && (
          <SimpleGrid cols={{ base: 2, sm: 4 }}>
            <Field label="Attempt success rate">
              {percent(h.successRate)} ({h.attempts - h.failedAttempts}/{h.attempts})
            </Field>
            <Field label="Latency p50 / p95">
              {h.latencyP50Ms == null ? '—' : `${h.latencyP50Ms} ms / ${h.latencyP95Ms} ms`}
            </Field>
            <Field label="Dead letters">{h.deadLetters}</Field>
            <Field label="Last success">
              <Time value={h.lastSuccessAt} />
            </Field>
          </SimpleGrid>
        )}
        {h?.lastFailure && (
          <Text size="sm" c="dimmed">
            Last failure <Time value={h.lastFailure.at} />: {h.lastFailure.outcome ?? 'error'}
            {h.lastFailure.responseStatus != null && ` (HTTP ${h.lastFailure.responseStatus})`}
            {h.lastFailure.error && h.lastFailure.responseStatus == null && ` — ${h.lastFailure.error}`}
          </Text>
        )}
        <Alert
          color={endpoint.circuit.state === 'CLOSED' ? 'gray' : endpoint.circuit.state === 'OPEN' ? 'red' : 'orange'}
          variant={endpoint.circuit.state === 'CLOSED' ? 'default' : 'light'}
        >
          <Group justify="space-between" wrap="nowrap" align="flex-start">
            <Text size="sm">
              <CircuitExplanation endpoint={endpoint} />
            </Text>
            {endpoint.circuit.state !== 'CLOSED' && (
              <Button
                size="xs"
                variant="white"
                color="red"
                style={{ flexShrink: 0 }}
                loading={reset.isPending}
                onClick={() =>
                  reset.mutate(endpoint.id, {
                    onSuccess: () => notifications.show({ color: 'green', message: 'Circuit closed; queued deliveries resume' }),
                    onError: (err) => notifications.show({ color: 'red', message: errorMessage(err) }),
                  })
                }
              >
                Close circuit now
              </Button>
            )}
          </Group>
        </Alert>
      </Stack>
    </Card>
  );
}
