import { useMemo, useState } from "react";
import { Search, Dumbbell, Info } from "lucide-react";
import { MUSCLE_GROUPS, type Exercise } from "../lib/types";
import { EmptyState, Modal, SectionHeader } from "../components/ui";

type Props = { exercises: Exercise[] };

const difficultyTone: Record<string, string> = {
  Beginner: "#4ade80",
  Intermediate: "#fb923c",
  Advanced: "#f87171",
};

export function ExerciseLibraryView({ exercises }: Props) {
  const [search, setSearch] = useState("");
  const [group, setGroup] = useState("All");
  const [equipment, setEquipment] = useState("All");
  const [selected, setSelected] = useState<Exercise | null>(null);

  const equipmentOptions = useMemo(
    () => Array.from(new Set(exercises.map((e) => e.equipment))).sort(),
    [exercises]
  );

  const filtered = useMemo(() => {
    const needle = search.trim().toLowerCase();
    return exercises.filter((e) => {
      if (group !== "All" && e.muscle_group !== group) return false;
      if (equipment !== "All" && e.equipment !== equipment) return false;
      if (!needle) return true;
      return (
        e.name.toLowerCase().includes(needle) ||
        e.muscle_group.toLowerCase().includes(needle) ||
        e.equipment.toLowerCase().includes(needle) ||
        e.secondary_muscles.some((m) => m.toLowerCase().includes(needle))
      );
    });
  }, [exercises, search, group, equipment]);

  const grouped = useMemo(() => {
    const map = new Map<string, Exercise[]>();
    for (const e of filtered) {
      const list = map.get(e.muscle_group) ?? [];
      list.push(e);
      map.set(e.muscle_group, list);
    }
    return Array.from(map.entries()).sort((a, b) => a[0].localeCompare(b[0]));
  }, [filtered]);

  return (
    <div className="flex-col">
      <SectionHeader title="Exercise library" subtitle={`${exercises.length} exercises you can add to any workout`} />

      <div style={{ display: "flex", gap: 12, flexWrap: "wrap" }}>
        <div style={{ position: "relative", flex: 1, minWidth: 220 }}>
          <Search size={16} color="#64748b" style={{ position: "absolute", left: 12, top: 12 }} />
          <input
            className="form-input"
            style={{ paddingLeft: 36 }}
            placeholder="Search by name, muscle or equipment"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            aria-label="Search exercises"
          />
        </div>
        <select className="form-select" style={{ width: "auto" }} value={group} onChange={(e) => setGroup(e.target.value)} aria-label="Filter by muscle group">
          <option value="All">All muscle groups</option>
          {MUSCLE_GROUPS.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
        <select className="form-select" style={{ width: "auto" }} value={equipment} onChange={(e) => setEquipment(e.target.value)} aria-label="Filter by equipment">
          <option value="All">All equipment</option>
          {equipmentOptions.map((m) => <option key={m} value={m}>{m}</option>)}
        </select>
      </div>

      {filtered.length === 0 ? (
        <div className="card">
          <EmptyState
            icon={<Search size={28} color="#64748b" />}
            title="No exercises match"
            message="Try a different search term or clear the filters."
          />
        </div>
      ) : (
        grouped.map(([muscle, list]) => (
          <div key={muscle}>
            <h3 style={{ fontSize: 15, fontWeight: 700, color: "#cbd5e1", margin: "0 0 12px" }}>
              {muscle} <span style={{ color: "#475569", fontWeight: 600 }}>· {list.length}</span>
            </h3>
            <div className="grid-3">
              {list.map((e) => (
                <button
                  key={e.id}
                  className="card exercise-card"
                  onClick={() => setSelected(e)}
                  style={{ textAlign: "left", cursor: "pointer" }}
                >
                  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 8 }}>
                    <span style={{ fontSize: 15, fontWeight: 700, color: "#f0f6fc" }}>{e.name}</span>
                    <div className="stat-icon" style={{ background: "rgba(56,189,248,0.14)", color: "#38bdf8", width: 30, height: 30 }}>
                      <Dumbbell size={15} />
                    </div>
                  </div>
                  <p style={{ fontSize: 13, color: "#94a3b8", margin: "8px 0 10px", lineHeight: 1.5 }}>{e.description}</p>
                  <div className="workout-meta">
                    <span>{e.equipment}</span>
                    <span style={{ color: difficultyTone[e.difficulty] ?? "#94a3b8", fontWeight: 600 }}>{e.difficulty}</span>
                    {e.is_compound && <span>Compound</span>}
                  </div>
                </button>
              ))}
            </div>
          </div>
        ))
      )}

      {selected && (
        <Modal title={selected.name} onClose={() => setSelected(null)}>
          <div className="flex-col" style={{ gap: 16 }}>
            <div className="workout-meta">
              <span className="badge badge-moderate">{selected.muscle_group}</span>
              <span>{selected.equipment}</span>
              <span style={{ color: difficultyTone[selected.difficulty] ?? "#94a3b8", fontWeight: 600 }}>{selected.difficulty}</span>
              {selected.is_compound && <span className="badge badge-done">Compound</span>}
            </div>

            <div>
              <div className="stat-label" style={{ marginBottom: 6 }}>How to perform it</div>
              <p style={{ fontSize: 14, color: "#cbd5e1", lineHeight: 1.6, margin: 0 }}>
                {selected.instructions || "No instructions recorded for this exercise yet."}
              </p>
            </div>

            {selected.secondary_muscles.length > 0 && (
              <div>
                <div className="stat-label" style={{ marginBottom: 6 }}>Also works</div>
                <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                  {selected.secondary_muscles.map((m) => <span key={m} className="badge badge-pending">{m}</span>)}
                </div>
              </div>
            )}

            {selected.description && (
              <div style={{ display: "flex", gap: 8, alignItems: "flex-start" }}>
                <Info size={15} color="#64748b" style={{ marginTop: 2, flexShrink: 0 }} />
                <p style={{ fontSize: 13, color: "#94a3b8", margin: 0, lineHeight: 1.55 }}>{selected.description}</p>
              </div>
            )}

            <div style={{ display: "flex", justifyContent: "flex-end" }}>
              <button className="btn btn-secondary" onClick={() => setSelected(null)}>Close</button>
            </div>
          </div>
        </Modal>
      )}
    </div>
  );
}

export default ExerciseLibraryView;


