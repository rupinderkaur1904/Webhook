import { AppShell, Burger, Center, Group, Loader, UnstyledButton } from '@mantine/core';
import { useDisclosure } from '@mantine/hooks';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, type ReactNode } from 'react';
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom';
import { onUnauthorized } from './api/client';
import { useLogout, useMe } from './api/hooks';
import { Brand } from './components/Brand';
import { DeadLettersIcon, DeliveriesIcon, EndpointsIcon, EventsIcon, KeyIcon, OverviewIcon } from './components/Icons';
import { ApiKeysPage } from './pages/ApiKeysPage';
import { DeadLettersPage } from './pages/DeadLettersPage';
import { DeliveriesPage } from './pages/DeliveriesPage';
import { DeliveryDetailPage } from './pages/DeliveryDetailPage';
import { EndpointDetailPage } from './pages/EndpointDetailPage';
import { EndpointsPage } from './pages/EndpointsPage';
import { EventDetailPage } from './pages/EventDetailPage';
import { EventsPage } from './pages/EventsPage';
import { LandingPage } from './pages/LandingPage';
import { LoginPage } from './pages/LoginPage';
import { OverviewPage } from './pages/OverviewPage';

const NAV: { to: string; label: string; icon: ReactNode }[] = [
  { to: '/overview', label: 'Overview', icon: <OverviewIcon /> },
  { to: '/endpoints', label: 'Endpoints', icon: <EndpointsIcon /> },
  { to: '/events', label: 'Events', icon: <EventsIcon /> },
  { to: '/deliveries', label: 'Deliveries', icon: <DeliveriesIcon /> },
  { to: '/dead-letters', label: 'Dead letters', icon: <DeadLettersIcon /> },
  { to: '/api-keys', label: 'API keys', icon: <KeyIcon /> },
];

/** The public landing page at `/`; every other route is the signed-in dashboard. */
export function App() {
  const location = useLocation();
  if (location.pathname === '/') {
    return <LandingPage />;
  }
  return <Dashboard />;
}

function Dashboard() {
  const me = useMe();
  const logout = useLogout();
  const queryClient = useQueryClient();
  const location = useLocation();
  const [navOpened, nav] = useDisclosure(false);
  const closeNav = nav.close;

  // Any 401 (expired or revoked session) drops back to the login page.
  useEffect(() => onUnauthorized(() => queryClient.setQueryData(['me'], null)), [queryClient]);
  // Following a link on a phone closes the menu.
  useEffect(() => closeNav(), [location.pathname, closeNav]);

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
    <AppShell
      header={{ height: { base: 52, sm: 0 } }}
      navbar={{ width: 232, breakpoint: 'sm', collapsed: { mobile: !navOpened } }}
      padding={0}
      className="dashboard"
    >
      <AppShell.Header hiddenFrom="sm" className="dashboard-topbar">
        <Group h="100%" px="md" justify="space-between">
          <Brand />
          <Burger opened={navOpened} onClick={nav.toggle} size="sm" aria-label="Toggle navigation" />
        </Group>
      </AppShell.Header>
      <AppShell.Navbar className="sidebar">
        <Link to="/overview" className="sidebar-brand" aria-label="Relay overview">
          <Brand />
        </Link>
        <nav className="sidebar-nav" aria-label="Dashboard">
          {NAV.map((item) => {
            const active = location.pathname.startsWith(item.to);
            return (
              <Link
                key={item.to}
                to={item.to}
                className={`sidebar-link${active ? ' active' : ''}`}
                aria-current={active ? 'page' : undefined}
              >
                {item.icon}
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>
        <div className="sidebar-account">
          <div className="sidebar-account-text">
            <span className="sidebar-account-label">Signed in as</span>
            <span className="sidebar-account-email">{me.data.email}</span>
          </div>
          <UnstyledButton className="sidebar-signout" disabled={logout.isPending} onClick={() => logout.mutate()}>
            Sign out
          </UnstyledButton>
        </div>
      </AppShell.Navbar>
      <AppShell.Main className="dashboard-main">
        <div className="dashboard-content">
          <Routes>
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
        </div>
      </AppShell.Main>
    </AppShell>
  );
}
