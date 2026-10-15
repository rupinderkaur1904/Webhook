import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';
import type { DeliveryDetail, DeliveryStatus, DeliverySummary, Endpoint, EndpointCreated, EventAccepted, Page } from './types';

const LIVE_REFRESH_MS = 5000;

export function useEndpoints() {
  return useQuery({
    queryKey: ['endpoints'],
    queryFn: () => api<{ items: Endpoint[] }>('/api/v1/endpoints').then((r) => r.items),
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
    onSuccess: () => client.invalidateQueries({ queryKey: ['endpoints'] }),
  });
}

export function useSendTestEvent() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (endpointId: string) =>
      api<EventAccepted>(`/api/v1/endpoints/${endpointId}/test`, { method: 'POST' }),
    onSuccess: () => client.invalidateQueries({ queryKey: ['deliveries'] }),
  });
}

export function useDeliveries(statuses: DeliveryStatus[]) {
  return useInfiniteQuery({
    queryKey: ['deliveries', statuses],
    initialPageParam: null as string | null,
    queryFn: ({ pageParam }) => {
      const params = new URLSearchParams({ limit: '50' });
      statuses.forEach((s) => params.append('status', s));
      if (pageParam) params.set('cursor', pageParam);
      return api<Page<DeliverySummary>>(`/api/v1/deliveries?${params}`);
    },
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: LIVE_REFRESH_MS,
  });
}

export function useDelivery(id: string) {
  return useQuery({
    queryKey: ['delivery', id],
    queryFn: () => api<DeliveryDetail>(`/api/v1/deliveries/${id}`),
    refetchInterval: (query) => {
      const status = query.state.data?.delivery.status;
      return status === 'DELIVERED' || status === 'DEAD_LETTERED' || status === 'CANCELLED' ? false : LIVE_REFRESH_MS;
    },
  });
}
