import { useEffect, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { api, fmtUtc } from '../api.js'
import StatusChip from '../components/StatusChip.jsx'

const STATUSES = ['CREATED', 'DISPATCHED', 'PARTIALLY_DISPATCHED', 'UNFULFILLED', 'CANCELLED', 'ERROR', 'DROPPED']

/**
 * A CREATED order with a future or open meal window isn't stuck — say so
 * on the rail instead of making the user drill in to find the window.
 */
function waitingNote(o) {
  if (o.orderStatus !== 'CREATED' || !o.dispatchTimeIntervalStart) return null
  if (o.vip) return '⏱ VIP — dispatches next' // VIP overrides the meal window
  const start = new Date(o.dispatchTimeIntervalStart)
  const end = new Date(o.dispatchTimeIntervalEnd)
  const now = Date.now()
  const hhmm = (d) => d.toISOString().slice(11, 16)
  const dayPrefix = (d) =>
    d.toISOString().slice(0, 10) === new Date(now).toISOString().slice(0, 10)
      ? '' : `${d.toISOString().slice(5, 10)} `
  const meal = o.meal ? `${o.meal} window` : 'meal window'
  if (now < start.getTime()) {
    return `⏱ waits for ${meal} · ${dayPrefix(start)}${hhmm(start)}–${hhmm(end)}`
  }
  if (now < end.getTime()) {
    return `⏱ in ${meal} until ${hhmm(end)} — dispatching`
  }
  return null
}
const SOURCE = { SVC_FILE: 'CSV', WEBHOOK: 'Webhook', API_PULL: 'Polling API' }
const SIZE = 20

export default function Orders() {
  // all filters live in the URL, so navigating into an order and coming
  // back (or refreshing / sharing the link) keeps the selections
  const [searchParams, setSearchParams] = useSearchParams()
  const location = useLocation()
  const status = searchParams.get('status') ?? ''
  const type = searchParams.get('type') ?? ''
  const timeField = searchParams.get('timeField') ?? 'createdTime'
  const since = searchParams.get('since') ?? '' // datetime-local value, interpreted as UTC
  const idQuery = searchParams.get('q') ?? '' // committed on Enter/blur
  const page = Number(searchParams.get('page') ?? 0)
  const [idInput, setIdInput] = useState(idQuery)
  const [result, setResult] = useState(null)
  const [error, setError] = useState(null)

  /** Merge changes into the URL; any filter change resets to the first page. */
  const setFilters = (changes) => {
    const next = new URLSearchParams(searchParams)
    for (const [key, value] of Object.entries(changes)) {
      if (value) next.set(key, value)
      else next.delete(key)
    }
    if (!('page' in changes)) next.delete('page')
    setSearchParams(next)
  }

  useEffect(() => {
    const params = { page, size: SIZE }
    if (status) params.status = status
    if (type) params.type = type
    if (since) params[timeField] = Date.parse(`${since}:00Z`) // input is UTC
    let live = true
    const fetchOrders = async () => {
      // one box finds either id. A UUID is tried as our order id first,
      // then as the source system's id (webhook source ids are UUIDs too);
      // anything non-UUID can only be a source id.
      const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(idQuery)
      if (idQuery && isUuid) {
        const byOrderId = await api.orders({ ...params, orderId: idQuery })
        if (byOrderId.page.totalElements > 0) return byOrderId
        return api.orders({ ...params, sourceOrderId: idQuery })
      }
      if (idQuery) return api.orders({ ...params, sourceOrderId: idQuery })
      return api.orders(params)
    }
    fetchOrders()
      .then((r) => { if (live) { setResult(r); setError(null) } })
      .catch((e) => { if (live) setError(String(e.message || e)) })
    return () => { live = false }
  }, [status, type, timeField, since, idQuery, page])

  const commitIdSearch = () => setFilters({ q: idInput.trim() })

  const pageInfo = result?.page
  const orders = result?.content ?? []

  return (
    <>
      <p className="eyebrow">Orders</p>

      <div className="filter-row">
        <input
          type="search"
          placeholder="Order id or source id"
          value={idInput}
          size={30}
          style={{ font: '13px var(--mono)', border: '1px solid var(--line)', borderRadius: 6, padding: '7px 10px' }}
          onChange={(e) => { setIdInput(e.target.value); if (e.target.value === '') setFilters({ q: '' }) }}
          onBlur={commitIdSearch}
          onKeyDown={(e) => e.key === 'Enter' && commitIdSearch()}
        />
        <label>Status</label>
        <select value={status} onChange={(e) => setFilters({ status: e.target.value })}>
          <option value="">Any</option>
          {STATUSES.map((s) => <option key={s}>{s}</option>)}
        </select>
        <label>Type</label>
        <select value={type} onChange={(e) => setFilters({ type: e.target.value })}>
          <option value="">Any</option>
          <option value="SVC_FILE">CSV</option>
          <option value="WEBHOOK">Webhook</option>
          <option value="API_PULL">Polling API</option>
        </select>
        <label>
          <select value={timeField} style={{ marginRight: 6 }}
              onChange={(e) => setFilters({ timeField: e.target.value })}>
            <option value="createdTime">Created</option>
            <option value="updatedTime">Updated</option>
          </select>
          at or after (UTC)
        </label>
        <input type="datetime-local" value={since}
            onChange={(e) => setFilters({ since: e.target.value })} />
        {since && (
          <button className="quiet" onClick={() => setFilters({ since: '' })}>Clear</button>
        )}
      </div>

      {error && <div className="error-banner">Could not load orders: {error}</div>}
      {!result && !error && <div className="loading">Loading…</div>}

      <div className="rail">
        {orders.map((o) => (
          <Link className="ticket" key={o.id} to={`/orders/${o.id}`}
              state={{ search: location.search }}>
            {o.vip && <span className="vip-tab">VIP</span>}
            <span className="punch" />
            <span>
              <div className="id">{o.id.slice(0, 8)}</div>
              <div className="src">
                {SOURCE[o.orderType] || o.orderType}
                {o.externalOrderId ? ` · ${o.externalOrderId.slice(0, 8)}` : ''}
              </div>
            </span>
            <span className="items">{o.items || <em>no items</em>}</span>
            <span className="meta">
              {waitingNote(o) && <span className="waiting-note">{waitingNote(o)}</span>}
              <StatusChip status={o.orderStatus} />
              {o.amount != null && <span>${Number(o.amount).toFixed(2)}</span>}
              <span>{fmtUtc(o.createdAt)}</span>
            </span>
          </Link>
        ))}
      </div>

      {result && orders.length === 0 && (
        <div className="empty">No orders match — adjust the filters, or send some traffic from Admin.</div>
      )}

      {pageInfo && pageInfo.totalPages > 1 && (
        <div className="pager">
          <button className="quiet" disabled={page === 0}
              onClick={() => setFilters({ page: String(page - 1) })}>← Newer</button>
          <span className="info">
            page {pageInfo.number + 1} of {pageInfo.totalPages} · {pageInfo.totalElements} orders
          </span>
          <button className="quiet" disabled={page >= pageInfo.totalPages - 1}
              onClick={() => setFilters({ page: String(page + 1) })}>Older →</button>
        </div>
      )}
    </>
  )
}
