import { AppShell, Button, Group, NavLink, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { getApiKey, setApiKey } from './api/client';
import { ConnectPage } from './pages/ConnectPage';
import { DeadLettersPage } from './pages/DeadLettersPage';
import { DeliveriesPage } from './pages/DeliveriesPage';
import { DeliveryDetailPage } from './pages/DeliveryDetailPage';
import { EndpointDetailPage } from './pages/EndpointDetailPage';
import { EndpointsPage } from './pages/EndpointsPage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventsPage } from './pages/EventsPage';
import { OverviewPage } from './pages/OverviewPage';

const NAV = [
  { to: '/overview', label: 'Overview' },
  { to: '/endpoints', label: 'Endpoints' },
  { to: '/events', label: 'Events' },
  { to: '/deliveries', label: 'Deliveries' },
  { to: '/dead-letters', label: 'Dead letters' },
];

export function App() {
  const [connected, setConnected] = useState(() => getApiKey() !== null);
  const location = useLocation();

  if (!connected) {
    return <ConnectPage onConnected={() => setConnected(true)} />;
  }

  return (
    <AppShell header={{ height: 56 }} navbar={{ width: 200, breakpoint: 'sm' }} padding="md">
      <AppShell.Header>
        <Group h="100%" px="md" justify="space-between">
          <Text fw={600}>Webhook Platform</Text>
          <Button
            variant="subtle"
            size="xs"
            onClick={() => {
              setApiKey(null);
              setConnected(false);
            }}
          >
            Disconnect
          </Button>
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
          <Route path="*" element={<Navigate to="/overview" replace />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
