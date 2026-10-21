import { useReducedMotion } from '@mantine/hooks';
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { Overview } from '../api/types';

const W = 1060;
const H = 200;

const COLORS = {
  navy: {
    top: '#1e2440',
    left: '#161b31',
    right: '#11152a',
    stroke: '#3b4a8a',
  },
  pending: {
    top: '#252a36',
    left: '#1c2029',
    right: '#171a22',
    stroke: '#8b93a5',
  },
  flight: {
    top: '#14324a',
    left: '#10283b',
    right: '#0c2030',
    stroke: '#7cc8ff',
  },
  green: {
    top: '#0f3324',
    left: '#0c281c',
    right: '#091f16',
    stroke: '#4ade95',
  },
  red: { top: '#3a1418', left: '#2c1013', right: '#220c0f', stroke: '#f87171' },
};

function Block({ x, y, w, h, c }: { x: number; y: number; w: number; h: number; c: (typeof COLORS)['navy'] }) {
  const half = w / 2;
  return (
    <g stroke={c.stroke} strokeWidth={1.2} strokeLinejoin="round">
      <path d={`M${x - w} ${y} L${x} ${y + half} L${x} ${y + half + h} L${x - w} ${y + h} Z`} fill={c.left} />
      <path d={`M${x + w} ${y} L${x} ${y + half} L${x} ${y + half + h} L${x + w} ${y + h} Z`} fill={c.right} />
      <path d={`M${x} ${y - half} L${x + w} ${y} L${x} ${y + half} L${x - w} ${y} Z`} fill={c.top} />
    </g>
  );
}

function Count({ x, y, value, label, to, tone }: { x: number; y: number; value: ReactNode; label: string; to: string; tone: string }) {
  return (
    <Link to={to} className={`lane-count lane-${tone}`} style={{ left: `${(x / W) * 100}%`, top: `${(y / H) * 100}%` }}>
      <span className="lane-count-value">{value}</span>
      <span className="lane-count-label">{label}</span>
    </Link>
  );
}

const MAIN = 'M90 92 L900 92';

/**
 * The delivery pipeline as a lane: submitted events flow through pending and in flight to delivered,
 * with the retry loop above and the dead-letter branch below. Current counts are live; the
 * submitted, delivered and dead-lettered counts cover the selected window.
 */
export function PipelineLane({ overview }: { overview: Overview }) {
  const reducedMotion = useReducedMotion();
  const o = overview;
  const w = o.window;
  return (
    <div className="lane-scroll">
      <div className="lane">
        <svg viewBox={`0 0 ${W} ${H}`} className="lane-svg" aria-hidden="true" focusable="false">
          <path d={MAIN} stroke="#38bdf8" strokeOpacity="0.2" strokeWidth={7} strokeLinecap="round" />
          <path d={MAIN} stroke="#38bdf8" strokeWidth={2} strokeLinecap="round" />
          <defs>
            <marker id="lane-arrow" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
              <path d="M0 0 L10 5 L0 10 Z" fill="#f5b942" />
            </marker>
          </defs>
          <path
            d="M600 60 C 560 10, 404 10, 366 54"
            fill="none"
            stroke="#f5b942"
            strokeWidth={1.6}
            strokeDasharray="5 5"
            markerEnd="url(#lane-arrow)"
          />
          <path d="M628 104 C 650 124, 668 136, 686 144" fill="none" stroke="#f87171" strokeWidth={1.6} strokeDasharray="5 5" />
          <Block x={90} y={78} w={30} h={16} c={COLORS.navy} />
          <Block x={360} y={78} w={36} h={22} c={COLORS.pending} />
          <Block x={600} y={78} w={36} h={22} c={COLORS.flight} />
          <Block x={900} y={78} w={34} h={20} c={COLORS.green} />
          <Block x={706} y={152} w={28} h={16} c={COLORS.red} />
          {[230, 480, 760].map((x) => (
            <g key={x}>
              <circle cx={x} cy={92} r={10} fill="#ffb547" opacity={0.16} />
              <circle cx={x} cy={92} r={3.5} fill="#ffb547" />
            </g>
          ))}
          {!reducedMotion && (
            <circle r={3.5} fill="#ffb547">
              <animateMotion path={MAIN} dur="2.8s" fill="freeze" />
            </circle>
          )}
        </svg>
        <Count x={90} y={128} value={o.eventsSubmitted.toLocaleString()} label={`events · ${w}`} to="/events" tone="events" />
        <Count x={360} y={128} value={o.current.pending} label="pending" to="/deliveries?status=PENDING" tone="pending" />
        <Count x={668} y={0} value={o.current.retrying} label="retrying" to="/deliveries?status=RETRYING" tone="retrying" />
        <Count x={600} y={128} value={o.current.inFlight} label="in flight" to="/deliveries?status=IN_FLIGHT" tone="in-flight" />
        <Count
          x={900}
          y={128}
          value={o.deliveriesSucceeded.toLocaleString()}
          label={`delivered · ${w}`}
          to="/deliveries?status=DELIVERED"
          tone="delivered"
        />
        <Count
          x={786}
          y={146}
          value={o.deliveriesDeadLettered.total}
          label={`dead-lettered · ${w}`}
          to="/dead-letters"
          tone="dead-letter"
        />
      </div>
    </div>
  );
}
