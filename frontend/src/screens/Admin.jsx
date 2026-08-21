import { useEffect, useState } from 'react'
import { api } from '../api.js'
import TpmChart from '../components/TpmChart.jsx'

const CSV_FILES = ['orders_1.csv', 'orders_2.csv', 'orders_3.csv', 'orders_4.csv']

export default function Admin() {
  const [pollingOn, setPollingOn] = useState(null)
  const [reachable, setReachable] = useState(true)
  const [webhookBusy, setWebhookBusy] = useState(false)
  const [webhookResult, setWebhookResult] = useState('')
  const [csvFile, setCsvFile] = useState(CSV_FILES[0])
  const [uploadResult, setUploadResult] = useState('')
  const [uploadErr, setUploadErr] = useState(false)

  useEffect(() => {
    api.controls()
      .then((s) => { setPollingOn(s.pollingApiEnabled); setReachable(true) })
      .catch(() => setReachable(false))
  }, [])

  const togglePolling = async () => {
    const next = !pollingOn
    try {
      const s = await api.postControls({ pollingApiEnabled: next })
      setPollingOn(s.pollingApiEnabled)
    } catch (e) {
      setPollingOn(pollingOn)
    }
  }

  const sendWebhooks = async () => {
    setWebhookBusy(true)
    setWebhookResult('Replaying… this takes a few seconds')
    try {
      const r = await api.postControls({ sendWebhookTraffic: true })
      setWebhookResult(`Sent ${r.webhookSent} · failed ${r.webhookFailed}`)
    } catch (e) {
      setWebhookResult(`Replay failed: ${e.message}`)
    } finally {
      setWebhookBusy(false)
    }
  }

  const uploadCsv = async () => {
    setUploadErr(false)
    setUploadResult('Uploading…')
    try {
      const r = await api.postControls({ uploadFile: csvFile })
      if (r.uploadError) {
        setUploadErr(true)
        setUploadResult(`Upload failed: ${r.uploadError}`)
        return
      }
      const jobId = r.upload?.jobId
      setUploadResult(`Job ${short(jobId)} queued — processing…`)
      // follow the job until it settles (the worker picks it up within 5s)
      for (let i = 0; i < 15; i++) {
        await sleep(2000)
        const job = await api.job(jobId)
        setUploadResult(`Job ${short(jobId)} ${job.status.toLowerCase()}`)
        if (job.status === 'DONE' || job.status === 'FAILED') {
          setUploadErr(job.status === 'FAILED')
          return
        }
      }
    } catch (e) {
      setUploadErr(true)
      setUploadResult(`Upload failed: ${e.message}`)
    }
  }

  return (
    <>
      <p className="eyebrow">Admin</p>
      <h1>Traffic &amp; throughput</h1>
      <p className="page-intro">
        Drive sample traffic through the three intake pipelines and watch the
        robot keep up. Orders land on the Orders screen as they arrive.
      </p>
      {!reachable && (
        <div className="error-banner">
          Backend not reachable — start it with <code>./mvnw spring-boot:run</code> and reload.
        </div>
      )}
      <div className="admin-grid">
        <div className="controls-stack">
          <section className="card">
            <h2>Polling API replay</h2>
            <p className="hint">
              Feeds the poller one line of sample deltas every 10s. Pausing
              holds the stream; resuming continues where it stopped.
            </p>
            <div className="control-row">
              <span className="switch">
                <span className={`dot ${pollingOn ? 'on' : ''}`} />
                {pollingOn == null ? '…' : pollingOn ? 'Replaying' : 'Paused'}
              </span>
              <button onClick={togglePolling} disabled={pollingOn == null}>
                {pollingOn ? 'Pause replay' : 'Start replay'}
              </button>
            </div>
          </section>

          <section className="card">
            <h2>Webhook burst</h2>
            <p className="hint">
              Replays all 1,004 sample webhook orders at once — enough to back
              up the queue and put the rate limit to work.
            </p>
            <button onClick={sendWebhooks} disabled={webhookBusy}>
              {webhookBusy ? 'Sending…' : 'Send 1,004 webhook orders'}
            </button>
            <div className="result-line">{webhookResult}</div>
          </section>

          <section className="card">
            <h2>CSV upload</h2>
            <p className="hint">
              Submits a sample batch to the ingest endpoint, exactly like a
              user upload. Meal windows decide when those orders dispatch.
            </p>
            <div className="control-row">
              <select value={csvFile} onChange={(e) => setCsvFile(e.target.value)}>
                {CSV_FILES.map((f) => <option key={f}>{f}</option>)}
              </select>
              <button onClick={uploadCsv}>Upload</button>
            </div>
            <div className={`result-line ${uploadErr ? 'err' : ''}`}>{uploadResult}</div>
          </section>
        </div>

        <section className="card">
          <TpmChart />
        </section>
      </div>
    </>
  )
}

const short = (id) => (id ? id.slice(0, 8) : '?')
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
