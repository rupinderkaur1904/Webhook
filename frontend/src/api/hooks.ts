import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, ensureCsrfToken } from './client';
import type {
  ApiKey,
  ApiKeyCreated,
  DeliveryDetail,
  DeliveryFilter,
  DeliverySummary,
  Endpoint,
  EndpointCreated,
  EndpointHealth,
  EventAccepted,
  EventDetail,
  EventSummary,
  MetricsWindow,
  Overview,
  Page,
  SecretRotated,
  User,
} from './types';

/** Active views poll so the dashboard follows the worker without a reload. */
export const LIVE_REFRESH_MS = 5000;

export function useEndpoints() {
  return useQuery({
    queryKey: ['endpoints'],
    queryFn: () => api<{ items: Endpoint[] }>('/api/v1/endpoints').then((r) => r.items),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useEndpoint(id: string) {
  return useQuery({
    queryKey: ['endpoints', id],
    queryFn: () => api<Endpoint>(`/api/v1/endpoints/${id}`),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useEndpointHealth(id: string, window: MetricsWindow) {
  return useQuery({
    queryKey: ['endpoints', id, 'health', window],
    queryFn: () => api<EndpointHealth>(`/api/v1/endpoints/${id}/health?window=${window}`),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

/** Closes an open circuit by hand: queued deliveries resume on the next poll. */
export function useResetCircuit() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api<Endpoint>(`/api/v1/endpoints/${id}/circuit/reset`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['endpoints'] });
      client.invalidateQueries({ queryKey: ['overview'] });
    },
  });
}

export function useCreateEndpoint() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; url: string; description?: string; eventTypes?: string[] }) =>
      api<EndpointCreated>('/api/v1/endpoints', { method: 'POST', body: JSON.stringify(body) }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['endpoints'] }),
  });
}

export function useSetEndpointEnabled() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
      api<Endpoint>(`/api/v1/endpoints/${id}`, { method: 'PATCH', body: JSON.stringify({ enabled }) }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['endpoints'] });
      client.invalidateQueries({ queryKey: ['delivery'] });
    },
  });
}

export type EndpointChanges = { name: string; url: string; description: string; eventTypes: string[] };

/** Updates an endpoint's settings. An empty description clears it. */
export function useUpdateEndpoint() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, ...changes }: EndpointChanges & { id: string }) =>
      api<Endpoint>(`/api/v1/endpoints/${id}`, { method: 'PATCH', body: JSON.stringify(changes) }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['endpoints'] }),
  });
}

export function useSendTestEvent() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (endpointId: string) => api<EventAccepted>(`/api/v1/endpoints/${endpointId}/test`, { method: 'POST' }),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: ['deliveries'] });
      client.invalidateQueries({ queryKey: ['events'] });
    },
  });
}

export function deliveryQueryString(filter: DeliveryFilter, cursor: string | null) {
  const params = new URLSearchParams({ limit: '50' });
  filter.statuses?.forEach((s) => params.append('status', s));
  if (filter.endpointId) params.set('endpointId', filter.endpointId);
  if (filter.eventId) params.set('eventId', filter.eventId);
  if (filter.eventType) params.set('eventType', filter.eventType);
  if (filter.deadLetterReason) params.set('deadLetterReason', filter.deadLetterReason);
  if (cursor) params.set('cursor', cursor);
  return params.toString();
}

export function useDeliveries(filter: DeliveryFilter) {
  return useInfiniteQuery({
    queryKey: ['deliveries', filter],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => api<Page<DeliverySummary>>(`/api/v1/deliveries?${deliveryQueryString(filter, pageParam)}`),
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useDelivery(id: string) {
  return useQuery({
    queryKey: ['delivery', id],
    queryFn: () => api<DeliveryDetail>(`/api/v1/deliveries/${id}`),
    refetchInterval: (query) => {
      const data = query.state.data;
      // Keep polling while this delivery or any replay of it is still being worked on.
      const active = data?.chain.some((d) => ['PENDING', 'RETRYING', 'IN_FLIGHT'].includes(d.status));
      return data && !active ? false : LIVE_REFRESH_MS;
    },
  });
}

/** Replays a dead-lettered delivery. Rejections carry a problem with a machine-readable `reason`. */
export function useReplay() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (deliveryId: string) => api<DeliverySummary>(`/api/v1/deliveries/${deliveryId}/replay`, { method: 'POST' }),
    onSettled: () => {
      client.invalidateQueries({ queryKey: ['deliveries'] });
      client.invalidateQueries({ queryKey: ['delivery'] });
      client.invalidateQueries({ queryKey: ['events'] });
      client.invalidateQueries({ queryKey: ['overview'] });
    },
  });
}

export function useEvents(eventType: string | undefined) {
  return useInfiniteQuery({
    queryKey: ['events', eventType ?? null],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: '50' });
      if (eventType) params.set('eventType', eventType);
      if (pageParam) params.set('cursor', pageParam);
      return api<Page<EventSummary>>(`/api/v1/events?${params}`);
    },
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useEvent(id: string) {
  return useQuery({
    queryKey: ['events', 'detail', id],
    queryFn: () => api<EventDetail>(`/api/v1/events/${id}`),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useOverview(window: MetricsWindow) {
  return useQuery({
    queryKey: ['overview', window],
    queryFn: () => api<Overview>(`/api/v1/metrics/overview?window=${window}`),
    refetchInterval: LIVE_REFRESH_MS,
  });
}

/** The signed-in user; a 401 here means "show the login page". */
export function useMe() {
  return useQuery({
    queryKey: ['me'],
    queryFn: () => api<User>('/api/v1/auth/me'),
    retry: false,
    staleTime: Infinity,
  });
}

export function useLogin() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: async (body: { email: string; password: string }) => {
      await ensureCsrfToken();
      return api<User>('/api/v1/auth/login', { method: 'POST', body: JSON.stringify(body) });
    },
    onSuccess: (user) => {
      // Nothing cached for a previous user may leak into this session.
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' });
      client.setQueryData(['me'], user);
    },
  });
}

export function useLogout() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => api<void>('/api/v1/auth/logout', { method: 'POST' }),
    onSettled: () => {
      client.setQueryData(['me'], null);
      client.removeQueries({ predicate: (q) => q.queryKey[0] !== 'me' });
    },
  });
}

export function useApiKeys() {
  return useQuery({
    queryKey: ['api-keys'],
    queryFn: () => api<{ items: ApiKey[] }>('/api/v1/api-keys').then((r) => r.items),
  });
}

export function useCreateApiKey() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (name: string) => api<ApiKeyCreated>('/api/v1/api-keys', { method: 'POST', body: JSON.stringify({ name }) }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['api-keys'] }),
  });
}

export function useRevokeApiKey() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api<void>(`/api/v1/api-keys/${id}`, { method: 'DELETE' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['api-keys'] }),
  });
}

export function useRotateSecret() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ id, gracePeriodHours }: { id: string; gracePeriodHours: number }) =>
      api<SecretRotated>(`/api/v1/endpoints/${id}/rotate-secret`, {
        method: 'POST',
        body: JSON.stringify({ gracePeriodHours }),
      }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['endpoints'] }),
  });
}
