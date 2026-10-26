import { useEffect, useRef, useState } from 'react';

const SEEN_KEY = 'relay.intro.seen';
const LETTERS = 'Relay'.split('');
// Horizontal "routes" behind the logo; each carries a packet that travels across the screen.
const ROUTES = [
  { top: 18, delay: 0.2, duration: 5.5 },
  { top: 31, delay: 1.6, duration: 4.2 },
  { top: 44, delay: 0.9, duration: 6.4 },
  { top: 58, delay: 2.4, duration: 4.8 },
  { top: 71, delay: 0.5, duration: 5.9 },
  { top: 84, delay: 1.9, duration: 4.5 },
];
const EXIT_MS = 650;

/** Whether the intro should play: once per browser tab, and not when the visitor deep-links to a section. */
export function shouldShowIntro(): boolean {
  if (typeof window === 'undefined' || window.location.hash) {
    return false;
  }
  try {
    return window.sessionStorage.getItem(SEEN_KEY) !== '1';
  } catch {
    return true;
  }
}

function rememberSeen() {
  try {
    window.sessionStorage.setItem(SEEN_KEY, '1');
  } catch {
    // Storage can be blocked; the intro then simply plays again next time.
  }
}

/**
 * Full-screen animated "Relay" intro shown over the landing page. A click, tap or key press
 * anywhere fades it out and reveals the page underneath.
 */
export function IntroSplash({ onDone }: { onDone: () => void }) {
  const [leaving, setLeaving] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    ref.current?.focus();
    const previous = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = previous;
    };
  }, []);

  useEffect(() => {
    if (!leaving) {
      return;
    }
    const reduced = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
    const timer = window.setTimeout(onDone, reduced ? 0 : EXIT_MS);
    return () => window.clearTimeout(timer);
  }, [leaving, onDone]);

  const enter = () => {
    if (!leaving) {
      rememberSeen();
      setLeaving(true);
    }
  };

  return (
    <div
      ref={ref}
      className={`intro${leaving ? ' intro-leaving' : ''}`}
      role="button"
      tabIndex={0}
      aria-label="Enter Relay"
      onClick={enter}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ' || e.key === 'Escape') {
          e.preventDefault();
          enter();
        }
      }}
    >
      <div className="intro-routes" aria-hidden="true">
        {ROUTES.map((r) => (
          <span key={r.top} className="intro-route" style={{ top: `${r.top}%` }}>
            <span className="intro-packet" style={{ animationDelay: `${r.delay}s`, animationDuration: `${r.duration}s` }} />
          </span>
        ))}
      </div>
      <div className="intro-glow" aria-hidden="true" />

      <div className="intro-center">
        <svg className="intro-mark" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path className="intro-face intro-face-1" d="M10 1.5 18 6v8.6L10 19l-8-4.4V6z" fill="#14a593" />
          <path className="intro-face intro-face-2" d="M10 1.5 18 6l-8 4.4L2 6z" fill="#5eead4" />
          <path className="intro-face intro-face-3" d="M10 10.4V19l-8-4.4V6z" fill="#2dd4bf" />
        </svg>
        <p className="intro-word">
          {LETTERS.map((letter, i) => (
            <span key={i} className="intro-letter" style={{ animationDelay: `${0.7 + i * 0.09}s` }}>
              {letter}
            </span>
          ))}
        </p>
        <p className="intro-tagline">Every webhook, accounted for.</p>
      </div>

      <p className="intro-hint" aria-hidden="true">
        Click anywhere to enter
      </p>
    </div>
  );
}
