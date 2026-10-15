import { AppShell, Button, Group, NavLink, Text } from '@mantine/core';
import { useState } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { getApiKey, setApiKey } from './api/client';
import { ConnectPage } from './pages/ConnectPage';
import { DeliveriesPage } from './pages/DeliveriesPage';
import { DeliveryDetailPage } from './pages/DeliveryDetailPage';
import { EndpointsPage } from './pages/EndpointsPage';

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
        <NavLink component={Link} to="/endpoints" label="Endpoints" active={location.pathname.startsWith('/endpoints')} />
        <NavLink component={Link} to="/deliveries" label="Deliveries" active={location.pathname.startsWith('/deliveries')} />
      </AppShell.Navbar>
      <AppShell.Main>
        <Routes>
          <Route path="/" element={<Navigate to="/deliveries" replace />} />
          <Route path="/endpoints" element={<EndpointsPage />} />
          <Route path="/deliveries" element={<DeliveriesPage />} />
          <Route path="/deliveries/:id" element={<DeliveryDetailPage />} />
        </Routes>
      </AppShell.Main>
    </AppShell>
  );
}
