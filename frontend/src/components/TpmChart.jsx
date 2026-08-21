import { useEffect, useMemo, useRef, useState } from 'react'
import { api, minuteBucket } from '../api.js'

const LIMIT = 100 // dispatch.max-per-minute (backend config default)
const MINUTES = 60
const W = 760
const H = 220
const PAD = { top: 16, right: 56, bottom: 26, left: 34 }

/**
 * Robot dispatches per minute, last hour, zeros filled (the API omits
 * empty minutes; the chart shows them). Single series — bars in the data
 * accent, a dashed reference line at the rate limit, per-bar tooltip.
 * The current (still accumulating) minute renders dimmed.
 */
export default function TpmChart() {
  const [buckets, setBuckets] = useState(null)
  const [error, setError] = useState(null)
  const [tip, setTip] = useState(null)
  const wrapRef = useRef(null)

  useEffect(() => {
    let live = true
    const load = async () => {
      try {
        const now = Date.now()
        const from = minuteBucket(now - (MINUTES - 1) * 60_000)
        const to = minuteBucket(now)
        const sparse = await api.dispatched(from, to)
        if (!live) return
        const byMinute = Object.fromEntries(sparse.map((b) => [b.minute, b.count]))
        const filled = Array.from({ length: MINUTES }, (_, i) => {
          const minute = minuteBucket(now - (MINUTES - 1 - i) * 60_000)
          return { minute, count: byMinute[minute] ?? 0 }
        })
        setBuckets(filled)
        setError(null)
      } catch (e) {
        if (live) setError(String(e.message || e))
      }
    }
    load()
    const timer = setInterval(load, 10_000)
    return () => { live = false; clearInterval(timer) }
  }, [])

  const yMax = useMemo(() => {
    const dataMax = Math.max(0, ...(buckets || []).map((b) => b.count))
    return Math.max(LIMIT, Math.ceil(dataMax / 10) * 10) * 1.08
  }, [buckets])

  if (error) return <div className="result-line err">Throughput unavailable: {error}</div>
  if (!buckets) return <div className="result-line">Loading throughput…</div>

  const plotW = W - PAD.left - PAD.right
  const plotH = H - PAD.top - PAD.bottom
  const slot = plotW / MINUTES
  const barW = Math.max(2, slot - 2) // 2px surface gap between bars
  const y = (v) => PAD.top + plotH - (v / yMax) * plotH
  const lastDone = buckets[MINUTES - 2]

  return (
    <div>
      <div className="chart-head">
        <div>
          <h2>Robot dispatches per minute — last hour</h2>
          <p className="hint">Limit {LIMIT}/min · refreshes every 10s</p>
        </div>
        <div className="stat-tile">
          <div className="num">{lastDone.count}</div>
          <div className="cap">last full minute · {fmtMin(lastDone.minute)} UTC</div>
        </div>
      </div>
      <div className="chart-wrap" ref={wrapRef}>
        <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', display: 'block' }} role="img"
            aria-label="Bar chart of robot dispatches per minute over the last hour">
          {[0, LIMIT / 2, LIMIT].map((v) => (
            <g key={v}>
              <line x1={PAD.left} x2={W - PAD.right} y1={y(v)} y2={y(v)}
                  stroke="var(--line)" strokeWidth="1" />
              <text x={PAD.left - 6} y={y(v) + 4} textAnchor="end"
                  fontSize="11" fontFamily="var(--mono)" fill="var(--muted)">{v}</text>
            </g>
          ))}
          {/* rate limit reference */}
          <line x1={PAD.left} x2={W - PAD.right} y1={y(LIMIT)} y2={y(LIMIT)}
              stroke="var(--muted)" strokeWidth="1" strokeDasharray="4 3" />
          <text x={W - PAD.right + 6} y={y(LIMIT) + 4} fontSize="11"
              fontFamily="var(--mono)" fill="var(--muted)">limit</text>

          {buckets.map((b, i) => {
            const x = PAD.left + i * slot + 1
            const h = Math.max(b.count > 0 ? 2 : 0, ((b.count / yMax) * plotH))
            const inProgress = i === MINUTES - 1
            return (
              <g key={b.minute}>
                {/* mark */}
                {b.count > 0 && (
                  <rect x={x} y={y(b.count)} width={barW} height={h} rx="1.5"
                      fill="var(--enamel)" opacity={inProgress ? 0.45 : 1} />
                )}
                {/* hover hit target: full column, wider than the mark */}
                <rect x={PAD.left + i * slot} y={PAD.top} width={slot} height={plotH}
                    fill="transparent"
                    onMouseEnter={() => setTip({ i, b, xPct: (x + barW / 2) / W, yPx: y(b.count) })}
                    onMouseLeave={() => setTip(null)} />
              </g>
            )
          })}
          {/* baseline + x labels every 10 minutes */}
          <line x1={PAD.left} x2={W - PAD.right} y1={PAD.top + plotH} y2={PAD.top + plotH}
              stroke="var(--muted)" strokeWidth="1" />
          {buckets.map((b, i) =>
            i % 10 === 0 ? (
              <text key={b.minute} x={PAD.left + i * slot} y={H - 8} fontSize="11"
                  fontFamily="var(--mono)" fill="var(--muted)">{fmtMin(b.minute)}</text>
            ) : null,
          )}
        </svg>
        {tip && (
          <div className="chart-tip"
              style={{ left: `${tip.xPct * 100}%`, top: `${(tip.yPx / H) * 100}%` }}>
            {fmtMin(tip.b.minute)} UTC · {tip.b.count} dispatched
            {tip.i === MINUTES - 1 ? ' (so far)' : ''}
          </div>
        )}
      </div>
    </div>
  )
}

function fmtMin(bucket) {
  return `${bucket.slice(8, 10)}:${bucket.slice(10, 12)}`
}
