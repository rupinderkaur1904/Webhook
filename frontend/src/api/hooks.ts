import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type {
  DeliveryDetail,
  DeliveryFilter,
  DeliverySummary,
  Endpoint,
  EndpointCreated,
  EventAccepted,
  EventDetail,
  EventSummary,
  MetricsWindow,
  Overview,
  Page,
} from './types';

/** Active views poll so the dashboard follows the worker without a reload. */
export const LIVE_REFRESH_MS = 5000;

export function useEndpoints() {
  return useQuery({
    queryKey: ['endpoints'],
    queryFn: () => api<{ items: Endpoint[] }>('/api/v1/endpoints').then((r) => r.items),
  });
}

export function useEndpoint(id: string) {
  return useQuery({
    queryKey: ['endpoints', id],
    queryFn: () => api<Endpoint>(`/api/v1/endpoints/${id}`),
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
