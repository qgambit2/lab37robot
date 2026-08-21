// Thin fetch wrappers over the backend API. All times are UTC; timestamp
// parameters are millis since epoch (matching the backend convention).

async function asJson(response) {
  if (!response.ok) {
    const body = await response.text().catch(() => '')
    throw new Error(body || `${response.status} ${response.statusText}`)
  }
  const text = await response.text()
  return text ? JSON.parse(text) : null
}

const json = { 'Content-Type': 'application/json' }

export const api = {
  // mocks
  controls: () => fetch('/mocks/controls').then(asJson),
  postControls: (body) =>
    fetch('/mocks/controls', { method: 'POST', headers: json, body: JSON.stringify(body) }).then(asJson),

  // orders
  orders: (params) => fetch(`/v1/orders?${new URLSearchParams(params)}`).then(asJson),
  order: (id) => fetch(`/v1/orders/${id}`).then(asJson),
  orderHistory: (id) => fetch(`/v1/orders/${id}/history`).then(asJson),
  patchVip: (id, vip) =>
    fetch(`/v1/orders/${id}`, { method: 'PATCH', headers: json, body: JSON.stringify({ vip }) }).then(asJson),

  // dispatch metrics & jobs
  dispatched: (from, to) =>
    fetch(`/v1/orders-dispatched?${new URLSearchParams({ from, to })}`).then(asJson),
  job: (id) => fetch(`/v1/jobs/${id}`).then(asJson),
}

/** "2026-08-21T02:45:28.359Z" → "08-21 02:45:28" (UTC). */
export function fmtUtc(iso) {
  if (!iso) return '—'
  const s = new Date(iso).toISOString()
  return `${s.slice(5, 10)} ${s.slice(11, 19)}`
}

/** Minute bucket for an epoch-millis instant, yyyyMMddHHmm in UTC. */
export function minuteBucket(millis) {
  return new Date(millis).toISOString().slice(0, 16).replace(/[-T:]/g, '')
}
