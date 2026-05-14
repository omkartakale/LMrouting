import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useSessionStore } from '../../stores/sessionStore'
import { usePlanStore, type RegionShape } from '../../stores/planStore'
import { api } from '../../api/endpoints'
import type { AttendanceRowDto } from '../../types/domain'

export function PlanCrew() {
  const date = useSessionStore((s) => s.selectedDate)
  const allocationMode = useSessionStore((s) => s.allocationMode)
  const regions = usePlanStore((s) => s.regions)
  const srZoneMap = usePlanStore((s) => s.srZoneMap)
  const setSrZone = usePlanStore((s) => s.setSrZone)
  const setShift = usePlanStore((s) => s.setShift)
  const shiftDurations = usePlanStore((s) => s.shiftDurations)
  const hydrateShifts = usePlanStore((s) => s.hydrateShifts)
  const saveCrewAndZones = usePlanStore((s) => s.saveCrewAndZones)
  const dirty = usePlanStore((s) => s.dirty)
  const lastSavedAt = usePlanStore((s) => s.lastSavedAt)
  const [newSr, setNewSr] = useState('')

  const qc = useQueryClient()
  const hubQ = useQuery({ queryKey: ['hub'], queryFn: () => api.hub() })

  const shiftQ = useQuery({ queryKey: ['shiftDurations'], queryFn: () => api.getShiftDurations() })
  useEffect(() => {
    if (shiftQ.data) hydrateShifts(shiftQ.data)
  }, [shiftQ.data, hydrateShifts])

  const attQ = useQuery({
    queryKey: ['attendance', date],
    queryFn: () => api.attendance(date!),
    enabled: !!date,
  })

  const saveMut = useMutation({
    mutationFn: async () => {
      const hub = hubQ.data?.hubName ?? 'PNQ HDP'
      await saveCrewAndZones(hub)
    },
    onSuccess: () => {
      void qc.invalidateQueries({ queryKey: ['affinityLoad'] })
    },
  })

  const hasZones = Object.values(regions).some((r) => r.latlngs.length >= 3)

  const toggle = async (srName: string, present: boolean) => {
    if (!date) return
    await api.setAttendance(date, srName, present)
    void qc.invalidateQueries({ queryKey: ['attendance', date] })
  }

  const addSrMut = useMutation({
    mutationFn: () => api.addSr(newSr.trim()),
    onSuccess: () => {
      setNewSr('')
      void qc.invalidateQueries({ queryKey: ['attendance', date] })
    },
  })

  if (!date) {
    return (
      <div className="panel">
        <p className="row-muted">Select a date in the previous step to load crew.</p>
      </div>
    )
  }

  return (
    <div className="panel">
      <h2 className="panel-title">SR attendance & zones</h2>
      <p className="row-muted">
        Toggle presence per SR. Assign zones when polygons exist. Shift minutes apply in time-based mode.
      </p>
      <div style={{ display: 'flex', gap: 6, marginBottom: 8 }}>
        <button
          type="button"
          className="btn btn-secondary"
          style={{ flex: 1, fontSize: '0.76rem' }}
          onClick={() => attQ.data?.forEach((a: AttendanceRowDto) => void toggle(a.srName, true))}
        >
          All present
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          style={{ flex: 1, fontSize: '0.76rem' }}
          onClick={() => attQ.data?.forEach((a: AttendanceRowDto) => void toggle(a.srName, false))}
        >
          All absent
        </button>
      </div>
      <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
        <input
          type="text"
          placeholder="New SR name (e.g. SR-011)"
          value={newSr}
          onChange={(e) => setNewSr(e.target.value)}
          style={{ flex: 1, padding: 6, borderRadius: 6, border: '1px solid #d1d5db' }}
        />
        <button type="button" className="btn btn-primary" disabled={!newSr.trim() || addSrMut.isPending} onClick={() => addSrMut.mutate()}>
          Add SR
        </button>
      </div>
      {attQ.isLoading && <p>Loading attendance…</p>}
      {attQ.data?.map((a: AttendanceRowDto) => (
        <div
          key={a.srName}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            flexWrap: 'wrap',
            padding: '6px 0',
            borderBottom: '1px solid #f3f4f6',
            fontSize: '0.8rem',
          }}
        >
          <span style={{ minWidth: 56, fontWeight: 600 }}>{a.srName}</span>
          {hasZones && (
            <select
              value={srZoneMap[a.srName] ?? ''}
              onChange={(e) => setSrZone(a.srName, e.target.value)}
              style={{ flex: 1, minWidth: 100, padding: 4, borderRadius: 6, border: '1px solid #d1d5db' }}
            >
              <option value="">— No zone —</option>
              {(Object.entries(regions) as [string, RegionShape][])
                .filter(([, r]) => r.latlngs.length >= 3)
                .map(([name]) => (
                  <option key={name} value={name}>
                    {name}
                  </option>
                ))}
            </select>
          )}
          {allocationMode === 'time-based' && (
            <input
              type="number"
              min={120}
              max={720}
              step={30}
              value={shiftDurations[a.srName] ?? 600}
              onChange={(e) => setShift(a.srName, parseInt(e.target.value, 10) || 600)}
              style={{ width: 64, padding: 4, borderRadius: 6, border: '1px solid #d1d5db' }}
              title="Shift minutes"
            />
          )}
          <label style={{ display: 'flex', alignItems: 'center', gap: 4, marginLeft: 'auto' }}>
            <input type="checkbox" checked={a.present} onChange={(e) => void toggle(a.srName, e.target.checked)} />
            Present
          </label>
        </div>
      ))}
      <div style={{ marginTop: 12 }}>
        <button
          type="button"
          className="btn btn-primary"
          style={{ width: '100%' }}
          disabled={saveMut.isPending || !hubQ.data}
          onClick={() => saveMut.mutate()}
        >
          {saveMut.isPending ? 'Saving…' : 'Save crew & zones'}
        </button>
        <p className="row-muted" style={{ marginTop: 6 }}>
          {dirty ? 'Unsaved changes' : lastSavedAt ? `All saved (${new Date(lastSavedAt).toLocaleTimeString()})` : 'No pending changes'}
        </p>
        {saveMut.isError && <p style={{ color: '#b91c1c' }}>{(saveMut.error as Error).message}</p>}
      </div>
    </div>
  )
}
