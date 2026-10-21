import { Title } from '@mantine/core';
import type { ReactNode } from 'react';

/** Page title with a one-line explanation and optional actions on the right. */
export function PageHeader({ title, description, actions }: { title: ReactNode; description?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="page-header">
      <div className="page-header-text">
        <Title order={2} className="page-title">
          {title}
        </Title>
        {description && <div className="page-description">{description}</div>}
      </div>
      {actions && <div className="page-header-actions">{actions}</div>}
    </div>
  );
}
