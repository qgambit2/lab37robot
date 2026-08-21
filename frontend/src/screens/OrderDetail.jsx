import { useCallback, useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router-dom'
import { api, fmtUtc } from '../api.js'
import StatusChip from '../components/StatusChip.jsx'

const SOURCE = { SVC_FILE: 'CSV upload', WEBHOOK: 'Webhook', API_PULL: 'Polling API' }

export default function OrderDetail() {
  const { id } = useParams()
  // the rail passes its filter query along, so "Orders" leads back to the
  // same filtered view
  const backTo = `/orders${useLocation().state?.search ?? ''}`
  const [details, setDetails] = useState(null)
  const [history, setHistory] = useState(null)
  const [error, setError] = useState(null)
  const [vipMsg, setVipMsg] = useState('')

  const load = useCallback(() => {
    api.order(id).then(setDetails).catch((e) => setError(String(e.message || e)))
    api.orderHistory(id).then(setHistory).catch(() => setHistory([]))
  }, [id])

  useEffect(load, [load])

  const toggleVip = async () => {
    try {
      setVipMsg('')
      await api.patchVip(id, !details.order.vip)
      load()
    } catch (e) {
      setVipMsg(e.message)
    }
  }

  if (error) {
    return (
      <>
        <div className="error-banner">Order not found: {error}</div>
        <Link to={backTo}>← Back to orders</Link>
      </>
    )
  }
  if (!details) return <div className="loading">Loading…</div>

  const { order, items } = details
  // item status is our dispatch record: DISPATCHED = included in the robot
  // payload, CREATED on a dispatched order = deliberately not sent
  const orderDispatched = order.dispatchTime != null
  const sentMark = (itemStatus, dispatchedContext = orderDispatched) => {
    if (itemStatus === 'DISPATCHED') return <strong className="sent"> · ✓ sent to robot</strong>
    if (dispatchedContext) return <span> · not sent</span>
    return null
  }

  return (
    <>
      <p className="eyebrow"><Link to={backTo}>Orders</Link> / order</p>
      <h1 style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        Order
        <StatusChip status={order.orderStatus} />
        {order.vip && <span className="vip-tab" style={{ position: 'static', borderRadius: 4 }}>VIP</span>}
      </h1>
      {order.error && <p className="page-intro" style={{ color: 'var(--st-error)' }}>{order.error}</p>}

      <div className="detail-grid">
        <section className="card">
          <h2>Details</h2>
          <dl className="kv">
            <dt>Order id</dt><dd style={{ wordBreak: 'break-all' }}>{order.id}</dd>
            {order.externalOrderId && (
              <><dt>Source order id</dt><dd style={{ wordBreak: 'break-all' }}>{order.externalOrderId}</dd></>
            )}
            <dt>Type</dt><dd>{SOURCE[order.orderType] || order.orderType}</dd>
            <dt>Created</dt><dd>{fmtUtc(order.createdAt)}</dd>
            <dt>Updated</dt><dd>{fmtUtc(order.updatedAt)}</dd>
            <dt>Dispatched</dt><dd>{fmtUtc(order.dispatchTime)}</dd>
            {order.dispatchTimeIntervalStart && (
              <>
                <dt>Meal window</dt>
                <dd>{fmtUtc(order.dispatchTimeIntervalStart)} → {fmtUtc(order.dispatchTimeIntervalEnd)}</dd>
              </>
            )}
            {order.amount != null && (<><dt>Amount</dt><dd>${Number(order.amount).toFixed(2)}</dd></>)}
            {(order.firstName || order.lastName) && (
              <><dt>Customer</dt><dd>{[order.firstName, order.lastName].filter(Boolean).join(' ')}</dd></>
            )}
            {order.restaurant && (<><dt>Restaurant</dt><dd>{order.restaurant}</dd></>)}
            {order.orderSource && (<><dt>Source</dt><dd>{order.orderSource}</dd></>)}
            {order.notes && (<><dt>Notes</dt><dd>{order.notes}</dd></>)}
            <dt>Version</dt><dd>{order.version}</dd>
          </dl>
          {order.orderStatus === 'CREATED' && (
            <div style={{ marginTop: 14 }} className="control-row">
              <button onClick={toggleVip}>
                {order.vip ? 'Remove VIP' : 'Make VIP — jump the queue'}
              </button>
              {vipMsg && <span className="result-line err">{vipMsg}</span>}
            </div>
          )}

          <h2 style={{ marginTop: 22 }}>Items</h2>
          {items.length === 0 && <p className="hint">No items on this order.</p>}
          {items.map((it) => (
            <div className="item-line" key={it.id}>
              <span>{it.itemName}</span>
              <span className="ss">
                {it.itemPrice != null && <>${Number(it.itemPrice).toFixed(2)} · </>}
                {it.sourceStatus || 'no source status'}
                {sentMark(it.status)}
              </span>
            </div>
          ))}
        </section>

        <section className="card">
          <h2>History</h2>
          <p className="hint">Every version, oldest first, with item states as they were.</p>
          {!history && <div className="loading">Loading…</div>}
          {history?.map(({ order: snap, items: snapItems }) => (
            <div className="version" key={snap.id}>
              <div className="vhead">
                <span>v{snap.version}</span>
                <StatusChip status={snap.orderStatus} />
                {snap.vip && <strong style={{ color: 'var(--brass)' }}>VIP</strong>}
                <span>{fmtUtc(snap.createdAt)}</span>
              </div>
              {snap.error && <div className="result-line err">{snap.error}</div>}
              {snapItems.map((it) => (
                <div className="item-line" key={it.id}>
                  <span>{it.itemName}</span>
                  <span className="ss">
                    {it.sourceStatus || '—'}
                    {sentMark(it.status, snap.orderStatus === 'DISPATCHED'
                        || snap.orderStatus === 'PARTIALLY_DISPATCHED')}
                  </span>
                </div>
              ))}
            </div>
          ))}
        </section>
      </div>
    </>
  )
}
