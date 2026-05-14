import { useCallback, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { api } from '../../api/endpoints'
import { useSessionStore } from '../../stores/sessionStore'

export function DataImportPanel() {
  const [file, setFile] = useState<File | null>(null)
  const qc = useQueryClient()
  const setDate = useSessionStore((s) => s.setSelectedDate)

  const datesQ = useQuery({ queryKey: ['csvDates'], queryFn: () => api.csvDates() })

  const uploadMut = useMutation({
    mutationFn: (f: File) => api.uploadCsv(f),
    onSuccess: (data) => {
      void qc.invalidateQueries({ queryKey: ['csvDates'] })
      const pd = (data as { primaryDate?: string }).primaryDate
      if (pd) setDate(pd)
    },
  })

  const onPick = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const f = e.target.files?.[0]
    setFile(f ?? null)
  }, [])

  const onDrop = useCallback((e: React.DragEvent) => {
    e.preventDefault()
    const f = e.dataTransfer.files?.[0]
    if (f) setFile(f)
  }, [])

  return (
    <>
      <div className="panel">
        <h2 className="panel-title">Upload shipment file</h2>
        <p className="row-muted">CSV or XLSX (max 20 MB). After upload, continue from Allocation → Step 1.</p>
        <div
          onDragOver={(e) => e.preventDefault()}
          onDrop={onDrop}
          style={{
            border: '2px dashed #d1d5db',
            borderRadius: 8,
            padding: 24,
            textAlign: 'center',
            background: '#f9fafb',
            cursor: 'pointer',
          }}
          onClick={() => document.getElementById('settings-file-input')?.click()}
        >
          <input
            id="settings-file-input"
            type="file"
            accept=".csv,.txt,.xlsx,.xls"
            style={{ display: 'none' }}
            onChange={onPick}
          />
          {file ? <strong>{file.name}</strong> : <span>Drag and drop or click to browse</span>}
        </div>
        <div style={{ marginTop: 10, display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-primary" disabled={!file || uploadMut.isPending} onClick={() => file && uploadMut.mutate(file)}>
            {uploadMut.isPending ? 'Uploading…' : 'Upload'}
          </button>
          <Link to="/allocation/step-1" className="btn btn-secondary" style={{ textDecoration: 'none', display: 'inline-flex', alignItems: 'center' }}>
            Go to allocation
          </Link>
        </div>
        {uploadMut.isError && <p style={{ color: '#b91c1c', marginTop: 8 }}>{(uploadMut.error as Error).message}</p>}
        {uploadMut.isSuccess && <p style={{ color: '#065f46', marginTop: 8 }}>Upload succeeded. Dates refreshed.</p>}
      </div>

      <div className="panel">
        <h2 className="panel-title">Known dates</h2>
        {datesQ.isLoading && <p>Loading…</p>}
        {datesQ.data?.length ? (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {datesQ.data.map((d) => (
              <button key={d} type="button" className="btn btn-secondary" style={{ fontSize: '0.75rem' }} onClick={() => setDate(d)}>
                {d}
              </button>
            ))}
          </div>
        ) : (
          <p className="row-muted">No dates yet — upload a file first.</p>
        )}
      </div>
    </>
  )
}
