import { useState, type FormEvent } from "react";
import { Activity, Mail, Lock, AlertTriangle, ArrowRight } from "lucide-react";
import { useAuth } from "../lib/auth";

export default function AuthScreen() {
  const { signIn, signUp } = useAuth();
  const [mode, setMode] = useState<"signin" | "signup">("signin");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setNotice(null);

    if (!email.trim() || !email.includes("@")) {
      setError("Please enter a valid email address.");
      return;
    }
    if (password.length < 6) {
      setError("Your password must be at least 6 characters.");
      return;
    }
    if (mode === "signup" && password !== confirm) {
      setError("Those passwords do not match.");
      return;
    }

    setBusy(true);
    const result = mode === "signin" ? await signIn(email.trim(), password) : await signUp(email.trim(), password);
    setBusy(false);

    if (result.error) {
      setError(result.error);
      return;
    }
    if (mode === "signup") {
      setMode("signin");
      setPassword("");
      setConfirm("");
      setNotice("Your account is ready. Sign in to continue.");
    }
  }

  return (
    <div className="auth-page">
      <div className="auth-card">
        <div className="auth-brand">
          <div className="logo">
            <Activity size={22} color="#38bdf8" />
          </div>
          <span className="brand-name">
            FitTrack <span style={{ color: "#38bdf8" }}>AI</span>
          </span>
        </div>

        <h1 className="auth-title">{mode === "signin" ? "Welcome back" : "Create your account"}</h1>
        <p className="auth-sub">
          {mode === "signin"
            ? "Sign in to view your training, nutrition and recovery."
            : "Your workouts, meals and health data stay private to you."}
        </p>

        <form onSubmit={handleSubmit} className="auth-form">
          {error && (
            <div className="form-error" role="alert">
              <AlertTriangle size={16} />
              <span>{error}</span>
            </div>
          )}
          {notice && <div className="info-note" role="status">{notice}</div>}

          <div className="form-group">
            <label className="form-label" htmlFor="email">Email address</label>
            <div className="input-with-icon">
              <Mail size={16} color="#64748b" />
              <input
                id="email"
                className="form-input"
                type="email"
                autoComplete="email"
                placeholder="you@example.com"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
              />
            </div>
          </div>

          <div className="form-group">
            <label className="form-label" htmlFor="password">Password</label>
            <div className="input-with-icon">
              <Lock size={16} color="#64748b" />
              <input
                id="password"
                className="form-input"
                type="password"
                autoComplete={mode === "signin" ? "current-password" : "new-password"}
                placeholder="At least 6 characters"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </div>
          </div>

          {mode === "signup" && (
            <div className="form-group">
              <label className="form-label" htmlFor="confirm">Confirm password</label>
              <div className="input-with-icon">
                <Lock size={16} color="#64748b" />
                <input
                  id="confirm"
                  className="form-input"
                  type="password"
                  autoComplete="new-password"
                  placeholder="Re-enter your password"
                  value={confirm}
                  onChange={(e) => setConfirm(e.target.value)}
                />
              </div>
            </div>
          )}

          <button className="btn auth-submit" type="submit" disabled={busy}>
            {busy ? "Please wait…" : mode === "signin" ? "Sign in" : "Create account"}
            {!busy && <ArrowRight size={16} />}
          </button>
        </form>

        <div className="auth-switch">
          {mode === "signin" ? (
            <>
              <span>New to FitTrack?</span>
              <button type="button" onClick={() => { setMode("signup"); setError(null); setNotice(null); }}>
                Create an account
              </button>
            </>
          ) : (
            <>
              <span>Already have an account?</span>
              <button type="button" onClick={() => { setMode("signin"); setError(null); setNotice(null); }}>
                Sign in
              </button>
            </>
          )}
        </div>
      </div>

      <p className="auth-footnote">Your health data is protected and only visible to your account.</p>
    </div>
  );
}


