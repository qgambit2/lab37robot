const COLORS = {
  CREATED: 'var(--st-created)',
  DISPATCHED: 'var(--st-dispatched)',
  PARTIALLY_DISPATCHED: 'var(--st-partial)',
  UNFULFILLED: 'var(--st-unfulfilled)',
  CANCELLED: 'var(--st-cancelled)',
  ERROR: 'var(--st-error)',
  DROPPED: 'var(--st-dropped)',
}

export default function StatusChip({ status }) {
  return (
    <span className="chip" style={{ background: COLORS[status] || 'var(--muted)' }}>
      {status}
    </span>
  )
}
