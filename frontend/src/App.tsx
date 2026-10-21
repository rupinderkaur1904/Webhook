import { AppShell, Button, Center, Group, Loader, NavLink, Text } from '@mantine/core';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { onUnauthorized } from './api/client';
import { useLogout, useMe } from './api/hooks';
import { ApiKeysPage } from './pages/ApiKeysPage';
import { DeadLettersPage } from './pages/DeadLettersPage';
import { DeliveriesPage } from './pages/DeliveriesPage';
import { DeliveryDetailPage } from './pages/DeliveryDetailPage';
import { EndpointDetailPage } from './pages/EndpointDetailPage';
import { EndpointsPage } from './pages/EndpointsPage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventsPage } from './pages/EventsPage';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';

const NAV = [
  { to: '/overview', label: 'Overview' },
  { to: '/endpoints', label: 'Endpoints' },
  { to: '/events', label: 'Events' },
  { to: '/deliveries', label: 'Deliveries' },
  { to: '/dead-letters', label: 'Dead letters' },
  { to: '/api-keys', label: 'API keys' },
];

export function App() {
  const me = useMe();
  const logout = useLogout();
  const queryClient = useQueryClient();
  const location = useLocation();

  // Any 401 (expired or revoked session) drops back to the login page.
  useEffect(() => onUnauthorized(() => queryClient.setQueryData(['me'], null)), [queryClient]);

  if (me.isLoading) {
    return (
      <Center h="100vh">
        <Loader aria-label="Loading" />
      </Center>
    );
  }
  if (!me.data) {
    return <LoginPage />;
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 200, breakpoint: 'sm' }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Text fw={600}>Webhook Platform</Text>
          <Group gap="xs">
            <Text size="sm" c="dimmed">
              {me.data.email}
            </Text>
            <Button variant="subtle" size="xs" loading={logout.isPending} onClick={() => logout.mutate()}>
              Sign out
            </Button>
          </Group>
        </Group>
      </AppShell.Header>
      <AppShell.Navbar p="xs">
        {NAV.map((item) => (
          <NavLink key={item.to} component={Link} to={item.to} label={item.label} active={location.pathname.startsWith(item.to)} />
        ))}
      </AppShell.Navbar>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<Navigate to="/overview" replace />} />
          <Route path="/overview" element={<OverviewPage />} />
          <Route path="/endpoints" element={<EndpointsPage />} />
          <Route path="/endpoints/:id" element={<EndpointDetailPage />} />
          <Route path="/events" element={<EventsPage />} />
          <Route path="/events/:id" element={<EventDetailPage />} />
          <Route path="/deliveries" element={<DeliveriesPage />} />
          <Route path="/deliveries/:id" element={<DeliveryDetailPage />} />
          <Route path="/dead-letters" element={<DeadLettersPage />} />
          <Route path="/api-keys" element={<ApiKeysPage />} />
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
