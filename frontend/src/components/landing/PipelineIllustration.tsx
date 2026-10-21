import { useReducedMotion } from '@mantine/hooks';

type CubeTone = { top: string; left: string; right: string; stroke: string };

const TONES: Record<'navy' | 'teal' | 'red' | 'green', CubeTone> = {
  navy: { top: '#1e2440', left: '#161b31', right: '#11152a', stroke: '#3b4a8a' },
  teal: { top: '#123a3a', left: '#0f2c2e', right: '#0b2224', stroke: '#2dd4bf' },
  red: { top: '#3a1418', left: '#2c1013', right: '#220c0f', stroke: '#f87171' },
  green: { top: '#0f3324', left: '#0c281c', right: '#091f16', stroke: '#4ade95' },
};

/** An isometric block: `x, y` is the centre of its top face, `w` its half-width and `h` its depth. */
function Cube({ x, y, w, h, tone, dot }: { x: number; y: number; w: number; h: number; tone: CubeTone; dot?: string }) {
  const half = w / 2;
  return (
    <g stroke={tone.stroke} strokeWidth={1.2} strokeLinejoin="round">
      <path d={`M${x - w} ${y} L${x} ${y + half} L${x} ${y + half + h} L${x - w} ${y + h} Z`} fill={tone.left} />
      <path d={`M${x + w} ${y} L${x} ${y + half} L${x} ${y + half + h} L${x + w} ${y + h} Z`} fill={tone.right} />
      <path d={`M${x} ${y - half} L${x + w} ${y} L${x} ${y + half} L${x - w} ${y} Z`} fill={tone.top} />
      {dot && <circle cx={x} cy={y} r={4} fill={dot} stroke="none" />}
    </g>
  );
}

function Label({ x, y, title, detail, color = '#f3f4f7' }: { x: number; y: number; title: string; detail: string; color?: string }) {
  return (
    <g>
      <text x={x} y={y} className="art-label" fill={color}>
        {title}
      </text>
      <text x={x} y={y + 18} className="art-detail">
        {detail}
      </text>
    </g>
  );
}

function Packet({ x, y }: { x: number; y: number }) {
  return (
    <g>
      <circle cx={x} cy={y} r={10} fill="#ffb547" opacity={0.18} />
      <circle cx={x} cy={y} r={4.5} fill="#ffb547" />
    </g>
  );
}

const LANE = 'M100 160 L520 370';

/**
 * The hero's picture of the delivery pipeline: app → Postgres queue → worker → endpoint, with the
 * retry loop, the dead-letter branch and replay. Illustration only, not live data.
 */
export function PipelineIllustration() {
  const reducedMotion = useReducedMotion();
  return (
    <svg className="hero-art-svg" viewBox="0 0 700 540" role="img" aria-labelledby="pipeline-title">
      <title id="pipeline-title">
        Delivery pipeline: your app posts an event to the Postgres queue, a worker signs and sends it to your endpoint, failures are retried
        with backoff, exhausted deliveries become dead letters and can be replayed.
      </title>
      <defs>
        <pattern id="iso-grid" width="60" height="30" patternUnits="userSpaceOnUse">
          <path d="M0 15 L30 0 L60 15 L30 30 Z" fill="none" stroke="#a3aaba" strokeOpacity="0.09" />
        </pattern>
        <radialGradient id="grid-fade" cx="50%" cy="50%" r="55%">
          <stop offset="0%" stopColor="#fff" stopOpacity="1" />
          <stop offset="100%" stopColor="#fff" stopOpacity="0" />
        </radialGradient>
        <mask id="grid-mask">
          <rect width="700" height="540" fill="url(#grid-fade)" />
        </mask>
        <radialGradient id="teal-glow" cx="55%" cy="55%" r="45%">
          <stop offset="0%" stopColor="#2dd4bf" stopOpacity="0.14" />
          <stop offset="100%" stopColor="#2dd4bf" stopOpacity="0" />
        </radialGradient>
        <marker id="arrow-amber" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 Z" fill="#f5b942" />
        </marker>
        <marker id="arrow-violet" viewBox="0 0 10 10" refX="5" refY="5" markerWidth="9" markerHeight="9" orient="auto-start-reverse">
          <path d="M0 0 L10 5 L0 10 Z" fill="#c4a1ff" />
        </marker>
      </defs>

      <rect width="700" height="540" fill="url(#iso-grid)" mask="url(#grid-mask)" />
      <rect width="700" height="540" fill="url(#teal-glow)" />

      {/* The delivery lane */}
      <path d={LANE} stroke="#38bdf8" strokeOpacity="0.25" strokeWidth={8} strokeLinecap="round" />
      <path d={LANE} stroke="#38bdf8" strokeWidth={2.2} strokeLinecap="round" />

      {/* Retry loop: worker back to the queue */}
      <path
        d="M388 243 C 382 150, 296 116, 250 166"
        fill="none"
        stroke="#f5b942"
        strokeWidth={1.8}
        strokeDasharray="6 6"
        markerEnd="url(#arrow-amber)"
      />
      {/* Exhausted: worker to the dead-letter queue */}
      <path d="M372 322 L300 354" fill="none" stroke="#f87171" strokeWidth={1.6} strokeDasharray="5 5" />
      {/* Replay: dead letter back into the queue */}
      <path
        d="M222 368 C 176 346, 176 292, 198 252"
        fill="none"
        stroke="#c4a1ff"
        strokeWidth={1.6}
        strokeDasharray="3 5"
        markerEnd="url(#arrow-violet)"
      />

      <Cube x={93} y={130} w={41} h={22} tone={TONES.navy} />
      <Cube x={243} y={200} w={54} h={30} tone={TONES.navy} />
      <Cube x={394} y={270} w={54} h={30} tone={TONES.teal} />
      <Cube x={265} y={352} w={42} h={22} tone={TONES.red} dot="#f87171" />
      <Cube x={544} y={352} w={46} h={24} tone={TONES.green} dot="#4ade95" />

      <Packet x={170} y={195} />
      <Packet x={470} y={345} />
      <Packet x={321} y={134} />
      {!reducedMotion && (
        <g>
          <circle r={4.5} fill="#ffb547">
            <animateMotion path={LANE} dur="3.2s" begin="0.4s" fill="freeze" />
          </circle>
        </g>
      )}

      <Label x={124} y={92} title="Your app" detail="POST /api/v1/events" />
      <Label x={260} y={78} title="Retry" detail="backoff + jitter" color="#f5b942" />
      <Label x={32} y={228} title="Postgres queue" detail="one row per delivery" />
      <Label x={450} y={240} title="Worker" detail="signs · sends · records" />
      <Label x={26} y={332} title="Replay" detail="new delivery, same id" color="#c4a1ff" />
      <Label x={218} y={418} title="Dead letter" detail="kept with full history" color="#f87171" />
      <Label x={503} y={418} title="Your endpoint" detail="2xx → delivered" color="#4ade95" />
    </svg>
  );
}

/** The final call to action's stacked-slab picture: three rows of storage with a live dot on top. */
export function StackIllustration() {
  const slab = (y: number, top: string) => (
    <g stroke="#16171b" strokeWidth={1.4} strokeLinejoin="round">
      <path d={`M70 ${y + 55} L180 ${y + 110} L180 ${y + 140} L70 ${y + 85} Z`} fill="#e3e0d6" />
      <path d={`M290 ${y + 55} L180 ${y + 110} L180 ${y + 140} L290 ${y + 85} Z`} fill="#d4d0c3" />
      <path d={`M180 ${y} L290 ${y + 55} L180 ${y + 110} L70 ${y + 55} Z`} fill={top} />
    </g>
  );
  return (
    <svg viewBox="0 0 360 300" className="stack-art" aria-hidden="true" focusable="false">
      {slab(140, '#f6f5f1')}
      {slab(90, '#ffffff')}
      {slab(40, '#ffffff')}
      <circle cx="180" cy="95" r="14" fill="#e5482d" opacity="0.18" />
      <circle cx="180" cy="95" r="5" fill="#e5482d" />
    </svg>
  );
}
