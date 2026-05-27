"use client";

import { useEffect, useState } from "react";
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
    return response.ok ? "Signup request approved." : `Request failed with ${response.status}`;
  }
}

export default function ApproveSignupPage() {
  const params = useParams<{ token: string }>();
  const token = params.token;
  const [message, setMessage] = useState("Approving signup request...");
  const [error, setError] = useState("");

  useEffect(() => {
    async function approve() {
      if (!token) {
        setError("Missing signup approval token.");
        return;
      }
      try {
        const response = await fetch(`${apiBaseUrl()}/api/public/signup-approvals/${token}/approve`, { method: "POST" });
        const nextMessage = await readMessage(response);
        if (!response.ok) throw new Error(nextMessage);
        setMessage(nextMessage);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Unable to approve signup request.");
      }
    }

    approve();
  }, [token]);

  return (
    <main className="mx-auto max-w-xl px-6 py-12">
      <div className="rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
        <h1 className="text-2xl font-semibold text-slate-950">Signup Approval</h1>
        {error ? <p className="mt-4 rounded-md border border-red-200 bg-red-50 p-3 text-red-700">{error}</p> : <p className="mt-4 text-slate-700">{message}</p>}
      </div>
    </main>
  );
}
