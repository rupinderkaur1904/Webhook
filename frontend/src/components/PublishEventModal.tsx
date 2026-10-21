import { Alert, Anchor, Button, Group, JsonInput, Modal, MultiSelect, SegmentedControl, Stack, Text, TextInput } from '@mantine/core';
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { errorMessage } from '../api/client';
import { useEndpoints, usePublishEvent } from '../api/hooks';
import { useSingleFlight } from '../api/useSingleFlight';
import type { EventAccepted } from '../api/types';
import { CheckIcon } from './Icons';
import { shortId } from './labels';

const EXAMPLE_PAYLOAD = '{\n  "orderId": "1001",\n  "amount": 4999\n}';

function newIdempotencyKey(): string {
  // crypto.randomUUID needs a secure context; a dashboard opened over plain HTTP on a LAN address is not one.
  const random = globalThis.crypto?.randomUUID?.() ?? `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  return `dashboard-${random}`;
}

/** The payload must be a JSON object (the server rejects arrays and scalars); returns the error to show, if any. */
export function payloadError(text: string): string | null {
  try {
    const value: unknown = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value)
      ? null
      : 'The payload must be a JSON object, e.g. { "orderId": "1001" }';
  } catch {
    return 'Not valid JSON';
  }
}

/**
 * Publishes an event from the dashboard through the same API producers use. Accepting an event only
 * stores it and queues one delivery per matching endpoint; the result says so and links to the deliveries.
 */
export function PublishEventModal({ opened, onClose }: { opened: boolean; onClose: () => void }) {
  const publish = usePublishEvent();
  const publishOnce = useSingleFlight(publish);
  const endpoints = useEndpoints();
  const [eventType, setEventType] = useState('');
  const [payload, setPayload] = useState(EXAMPLE_PAYLOAD);
  const [target, setTarget] = useState<'matching' | 'chosen'>('matching');
  const [endpointIds, setEndpointIds] = useState<string[]>([]);
  const [idempotencyKey, setIdempotencyKey] = useState(newIdempotencyKey);
  const [accepted, setAccepted] = useState<EventAccepted | null>(null);
  const [touched, setTouched] = useState(false);
  const jsonError = payloadError(payload);
  // Same rule as the server. A non-ASCII header value would make fetch throw before anything is sent.
  const keyError = /^[\x21-\x7e]{1,255}$/.test(idempotencyKey.trim()) ? null : 'Use 1-255 printable ASCII characters, no spaces';
  const names = new Map((endpoints.data ?? []).map((e) => [e.id, e.name]));

  // The form resets once the closing animation has finished.
  const reset = () => {
    setEventType('');
    setPayload(EXAMPLE_PAYLOAD);
    setTarget('matching');
    setEndpointIds([]);
    setIdempotencyKey(newIdempotencyKey());
    setAccepted(null);
    setTouched(false);
    publish.reset();
  };

  const submit = () => {
    setTouched(true);
    if (jsonError || keyError) return;
    publishOnce(
      {
        eventType: eventType.trim(),
        payload: JSON.parse(payload) as Record<string, unknown>,
        endpointIds: target === 'chosen' ? endpointIds : undefined,
        idempotencyKey: idempotencyKey.trim(),
      },
      { onSuccess: setAccepted },
    );
  };

  return (
    <Modal
      opened={opened}
      onClose={onClose}
      onExitTransitionEnd={reset}
      size={560}
      title={
        accepted ? (
          <span className="modal-title-icon">
            <span className="modal-icon-ok" aria-hidden="true">
              <CheckIcon size={16} />
            </span>
            Event accepted
          </span>
        ) : (
          'Publish event'
        )
      }
    >
      {accepted ? (
        <Stack>
          <Text size="sm" c="dimmed">
            Accepted means the event is stored and queued. Delivery happens in the background, with retries, so check the deliveries for the
            outcome.
          </Text>
          {accepted.deliveries.length === 0 ? (
            <Alert color="yellow" variant="light" title="No delivery was created">
              No enabled endpoint subscribes to <code>{accepted.eventType}</code>. Add the type to an endpoint&apos;s event types, or choose
              endpoints explicitly.
            </Alert>
          ) : (
            <div>
              <Text size="sm" fw={500} mb={6}>
                {accepted.deliveries.length} {accepted.deliveries.length === 1 ? 'delivery' : 'deliveries'} queued
              </Text>
              <ul className="publish-result">
                {accepted.deliveries.map((d) => (
                  <li key={d.id}>
                    <Anchor component={Link} to={`/deliveries/${d.id}`} className="mono" size="sm" onClick={onClose}>
                      {shortId(d.id)}
                    </Anchor>
                    <span>{names.get(d.endpointId) ?? shortId(d.endpointId)}</span>
                  </li>
                ))}
              </ul>
            </div>
          )}
          <Group justify="flex-end">
            <Button variant="default" onClick={reset}>
              Publish another
            </Button>
            <Button component={Link} to={`/events/${accepted.id}`} onClick={onClose}>
              View event
            </Button>
          </Group>
        </Stack>
      ) : (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            submit();
          }}
        >
          <Stack>
            <TextInput
              label="Event type"
              description="Lowercase, dot-separated, e.g. order.created. Endpoints subscribe to types or prefixes such as order.*."
              placeholder="order.created"
              value={eventType}
              onChange={(e) => setEventType(e.currentTarget.value)}
              required
              maxLength={100}
            />
            <JsonInput
              label="Payload"
              description="A JSON object. Receivers get it as the data field of the webhook body."
              value={payload}
              onChange={setPayload}
              error={touched || payload !== EXAMPLE_PAYLOAD ? jsonError : null}
              formatOnBlur
              autosize
              minRows={4}
              maxRows={14}
              required
            />
            <div>
              <Text size="sm" fw={500} mb={6}>
                Send to
              </Text>
              <SegmentedControl
                aria-label="Send to"
                value={target}
                onChange={(v) => setTarget(v as 'matching' | 'chosen')}
                data={[
                  { value: 'matching', label: 'Every matching endpoint' },
                  { value: 'chosen', label: 'Chosen endpoints' },
                ]}
              />
            </div>
            {target === 'chosen' && (
              <MultiSelect
                label="Endpoints"
                placeholder={endpoints.data?.length ? 'Pick endpoints' : 'No endpoints registered'}
                data={(endpoints.data ?? []).map((e) => ({ value: e.id, label: e.enabled ? e.name : `${e.name} (disabled)` }))}
                value={endpointIds}
                onChange={setEndpointIds}
                searchable
                required
              />
            )}
            <TextInput
              label="Idempotency key"
              description="Publishing again with the same key returns the original event instead of creating a duplicate."
              value={idempotencyKey}
              onChange={(e) => setIdempotencyKey(e.currentTarget.value)}
              error={idempotencyKey.trim() ? keyError : null}
              required
              maxLength={255}
              classNames={{ input: 'mono' }}
            />
            {publish.isError && (
              <Alert color="red" variant="light">
                {errorMessage(publish.error)}
              </Alert>
            )}
            <Button
              type="submit"
              loading={publish.isPending}
              disabled={!eventType.trim() || !idempotencyKey.trim() || (target === 'chosen' && endpointIds.length === 0)}
            >
              Publish event
            </Button>
          </Stack>
        </form>
      )}
    </Modal>
  );
}
