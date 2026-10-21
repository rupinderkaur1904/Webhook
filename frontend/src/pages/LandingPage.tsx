import { Link } from 'react-router-dom';
import { Brand, BrandMark } from '../components/Brand';
import { CheckIcon } from '../components/Icons';
import { PipelineIllustration, StackIllustration } from '../components/landing/PipelineIllustration';
import { Pill, type Tone } from '../components/StatusBadge';

const REPO = 'https://github.com/rupinderkaur1904/Webhook';

const FACTS = [
  { title: '202 after commit', body: 'Events are persisted with their deliveries in one transaction before the API responds.' },
  { title: '8 attempts, 30s → 1h', body: 'Exponential backoff with equal jitter. Retry-After raises the delay, never the budget.' },
  { title: 'Standard Webhooks signing', body: 'webhook-id, webhook-timestamp and an HMAC-SHA256 signature on every request.' },
  { title: 'Lease-fenced workers', body: 'A stalled worker cannot overwrite newer state. Expired leases are reaped and retried.' },
];

const STEPS: { title: string; code: string; body: string; tone?: 'retry' | 'dead' }[] = [
  {
    title: 'Ingest',
    code: 'POST /api/v1/events',
    body: 'Validated, de-duplicated by Idempotency-Key and committed with one delivery per endpoint before the API returns 202.',
  },
  {
    title: 'Match',
    code: 'eventTypes: ["order.*"]',
    body: 'Every enabled endpoint whose filter matches the event type gets its own delivery. Exact, prefix or all events.',
  },
  {
    title: 'Attempt',
    code: 'FOR UPDATE SKIP LOCKED',
    body: 'A worker claims due deliveries under a lease, signs the request and sends it with strict timeouts.',
  },
  {
    title: 'Retry or deliver',
    code: '2xx → DELIVERED',
    body: 'Timeouts, 408, 425, 429 and 5xx except 501 and 505 retry with backoff. Redirects, other 4xx, 501 and 505 are permanent and stop immediately.',
    tone: 'retry',
  },
  {
    title: 'Dead letter',
    code: 'RETRIES_EXHAUSTED',
    body: 'When the budget runs out the delivery stops, keeps every attempt and waits for you to replay it.',
    tone: 'dead',
  },
];

// Default schedule: base 30 s doubling, equal jitter, so each delay lies between half and all of it.
const DELAYS = [
  { after: 1, label: '30s', seconds: 30 },
  { after: 2, label: '1m', seconds: 60 },
  { after: 3, label: '2m', seconds: 120 },
  { after: 4, label: '4m', seconds: 240 },
  { after: 5, label: '8m', seconds: 480 },
  { after: 6, label: '16m', seconds: 960 },
  { after: 7, label: '32m', seconds: 1920 },
];

const DESTINATIONS: { url: string; why: string; verdict: string; tone: Tone }[] = [
  { url: 'https://hooks.acme.dev', why: '203.0.113.7 → public', verdict: 'Allowed', tone: 'delivered' },
  { url: 'https://127.0.0.1:8080', why: 'loopback', verdict: 'Blocked', tone: 'dead-letter' },
  { url: 'https://10.0.4.12/hook', why: 'private range', verdict: 'Blocked', tone: 'dead-letter' },
  { url: 'http://169.254.169.254', why: 'cloud metadata', verdict: 'Blocked', tone: 'dead-letter' },
  { url: 'https://[::ffff:7f00:1]', why: 'IPv6-embedded loopback', verdict: 'Blocked', tone: 'dead-letter' },
  { url: '301 → https://internal', why: 'redirect', verdict: 'Not followed', tone: 'retrying' },
];

const CHAIN: { label: string; id: string; summary: string; code: string; status: string; tone: Tone }[] = [
  {
    label: 'Original',
    id: 'dlv_7f3a9c21',
    summary: '8 of 8 attempts · last HTTP 503',
    code: 'RETRIES_EXHAUSTED',
    status: 'Dead letter',
    tone: 'dead-letter',
  },
  {
    label: 'Replay of 7f3a9c21',
    id: 'dlv_b20e44d7',
    summary: '1 of 8 attempts · HTTP 404',
    code: 'NON_RETRYABLE_RESPONSE',
    status: 'Dead letter',
    tone: 'dead-letter',
  },
  {
    label: 'Replay of b20e44d7',
    id: 'dlv_c91f0a3e',
    summary: '1 of 8 attempts · HTTP 200 · 186 ms',
    code: 'DELIVERED',
    status: 'Delivered',
    tone: 'delivered',
  },
];

const NOTES = [
  {
    title: 'At-least-once, on purpose',
    body: "A lost response or an expired lease can mean a second send. Exactly-once is not promised; de-duplication on webhook-id is the receiver's job, and the docs say so.",
  },
  {
    title: 'Postgres is the queue',
    body: 'Deliveries are rows claimed with FOR UPDATE SKIP LOCKED. Concurrent workers always receive disjoint work, and no broker is needed.',
  },
  {
    title: 'No transaction across HTTP',
    body: 'Claim, send and record are separate. A slow endpoint never holds a database transaction open.',
  },
  {
    title: 'Fenced by lease tokens',
    body: "Results are written only if the delivery still carries the worker's token. Late results are kept in history and flagged, never applied.",
  },
  {
    title: 'Crash recovery by reaping',
    body: 'Expired leases are found in batches, their open attempt is marked abandoned, and the delivery goes back to the normal retry path.',
  },
  {
    title: 'Budgets frozen per delivery',
    body: 'Retry settings are copied onto each delivery when it is created, so changing configuration never alters work already queued.',
  },
];

function SectionHeader({ kicker, title, children }: { kicker: string; title: string; children: string }) {
  return (
    <header className="lp-section-header">
      <p className="lp-kicker">{kicker}</p>
      <h2 className="lp-h2">{title}</h2>
      <p className="lp-sub">{children}</p>
    </header>
  );
}

function Bullets({ items }: { items: string[] }) {
  return (
    <ul className="lp-bullets">
      {items.map((item) => (
        <li key={item}>{item}</li>
      ))}
    </ul>
  );
}

function SiteNav() {
  return (
    <header className="lp-nav">
      <div className="lp-container lp-nav-inner">
        <Link to="/" className="lp-brand-link" aria-label="Relay home">
          <Brand size={22} />
        </Link>
        <nav className="lp-nav-links" aria-label="Sections">
          <a href="#how-it-works">How it works</a>
          <a href="#reliability">Reliability</a>
          <a href="#reliability-security">Security</a>
          <a href="#engineering-notes">Engineering notes</a>
        </nav>
        <div className="lp-nav-actions">
          <a href={REPO} className="lp-nav-github">
            GitHub
          </a>
          <Link to="/overview" className="lp-btn lp-btn-secondary">
            Open dashboard
          </Link>
        </div>
      </div>
    </header>
  );
}

function Hero() {
  return (
    <section className="lp-hero">
      <div className="lp-container lp-hero-grid">
        <div className="lp-hero-copy">
          <span className="lp-chip">
            <span className="lp-chip-dot" aria-hidden="true" />
            Webhook delivery &amp; reliability platform
          </span>
          <h1 className="lp-display">
            Every webhook,
            <br />
            <span className="lp-display-muted">accounted for.</span>
          </h1>
          <p className="lp-lede">
            Accept an event once and it is committed to Postgres. Every attempt is signed, retried with backoff, and recorded. Anything that
            still fails waits in a dead-letter queue until you fix the endpoint and replay it.
          </p>
          <div className="lp-ctas">
            <a href="#how-it-works" className="lp-btn lp-btn-primary">
              See how delivery works
            </a>
            <a href={REPO} className="lp-btn lp-btn-secondary">
              Read the source
            </a>
          </div>
          <ul className="lp-checks">
            {['At-least-once delivery', 'HMAC-SHA256 signed', 'Crash-safe worker leases'].map((item) => (
              <li key={item}>
                <CheckIcon size={13} />
                {item}
              </li>
            ))}
          </ul>
        </div>
        <div className="lp-hero-art">
          <PipelineIllustration />
          <div className="lp-float-card" aria-hidden="true">
            <div className="lp-float-head">
              <span className="mono">dlv_7f3a9c21</span>
              <Pill tone="retrying">Retrying</Pill>
            </div>
            <div className="lp-float-title">Attempt 3 of 8 · HTTP 503</div>
            <div className="lp-float-meter">
              {Array.from({ length: 8 }, (_, i) => (
                <span key={i} className={`meter-seg meter-${i < 2 ? 'dead-letter' : i === 2 ? 'retrying' : 'off'}`} />
              ))}
            </div>
            <div className="lp-float-foot">Next attempt in 2m 14s · Retry-After honoured</div>
          </div>
          <p className="lp-art-caption">Illustration of the delivery pipeline. Not live data.</p>
        </div>
      </div>
      <div className="lp-facts">
        <div className="lp-container lp-facts-grid">
          {FACTS.map((fact) => (
            <div key={fact.title} className="lp-fact">
              <p className="lp-fact-title">{fact.title}</p>
              <p className="lp-fact-body">{fact.body}</p>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}

function DashboardPreview() {
  const attempts = [
    {
      n: 1,
      tone: 'dead-letter',
      outcome: 'Failed',
      http: 'HTTP 500',
      ms: '1,204 ms',
      at: '14:00:02',
      note: 'Internal Server Error · retryable',
    },
    {
      n: 2,
      tone: 'dead-letter',
      outcome: 'Failed',
      http: 'Timeout',
      ms: '15,000 ms',
      at: '14:00:39',
      note: 'Response deadline exceeded · retryable',
    },
    {
      n: 3,
      tone: 'retrying',
      outcome: 'Failed',
      http: 'HTTP 503',
      ms: '412 ms',
      at: '14:01:51',
      note: 'Receiver asked to retry after 120 s (Retry-After)',
    },
    { n: 4, tone: 'pending', outcome: 'Scheduled', http: '', ms: '', at: '14:04:05', note: 'Waiting for its next_attempt_at' },
  ];
  return (
    <section className="lp-section lp-paper">
      <div className="lp-container">
        <SectionHeader kicker="01 — The dashboard" title="Open any delivery and see exactly what happened.">
          Every attempt keeps its status code, latency, error and Retry-After. The dashboard reads that history straight from Postgres, so
          what you see is what the worker recorded.
        </SectionHeader>
        <div className="lp-window" aria-label="Screenshot of a delivery in the dashboard" role="img">
          <div className="lp-window-bar">
            <span className="lp-window-dots" aria-hidden="true">
              <span />
              <span />
              <span />
            </span>
            <span className="lp-window-url">localhost:5173/deliveries/7f3a9c21</span>
          </div>
          <div className="lp-window-body">
            <aside className="lp-mock-nav">
              <Brand size={18} />
              <ul>
                <li>Overview</li>
                <li>Endpoints</li>
                <li>Events</li>
                <li className="active">Deliveries</li>
                <li>
                  Dead letters <span className="count-badge">2</span>
                </li>
              </ul>
            </aside>
            <div className="lp-mock-main">
              <p className="lp-mock-crumbs">Deliveries &nbsp;/&nbsp; 7f3a9c21</p>
              <div className="lp-mock-title">
                Delivery 7f3a9c21 <Pill tone="retrying">Retrying</Pill>
              </div>
              <div className="summary-strip">
                <div>
                  <span>Event type</span>
                  <strong className="mono">order.created</strong>
                </div>
                <div>
                  <span>Endpoint</span>
                  <strong className="accent">billing-hook</strong>
                </div>
                <div>
                  <span>Attempts</span>
                  <strong className="mono">3 of 8</strong>
                </div>
                <div>
                  <span>Next attempt</span>
                  <strong className="mono">in 2m 14s</strong>
                </div>
                <div>
                  <span>Event ID (webhook-id)</span>
                  <strong className="mono">evt_01J9Q4…c2e</strong>
                </div>
              </div>
              <div className="lp-mock-columns">
                <div>
                  <p className="lp-mock-h">Attempt history</p>
                  <ol className="timeline">
                    {attempts.map((a) => (
                      <li key={a.n} className={`timeline-item timeline-${a.tone}`}>
                        <div className="timeline-head">
                          <strong>Attempt {a.n}</strong>
                          <span className={`tag tag-${a.tone === 'pending' ? 'pending' : 'dead-letter'}`}>{a.outcome}</span>
                          {a.http && <span className="tag">{a.http}</span>}
                          {a.ms && <span className="timeline-ms">{a.ms}</span>}
                        </div>
                        <div className="timeline-meta">{a.at} &nbsp;·&nbsp; https://billing.example.com/hooks</div>
                        <div className="timeline-note">{a.note}</div>
                      </li>
                    ))}
                  </ol>
                </div>
                <div className="code-panel">
                  <div className="code-panel-head">
                    <span>Payload sent</span>
                    <span className="mono dim">application/json</span>
                  </div>
                  <pre className="code-json">{`{
  "id": "evt_01J9Q4…c2e",
  "type": "order.created",
  "createdAt": "2026-10-15T14:00:01Z",
  "data": {
    "orderId": "ord_8841",
    "amount": 4999,
    "currency": "INR"
  }
}`}</pre>
                  <div className="code-panel-sub">Signed headers</div>
                  <dl className="kv-mono">
                    <dt>webhook-id</dt>
                    <dd>evt_01J9Q4…c2e</dd>
                    <dt>webhook-timestamp</dt>
                    <dd>1760536801</dd>
                    <dt>webhook-signature</dt>
                    <dd>v1,K7mQ…Zc4=</dd>
                  </dl>
                </div>
              </div>
            </div>
          </div>
        </div>
        <p className="lp-footnote">
          Demo data in a real screen layout — delivery detail, attempt timeline and payload come from GET /api/v1/deliveries/{'{id}'}.
        </p>
      </div>
    </section>
  );
}

function HowItWorks() {
  return (
    <section className="lp-section lp-paper" id="how-it-works">
      <div className="lp-container">
        <SectionHeader kicker="02 — How delivery works" title="From one API call to a confirmed delivery.">
          Five steps, all of them persisted. A restart at any point loses nothing: due work is simply picked up again.
        </SectionHeader>
        <ol className="lp-steps">
          {STEPS.map((step, i) => (
            <li key={step.title} className={`lp-step${step.tone ? ` lp-step-${step.tone}` : ''}`}>
              <div className="lp-step-head">
                <span className="lp-step-num">{String(i + 1).padStart(2, '0')}</span>
                {i < STEPS.length - 1 && <span className="lp-step-rule" aria-hidden="true" />}
              </div>
              <h3>{step.title}</h3>
              <code className="lp-code-tag">{step.code}</code>
              <p>{step.body}</p>
            </li>
          ))}
        </ol>
      </div>
    </section>
  );
}

function Reliability() {
  const max = DELAYS[DELAYS.length - 1].seconds;
  return (
    <section className="lp-section lp-paper-white" id="reliability">
      <div className="lp-container">
        <SectionHeader kicker="03 — Reliability and security" title="Built for the endpoints that fail.">
          Most webhook code is written for the happy path. This platform is mostly about the other one.
        </SectionHeader>

        <div className="lp-feature">
          <div className="lp-feature-text">
            <h3 className="lp-h3">Retries that back off instead of piling on.</h3>
            <p>
              Each failed attempt is classified first. Transient failures wait longer each time, with jitter so a recovering endpoint is not
              hit by every retry at once. The schedule is stored on the delivery row, so a restart never forgets it.
            </p>
            <Bullets items={['408 · 425 · 429 · other 5xx → retry', '3xx · other 4xx · 501 · 505 → stop', 'next_attempt_at lives in Postgres']} />
          </div>
          <figure className="lp-chart">
            <figcaption className="lp-chart-head">
              <span>Delay before each retry</span>
              <span className="lp-legend">
                <span className="lp-legend-min">minimum</span>
                <span className="lp-legend-jitter">jitter range</span>
              </span>
            </figcaption>
            <div className="lp-bars">
              {DELAYS.map((d) => (
                <div key={d.after} className="lp-bar-row">
                  <span className="lp-bar-label">after {d.after}</span>
                  <span className="lp-bar-track">
                    <span className="lp-bar-min" style={{ width: `${(d.seconds / 2 / max) * 100}%` }} />
                    <span className="lp-bar-jitter" style={{ width: `${(d.seconds / 2 / max) * 100}%` }} />
                    <span className="lp-bar-value">{d.label}</span>
                  </span>
                </div>
              ))}
            </div>
            <p className="lp-chart-note">
              Defaults shown. On 429 and 503 a valid Retry-After raises the delay, capped at 6 h. It never adds attempts.
            </p>
          </figure>
        </div>

        <div className="lp-feature lp-feature-reverse" id="reliability-security">
          <div className="lp-feature-text">
            <h3 className="lp-h3">Signed so receivers can trust every request.</h3>
            <p>
              Requests follow the Standard Webhooks format. The signature covers the exact bytes sent, and the event id stays the same
              across retries and replays, so receivers can verify, reject stale timestamps and de-duplicate.
            </p>
            <Bullets items={['HMAC-SHA256, base64', '5-minute timestamp tolerance', 'secrets encrypted at rest (AES-256-GCM)']} />
          </div>
          <figure className="lp-request">
            <figcaption className="lp-request-head">
              <span>What your endpoint receives</span>
              <span className="mono dim">HTTP/1.1</span>
            </figcaption>
            <pre className="lp-request-body">
              <span>POST /hooks HTTP/1.1</span>
              {'\n'}
              <span className="h-key">Content-Type</span>: application/json{'\n'}
              <span className="h-key">webhook-id</span>: <span className="h-val">evt_01J9Q4…c2e</span>
              {'\n'}
              <span className="h-key">webhook-timestamp</span>: <span className="h-val">1760536801</span>
              {'\n'}
              <span className="h-key">webhook-signature</span>: <span className="h-val">v1,K7mQ2x…Zc4=</span>
              {'\n'}
              <span className="h-key">X-Webhook-Delivery-Id</span>: dlv_7f3a9c21{'\n'}
              <span className="h-key">X-Webhook-Attempt</span>: 3{'\n'}
              {'\n'}
              {'{"id":"evt_01J9Q4…c2e","type":"order.created",\n "createdAt":"2026-10-15T14:00:01Z","data":{…}}'}
            </pre>
            <p className="lp-request-foot">signed bytes = webhook-id + "." + webhook-timestamp + "." + body</p>
          </figure>
        </div>

        <div className="lp-feature">
          <div className="lp-feature-text">
            <h3 className="lp-h3">Private networks stay private.</h3>
            <p>
              Every resolved address must be public. The check runs when an endpoint is registered and again inside the HTTP client's DNS
              resolver, which closes the DNS-rebinding gap. Redirects are never followed.
            </p>
            <Bullets items={['https only, no credentials in URLs', '2 KB response bodies stored', '5 s connect · 15 s total deadline']} />
          </div>
          <figure className="lp-dest">
            <figcaption className="lp-dest-head">
              <span>Destination check</span>
              <span className="mono dim">at registration and at connect time</span>
            </figcaption>
            <table>
              <tbody>
                {DESTINATIONS.map((d) => (
                  <tr key={d.url}>
                    <td className="mono">{d.url}</td>
                    <td>{d.why}</td>
                    <td>
                      <Pill tone={d.tone} dot={false}>
                        {d.verdict}
                      </Pill>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </figure>
        </div>
      </div>
    </section>
  );
}

function FailureAndRecovery() {
  return (
    <section className="lp-section lp-paper">
      <div className="lp-container">
        <SectionHeader kicker="04 — Failure and recovery" title="When an endpoint breaks, nothing is lost.">
          A failed delivery keeps its payload and every attempt. Fix the endpoint, replay, and the receiver gets the same event as a new
          delivery with a fresh retry budget.
        </SectionHeader>
        <ol className="lp-chain">
          {CHAIN.map((item, i) => (
            <li key={item.id} className="lp-chain-item">
              {i > 0 && (
                <span className="lp-chain-link" aria-hidden="true">
                  <span>replay {i}</span>
                </span>
              )}
              <div className={`lp-chain-card${item.tone === 'delivered' ? ' lp-chain-card-ok' : ''}`}>
                <div className="lp-chain-head">
                  <span>{item.label}</span>
                  <Pill tone={item.tone} outline={false}>
                    {item.status}
                  </Pill>
                </div>
                <p className="lp-chain-id">{item.id}</p>
                <p className="lp-chain-summary">{item.summary}</p>
                <span className={`tag tag-${item.tone}`}>{item.code}</span>
              </div>
            </li>
          ))}
        </ol>
        <div className="lp-three">
          <div>
            <h3>Same webhook-id</h3>
            <p>Every delivery in the chain sends the same event id, so receivers that already processed it can de-duplicate.</p>
          </div>
          <div>
            <h3>History is never rewritten</h3>
            <p>The dead letter and its attempts stay exactly as recorded. Each replay is a new row linked by replayOfDeliveryId.</p>
          </div>
          <div>
            <h3>Replays are guarded</h3>
            <p>One unfinished delivery per chain, up to 10 replays, and only to an endpoint that is enabled.</p>
          </div>
        </div>
      </div>
    </section>
  );
}

function EngineeringNotes() {
  return (
    <section className="lp-section lp-paper-white" id="engineering-notes">
      <div className="lp-container">
        <SectionHeader kicker="05 — Engineering notes" title="The decisions behind the guarantees.">
          Short versions of what the README explains in full.
        </SectionHeader>
        <div className="lp-notes">
          {NOTES.map((note, i) => (
            <article key={note.title} className="lp-note">
              <span className="lp-note-num">{String(i + 1).padStart(2, '0')}</span>
              <h3>{note.title}</h3>
              <p>{note.body}</p>
            </article>
          ))}
        </div>
      </div>
    </section>
  );
}

function FinalCta() {
  return (
    <section className="lp-section lp-paper lp-cta">
      <div className="lp-container lp-cta-grid">
        <div>
          <h2 className="lp-h2">Read the code behind every guarantee.</h2>
          <p className="lp-sub">
            The README walks through each design decision, and the receiver simulator lets you break an endpoint on purpose and watch the
            platform recover.
          </p>
          <div className="lp-ctas">
            <a href={REPO} className="lp-btn lp-btn-ink">
              View on GitHub
            </a>
            <Link to="/overview" className="lp-btn lp-btn-outline-ink">
              Open dashboard
            </Link>
          </div>
        </div>
        <StackIllustration />
      </div>
    </section>
  );
}

function Footer() {
  return (
    <footer className="lp-footer">
      <div className="lp-container">
        <div className="lp-footer-top">
          <div className="lp-footer-about">
            <span className="brand">
              <BrandMark size={22} />
              <span className="brand-name">Relay</span>
            </span>
            <p>A webhook delivery and reliability platform. Java 21, Spring Boot, PostgreSQL and React.</p>
          </div>
          <div className="lp-footer-links">
            <div>
              <p>Product</p>
              <a href="#how-it-works">How it works</a>
              <a href="#reliability">Reliability</a>
              <a href="#reliability-security">Security</a>
              <Link to="/overview">Open dashboard</Link>
            </div>
            <div>
              <p>Project</p>
              <a href={REPO}>GitHub repository</a>
              <a href={`${REPO}#readme`}>README</a>
              <a href={`${REPO}/blob/main/docs/PLAN.md`}>Architecture plan</a>
              <a href="/swagger-ui.html">API reference</a>
            </div>
          </div>
        </div>
        <div className="lp-footer-bottom">
          <span>Portfolio project. Illustrations and demo data are not live metrics.</span>
          <a href={REPO} className="mono">
            github.com/rupinderkaur1904/Webhook
          </a>
        </div>
      </div>
    </footer>
  );
}

/** The public landing page at `/`. Everything on it is static; the dashboard lives behind sign-in. */
export function LandingPage() {
  return (
    <div className="landing">
      <SiteNav />
      <main>
        <Hero />
        <DashboardPreview />
        <HowItWorks />
        <Reliability />
        <FailureAndRecovery />
        <EngineeringNotes />
        <FinalCta />
      </main>
      <Footer />
    </div>
  );
}
