import { useCallback, useState } from "react";
import { Sparkles, Loader2, AlertTriangle, RefreshCw, Lightbulb, ListChecks, ArrowRight, Info } from "lucide-react";
import { EmptyState } from "../components/ui";
import {
  askCoach, coachErrorMessage, newIdempotencyKey,
  ALLOWED_WINDOW_DAYS, DEFAULT_WINDOW_DAYS, MAX_QUESTION_LENGTH,
  type CoachInsight, type WindowDays,
} from "../lib/api/coachApi";
import { buildLocalFallback, type LocalFallbackInput } from "../lib/coachFallback";

type Props = {
  /** Local aggregates used to build the fallback when the provider is unavailable. */
  fallbackInput: LocalFallbackInput;
};

/**
 * The AI Coach surface.
 *
 * <p>Single request, single response. There is no conversation to scroll through and no history,
 * because the server keeps neither: asking again is a fresh request, not a follow-up turn.
 *
 * <p>When the provider is unavailable the view can render a clearly-labelled local summary built
 * from the user's own numbers. It is never presented as AI output, and it is deterministic, so the
 * same data always produces the same text. Showing nothing at all would leave a dead screen, and
 * showing AI-styled text we did not generate would misrepresent where the words came from.
 */
export default function CoachView({ fallbackInput }: Props) {
  const [question, setQuestion] = useState("");
  const [windowDays, setWindowDays] = useState<WindowDays>(DEFAULT_WINDOW_DAYS);
  const [insight, setInsight] = useState<CoachInsight | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [showFallback, setShowFallback] = useState(false);
  // One key per submitted question, so a retry of the *same* ask is a replay the server refuses
  // rather than a second bill, while a genuinely new question gets a new key.
  const [idempotencyKey, setIdempotencyKey] = useState(() => newIdempotencyKey());

  const ask = useCallback(async () => {
    const trimmed = question.trim();
    if (trimmed.length === 0 && question.length > 0) {
      setError("Enter a question, or clear the box to get a general review.");
      return;
    }
    setLoading(true);
    setError(null);
    setShowFallback(false);
    try {
      const result = await askCoach({
        question: trimmed.length > 0 ? trimmed : undefined,
        windowDays,
        idempotencyKey,
      });
      setInsight(result);
    } catch (err) {
      setError(coachErrorMessage(err));
      setInsight(null);
      // Only the provider being unavailable is papered over. A quota refusal, a rejected request
      // or a replay each mean something specific, and silently substituting other content would
      // hide the reason the user is looking at.
      const status = (err as { status?: number })?.status;
      if (status === 502 || status === 503 || status === 0) setShowFallback(true);
    } finally {
      setLoading(false);
    }
  }, [idempotencyKey, question, windowDays]);

  function startNewQuestion() {
    setIdempotencyKey(newIdempotencyKey());
    setInsight(null);
    setError(null);
    setShowFallback(false);
  }

  const shown = showFallback ? buildLocalFallback(fallbackInput, windowDays) : insight;


  return (
    <div className="flex-col">
      <div>
        <h2 className="section-title">AI Coach</h2>
        <p className="section-sub">General fitness and wellness guidance from your own data.</p>
      </div>

      <div className="card form-group">
        <label className="form-label" htmlFor="coach-question">Ask a question (optional)</label>
        <textarea
          id="coach-question"
          className="form-input"
          rows={3}
          maxLength={MAX_QUESTION_LENGTH}
          placeholder="How should I adjust my training this week?"
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
        />
        <div className="header-actions" style={{ justifyContent: "space-between" }}>
          <span className="stat-meta">{question.length} / {MAX_QUESTION_LENGTH}</span>
          <div className="habit-days" role="group" aria-label="Time window">
            {ALLOWED_WINDOW_DAYS.map((days) => (
              <button
                key={days}
                type="button"
                className={`chip ${windowDays === days ? "chip-on" : ""}`}
                aria-pressed={windowDays === days}
                onClick={() => setWindowDays(days)}
              >
                {days}d
              </button>
            ))}
          </div>
        </div>

        <div className="header-actions" style={{ justifyContent: "flex-end" }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={startNewQuestion} disabled={loading}>
            <RefreshCw size={16} /> Clear
          </button>
          <button type="button" className="btn" onClick={ask} disabled={loading}>
            {loading ? <Loader2 size={16} className="spinner" /> : <Sparkles size={16} />}
            {loading ? "Thinking…" : "Get insights"}
          </button>
        </div>

        {error && (
          <div className="form-error" role="alert">
            <AlertTriangle size={16} />
            <span>{error}</span>
          </div>
        )}
      </div>

      {!shown && !loading && (
        <EmptyState
          icon={<Sparkles size={28} />}
          title="No insights yet"
          message="Pick a window and ask a question, or leave the box empty for a general review of your recent training."
        />
      )}

      {showFallback && (
        <div className="warn-note" role="status">
          <AlertTriangle size={16} />
          <span>
            The AI Coach is unavailable right now. This is a summary of your own recorded numbers,
            not AI advice.
          </span>
        </div>
      )}

      {shown && (
        <article className="card form-group" aria-live="polite">
          <p className="reco-text"><strong>{shown.summary}</strong></p>
          <CoachSection icon={<ListChecks size={16} />} title="What stands out" items={shown.observations} />
          <CoachSection icon={<Lightbulb size={16} />} title="Suggestions" items={shown.recommendations} />
          <CoachSection icon={<ArrowRight size={16} />} title="Next steps" items={shown.next_actions} />
          {shown.warnings.length > 0 && (
            <section className="warn-note" aria-label="Safety notes">
              <span className="reco-icon"><AlertTriangle size={16} /></span>
              <div>
                <strong>Worth checking</strong>
                <ul className="reco-text">{shown.warnings.map((w, i) => <li key={i}>{w}</li>)}</ul>
              </div>
            </section>
          )}
          {shown.model && <p className="stat-meta">Generated by {shown.model}</p>}
        </article>
      )}

      <div className="info-note">
        <Info size={16} />
        <p className="reco-text">
          The Coach offers general fitness and wellness guidance only. It does not diagnose conditions,
          prescribe treatment, or replace a doctor or other qualified professional. If you are unwell or
          having urgent symptoms, contact a healthcare professional or your local emergency service.
        </p>
      </div>
    </div>
  );
}

function CoachSection({ icon, title, items }: { icon: React.ReactNode; title: string; items: string[] }) {
  if (!items || items.length === 0) return null;
  return (
    <section className="flex-col" style={{ gap: 6 }}>
      <h3 className="stat-label"><span className="reco-icon">{icon}</span> {title}</h3>
      <ul className="reco-text">{items.map((item, i) => <li key={i}>{item}</li>)}</ul>
    </section>
  );
}

