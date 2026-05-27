"use client";

import { FormEvent, useEffect, useState } from "react";
import { useParams } from "next/navigation";

function apiBaseUrl() {
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (configured) return configured;
  if (typeof window !== "undefined") return `http://${window.location.hostname}:8081`;
  return "http://localhost:8081";
}

async function readMessage(response: Response) {
  try {
    const body = await response.json();
    return body.message || body.error || `Request failed with ${response.status}`;
  } catch {
    return response.ok ? "Signup request rejected." : `Request failed with ${response.status}`;
  }
}

export default function RejectSignupPage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const [requestLabel, setRequestLabel] = useState("Loading signup request...");
  const [reason, setReason] = useState("");
  const [message, setMessage] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    async function loadStatus() {
      if (!token) {
        setError("Missing signup rejection token.");
        return;
      }
      try {
        const response = await fetch(`${apiBaseUrl()}/api/public/signup-approvals/${token}`);
        const body = await response.json();
        if (!response.ok) throw new Error(body.message || body.error || "Unable to load signup request.");
        setRequestLabel(body.email ? `Reject request for ${body.email}` : body.message);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to load signup request.");
      }
    }

    loadStatus();
  }, [token]);

  async function reject(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!token) {
      setError("Missing signup rejection token.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl()}/api/public/signup-approvals/${token}/reject`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reason })
      });
      const nextMessage = await readMessage(response);
      if (!response.ok) throw new Error(nextMessage);
      setMessage(nextMessage);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to reject signup request.");
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <main className="mx-auto max-w-xl px-6 py-12">
      <form className="rounded-lg border border-slate-200 bg-white p-8 shadow-sm" onSubmit={reject}>
        <h1 className="text-2xl font-semibold text-slate-950">Reject Signup</h1>
        <p className="mt-3 text-slate-700">{requestLabel}</p>
        <label className="mt-5 block text-sm font-medium text-slate-700" htmlFor="reject-reason">
          Reason sent to applicant
        </label>
        <textarea
          id="reject-reason"
          value={reason}
          onChange={(event) => setReason(event.target.value)}
          className="mt-2 min-h-32 w-full rounded-md border border-slate-300 p-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
        />
        <button
          type="submit"
          disabled={submitting || Boolean(message)}
          className="mt-4 inline-flex h-11 w-full items-center justify-center rounded-md bg-red-600 px-5 font-semibold text-white disabled:opacity-50"
        >
          {submitting ? "Rejecting" : "Reject request"}
        </button>
        {message ? <p className="mt-4 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-emerald-800">{message}</p> : null}
        {error ? <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-red-700">{error}</p> : null}
      </form>
    </main>
  );
}
