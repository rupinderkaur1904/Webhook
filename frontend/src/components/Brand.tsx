/** The Relay mark: a small isometric cube in the accent teal. */
export function BrandMark({ size = 20 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" aria-hidden="true" focusable="false">
      <path d="M10 1.5 18 6v8.6L10 19l-8-4.4V6z" fill="#14a593" />
      <path d="M10 1.5 18 6l-8 4.4L2 6z" fill="#5eead4" />
      <path d="M10 10.4V19l-8-4.4V6z" fill="#2dd4bf" />
    </svg>
  );
}

export function Brand({ size = 20, className }: { size?: number; className?: string }) {
  return (
    <span className={`brand ${className ?? ''}`}>
      <BrandMark size={size} />
      <span className="brand-name">Relay</span>
    </span>
  );
}
