"use client";

import { ChangeEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  ChevronRight,
  Download,
  FileText,
  Folder,
  HardDrive,
  LogIn,
  LogOut,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
  Upload
} from "lucide-react";

type User = {
  id: string;
  email: string;
  username: string;
  role?: string;
  quotaBytes?: number | null;
  usedBytes?: number;
};

type StorageInfo = {
  role: "ADMIN" | "USER";
  quotaBytes: number | null;
  usedBytes: number;
  isUnlimited: boolean;
};

type FolderRecord = {
  id: string;
  name: string;
  ownerId: string;
  parentId: string | null;
  createdAt: string;
  updatedAt: string;
  deletedAt: string | null;
};

type DriveItem = {
  itemType: "folder" | "file";
  id: string;
  name: string;
  ownerId: string;
  parentId: string | null;
  contentType: string | null;
  sizeBytes: number | null;
  checksumSha256: string | null;
  createdAt: string;
  updatedAt: string;
};

const keycloakBaseUrl = process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8080";
const realm = process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "owldrive";
const clientId = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "owl-drive-web";
const apiBaseUrl = process.env.NEXT_PUBLIC_API_BASE_URL ?? "http://localhost:8081";

class AuthExpiredError extends Error {
  constructor() {
    super("Session expired. Please log in again.");
  }
}

function base64Url(buffer: ArrayBuffer) {
  return btoa(String.fromCharCode(...new Uint8Array(buffer)))
    .replace(/\+/g, "-")
    .replace(/\//g, "_")
    .replace(/=+$/, "");
}

function rightRotate(value: number, amount: number) {
  return (value >>> amount) | (value << (32 - amount));
}

function sha256Fallback(value: string) {
  const bytes = new TextEncoder().encode(value);
  const words = new Uint32Array((((bytes.length + 8) >> 6) + 1) * 16);
  for (let i = 0; i < bytes.length; i += 1) {
    words[i >> 2] |= bytes[i] << (24 - (i % 4) * 8);
  }
  words[bytes.length >> 2] |= 0x80 << (24 - (bytes.length % 4) * 8);
  words[words.length - 1] = bytes.length * 8;

  let a = 0x6a09e667;
  let b = 0xbb67ae85;
  let c = 0x3c6ef372;
  let d = 0xa54ff53a;
  let e = 0x510e527f;
  let f = 0x9b05688c;
  let g = 0x1f83d9ab;
  let h = 0x5be0cd19;

  const k = [
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
    0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
    0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
    0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2
  ];

  for (let offset = 0; offset < words.length; offset += 16) {
    const w = new Uint32Array(64);
    for (let i = 0; i < 16; i += 1) w[i] = words[offset + i] >>> 0;
    for (let i = 16; i < 64; i += 1) {
      const s0 = rightRotate(w[i - 15], 7) ^ rightRotate(w[i - 15], 18) ^ (w[i - 15] >>> 3);
      const s1 = rightRotate(w[i - 2], 17) ^ rightRotate(w[i - 2], 19) ^ (w[i - 2] >>> 10);
      w[i] = (w[i - 16] + s0 + w[i - 7] + s1) >>> 0;
    }

    let aa = a;
    let bb = b;
    let cc = c;
    let dd = d;
    let ee = e;
    let ff = f;
    let gg = g;
    let hh = h;

    for (let i = 0; i < 64; i += 1) {
      const s1 = rightRotate(ee, 6) ^ rightRotate(ee, 11) ^ rightRotate(ee, 25);
      const ch = (ee & ff) ^ (~ee & gg);
      const temp1 = (hh + s1 + ch + k[i] + w[i]) >>> 0;
      const s0 = rightRotate(aa, 2) ^ rightRotate(aa, 13) ^ rightRotate(aa, 22);
      const maj = (aa & bb) ^ (aa & cc) ^ (bb & cc);
      const temp2 = (s0 + maj) >>> 0;

      hh = gg;
      gg = ff;
      ff = ee;
      ee = (dd + temp1) >>> 0;
      dd = cc;
      cc = bb;
      bb = aa;
      aa = (temp1 + temp2) >>> 0;
    }

    a = (a + aa) >>> 0;
    b = (b + bb) >>> 0;
    c = (c + cc) >>> 0;
    d = (d + dd) >>> 0;
    e = (e + ee) >>> 0;
    f = (f + ff) >>> 0;
    g = (g + gg) >>> 0;
    h = (h + hh) >>> 0;
  }

  const output = new Uint8Array(32);
  [a, b, c, d, e, f, g, h].forEach((value, index) => {
    output[index * 4] = (value >>> 24) & 0xff;
    output[index * 4 + 1] = (value >>> 16) & 0xff;
    output[index * 4 + 2] = (value >>> 8) & 0xff;
    output[index * 4 + 3] = value & 0xff;
  });
  return output.buffer;
}

async function sha256(value: string) {
  if (globalThis.crypto?.subtle?.digest) {
    return base64Url(await globalThis.crypto.subtle.digest("SHA-256", new TextEncoder().encode(value)));
  }
  return base64Url(sha256Fallback(value));
}

function randomString() {
  const bytes = new Uint8Array(32);
  (globalThis.crypto ?? window.crypto).getRandomValues(bytes);
  return base64Url(bytes.buffer as ArrayBuffer);
}

async function readJson<T>(response: Response): Promise<T> {
  if (response.ok) return response.json();
  if (response.status === 401) throw new AuthExpiredError();
  let message = `Request failed with ${response.status}`;
  try {
    const body = await response.json();
    message = body.message || body.error || message;
  } catch {
    // Keep the status-based message.
  }
  throw new Error(message);
}

function itemToFolder(item: DriveItem): FolderRecord {
  return {
    id: item.id,
    name: item.name,
    ownerId: item.ownerId,
    parentId: item.parentId,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt,
    deletedAt: null
  };
}

function formatBytes(value: number | null) {
  if (value === null) return "";
  if (value < 1024) return `${value} B`;
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`;
  return `${(value / 1024 / 1024).toFixed(1)} MB`;
}

function formatStorage(info: StorageInfo | null) {
  if (!info) return "Loading storage...";
  if (info.isUnlimited) return "Unlimited storage";
  return `${formatBytes(info.usedBytes)} of ${formatBytes(info.quotaBytes)} used`;
}

function formatDate(value: string) {
  return new Intl.DateTimeFormat(undefined, { dateStyle: "medium", timeStyle: "short" }).format(new Date(value));
}

export default function Home() {
  const [token, setToken] = useState<string | null>(null);
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [user, setUser] = useState<User | null>(null);
  const [rootFolder, setRootFolder] = useState<FolderRecord | null>(null);
  const [storageInfo, setStorageInfo] = useState<StorageInfo | null>(null);
  const [currentFolder, setCurrentFolder] = useState<FolderRecord | null>(null);
  const [children, setChildren] = useState<DriveItem[]>([]);
  const [breadcrumbs, setBreadcrumbs] = useState<FolderRecord[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);

  const redirectUri = useMemo(() => {
    if (typeof window === "undefined") return "";
    return window.location.origin;
  }, []);

  const jsonHeaders = useMemo(() => {
    return token ? { Authorization: `Bearer ${token}`, "Content-Type": "application/json" } : undefined;
  }, [token]);

  const bearerHeaders = useMemo(() => {
    return token ? { Authorization: `Bearer ${token}` } : undefined;
  }, [token]);

  useEffect(() => {
    const storedToken = localStorage.getItem("owl_access_token");
    if (storedToken) setToken(storedToken);
  }, []);

  function clearSession(message = "") {
    localStorage.removeItem("owl_access_token");
    localStorage.removeItem("owl_id_token");
    sessionStorage.removeItem("owl_pkce_verifier");
    setToken(null);
    setAuthMode("login");
    setUser(null);
    setStorageInfo(null);
    setRootFolder(null);
    setCurrentFolder(null);
    setBreadcrumbs([]);
    setChildren([]);
    setLoading(false);
    setUploading(false);
    setError(message);
  }

  function handleRequestError(err: unknown, fallback: string) {
    if (err instanceof AuthExpiredError) {
      clearSession(err.message);
      return;
    }
    setError(err instanceof Error ? err.message : fallback);
  }

  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    if (!code) return;

    const verifier = sessionStorage.getItem("owl_pkce_verifier");
    if (!verifier) {
      setError("Missing login verifier. Please try logging in again.");
      return;
    }
    const authCode = code;
    const codeVerifier = verifier;

    async function exchangeCode() {
      setLoading(true);
      setError("");
      try {
        const body = new URLSearchParams({
          grant_type: "authorization_code",
          client_id: clientId,
          code: authCode,
          redirect_uri: window.location.origin,
          code_verifier: codeVerifier
        });
        const response = await fetch(`${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/token`, {
          method: "POST",
          headers: { "Content-Type": "application/x-www-form-urlencoded" },
          body
        });
        if (!response.ok) throw new Error("Token exchange failed");
        const data = await response.json();
        localStorage.setItem("owl_access_token", data.access_token);
        if (data.id_token) localStorage.setItem("owl_id_token", data.id_token);
        setToken(data.access_token);
        window.history.replaceState({}, document.title, window.location.origin);
      } catch (err) {
        setError(err instanceof Error ? err.message : "Login failed");
      } finally {
        setLoading(false);
      }
    }

    exchangeCode();
  }, []);

  const loadChildren = useCallback(
    async (folder: FolderRecord) => {
      if (!jsonHeaders) return;
      setLoading(true);
      setError("");
      try {
        const response = await fetch(`${apiBaseUrl}/api/folders/${folder.id}/children`, { headers: jsonHeaders });
        setChildren(await readJson<DriveItem[]>(response));
      } catch (err) {
        handleRequestError(err, "Unable to load folder contents");
      } finally {
        setLoading(false);
      }
    },
    [jsonHeaders]
  );

  useEffect(() => {
    if (!token || !jsonHeaders) return;

    async function loadDrive() {
      setLoading(true);
      setError("");
      try {
        const meResponse = await fetch(`${apiBaseUrl}/api/me`, { headers: jsonHeaders });
        const me = await readJson<User>(meResponse);

        const storageResponse = await fetch(`${apiBaseUrl}/api/me/storage`, { headers: jsonHeaders });
        const storage = await readJson<StorageInfo>(storageResponse);

        const rootResponse = await fetch(`${apiBaseUrl}/api/drive/root`, { headers: jsonHeaders });
        const root = await readJson<FolderRecord>(rootResponse);

        const childResponse = await fetch(`${apiBaseUrl}/api/folders/${root.id}/children`, { headers: jsonHeaders });
        const rootChildren = await readJson<DriveItem[]>(childResponse);

        setUser(me);
        setStorageInfo(storage);
        setRootFolder(root);
        setCurrentFolder(root);
        setBreadcrumbs([root]);
        setChildren(rootChildren);
      } catch (err) {
        handleRequestError(err, "Unable to load drive");
      } finally {
        setLoading(false);
      }
    }

    loadDrive();
  }, [token, jsonHeaders]);

  async function login() {
    const verifier = randomString();
    const challenge = await sha256(verifier);
    sessionStorage.setItem("owl_pkce_verifier", verifier);
    const params = new URLSearchParams({
      client_id: clientId,
      response_type: "code",
      scope: "openid email profile",
      redirect_uri: redirectUri,
      code_challenge: challenge,
      code_challenge_method: "S256"
    });
    window.location.href = `${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/auth?${params}`;
  }

  async function registerAccount() {
    const verifier = randomString();
    const challenge = await sha256(verifier);
    sessionStorage.setItem("owl_pkce_verifier", verifier);
    const params = new URLSearchParams({
      client_id: clientId,
      response_type: "code",
      scope: "openid email profile",
      redirect_uri: redirectUri,
      code_challenge: challenge,
      code_challenge_method: "S256"
    });
    window.location.href = `${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/registrations?${params}`;
  }

  function logout() {
    const idToken = localStorage.getItem("owl_id_token");
    clearSession();
    const params = new URLSearchParams({
      client_id: clientId,
      post_logout_redirect_uri: window.location.origin
    });
    if (idToken) params.set("id_token_hint", idToken);
    window.location.href = `${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/logout?${params}`;
  }

  async function openFolder(folder: FolderRecord) {
    setCurrentFolder(folder);
    setBreadcrumbs((items) => {
      const existingIndex = items.findIndex((item) => item.id === folder.id);
      if (existingIndex >= 0) return items.slice(0, existingIndex + 1);
      return [...items, folder];
    });
    await loadChildren(folder);
  }

  async function refreshCurrentFolder() {
    if (currentFolder) await loadChildren(currentFolder);
  }

  async function createFolder() {
    if (!jsonHeaders || !currentFolder) return;
    const name = window.prompt("Folder name");
    if (name === null) return;
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/folders`, {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({ name, parentId: currentFolder.id })
      });
      await readJson<FolderRecord>(response);
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to create folder");
      setLoading(false);
    }
  }

  async function renameFolder(folder: FolderRecord) {
    if (!jsonHeaders || !currentFolder) return;
    const name = window.prompt("New folder name", folder.name);
    if (name === null) return;
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/folders/${folder.id}`, {
        method: "PATCH",
        headers: jsonHeaders,
        body: JSON.stringify({ name })
      });
      const updated = await readJson<FolderRecord>(response);
      if (currentFolder.id === updated.id) setCurrentFolder(updated);
      setBreadcrumbs((items) => items.map((item) => (item.id === updated.id ? updated : item)));
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to rename folder");
      setLoading(false);
    }
  }

  async function deleteFolder(folder: FolderRecord) {
    if (!jsonHeaders || !currentFolder) return;
    const confirmed = window.confirm(`Delete "${folder.name}"?`);
    if (!confirmed) return;
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/folders/${folder.id}`, {
        method: "DELETE",
        headers: jsonHeaders
      });
      if (!response.ok) await readJson(response);
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to delete folder");
      setLoading(false);
    }
  }

  async function uploadFile(event: ChangeEvent<HTMLInputElement>) {
    if (!bearerHeaders || !currentFolder) return;
    const selectedFile = event.target.files?.[0];
    event.target.value = "";
    if (!selectedFile) return;

    const form = new FormData();
    form.append("parentFolderId", currentFolder.id);
    form.append("file", selectedFile);
    setUploading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/files/upload`, {
        method: "POST",
        headers: bearerHeaders,
        body: form
      });
      await readJson(response);
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to upload file");
    } finally {
      setUploading(false);
    }
  }

  async function downloadFile(item: DriveItem) {
    if (!bearerHeaders) return;
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/files/${item.id}/download`, { headers: bearerHeaders });
      if (!response.ok) {
        await readJson(response);
        return;
      }
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = item.name;
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(url);
    } catch (err) {
      handleRequestError(err, "Unable to download file");
    }
  }

  async function deleteFile(item: DriveItem) {
    if (!jsonHeaders || !currentFolder) return;
    const confirmed = window.confirm(`Delete "${item.name}"?`);
    if (!confirmed) return;
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/files/${item.id}`, {
        method: "DELETE",
        headers: jsonHeaders
      });
      if (!response.ok) await readJson(response);
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to delete file");
      setLoading(false);
    }
  }

  const parentFolder = breadcrumbs.length > 1 ? breadcrumbs[breadcrumbs.length - 2] : null;

  return (
    <main className="min-h-screen bg-slate-50 text-slate-900">
      <header className="flex h-16 items-center justify-between border-b border-slate-200 bg-white px-5">
        <div className="flex items-center gap-3">
          <HardDrive className="h-6 w-6 text-blue-600" />
          <div className="text-xl font-semibold">OWL Drive</div>
        </div>
        {token ? (
          <div className="flex items-center gap-3">
            <div className="hidden text-sm text-slate-600 sm:block">{user?.email || user?.username}</div>
            <button onClick={logout} className="inline-flex h-10 items-center gap-2 rounded-md border border-slate-300 bg-white px-4 font-semibold">
              <LogOut className="h-4 w-4" />
              Log out
            </button>
          </div>
        ) : null}
      </header>

      {!token ? (
        <section className="mx-auto max-w-3xl px-6 py-12">
          <div className="rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
            <h1 className="text-2xl font-semibold">Sign in to OWL Drive</h1>
            <p className="mt-2 text-slate-600">Use Keycloak OpenID Connect to access your drive.</p>

            <div className="mt-6 grid grid-cols-2 rounded-md border border-slate-200 p-1">
              <button
                type="button"
                onClick={() => {
                  setAuthMode("login");
                  setError("");
                }}
                className={`rounded-md px-3 py-2 text-sm font-semibold ${
                  authMode === "login" ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"
                }`}
              >
                Sign in
              </button>
              <button
                type="button"
                onClick={() => {
                  setAuthMode("register");
                  setError("");
                }}
                className={`rounded-md px-3 py-2 text-sm font-semibold ${
                  authMode === "register" ? "bg-slate-900 text-white" : "text-slate-600 hover:bg-slate-100"
                }`}
              >
                Create account
              </button>
            </div>

            {authMode === "login" ? (
              <>
                <button onClick={() => login()} className="mt-6 inline-flex h-11 items-center gap-2 rounded-md bg-blue-600 px-5 font-semibold text-white">
                  <LogIn className="h-4 w-4" />
                  Login with Keycloak
                </button>
                <p className="mt-4 text-sm text-slate-600">
                  New users can create an account here. The email becomes the username.
                </p>
              </>
            ) : (
              <div className="mt-6 space-y-4">
                <p className="text-sm text-slate-600">
                  Keycloak will ask for the email and password during registration. The email is used as the username.
                </p>
                <div className="flex items-center gap-3">
                  <button
                    type="button"
                    onClick={() => registerAccount()}
                    className="inline-flex h-11 items-center gap-2 rounded-md bg-blue-600 px-5 font-semibold text-white"
                  >
                    <Plus className="h-4 w-4" />
                    Create account
                  </button>
                  <button
                    type="button"
                  onClick={() => {
                    setAuthMode("login");
                    setError("");
                  }}
                  className="inline-flex h-11 items-center rounded-md border border-slate-300 bg-white px-4 font-semibold"
                >
                    Back to sign in
                  </button>
                </div>
              </div>
            )}
          </div>
          {error ? <p className="mt-5 rounded-md border border-red-200 bg-red-50 p-3 text-red-700">{error}</p> : null}
        </section>
      ) : (
        <div className="flex min-h-[calc(100vh-4rem)]">
          <aside className="hidden w-64 border-r border-slate-200 bg-white px-4 py-5 md:block">
            <button
              onClick={() => rootFolder && openFolder(rootFolder)}
              className="flex w-full items-center gap-3 rounded-md bg-blue-50 px-3 py-2 text-left font-semibold text-blue-700"
            >
              <HardDrive className="h-4 w-4" />
              My Drive
            </button>
            <div className="mt-5 rounded-md border border-slate-200 bg-slate-50 p-3 text-sm">
              <div className="font-semibold text-slate-700">{storageInfo?.role ?? "USER"}</div>
              <div className="mt-1 text-slate-600">{formatStorage(storageInfo)}</div>
            </div>
          </aside>

          <section className="flex-1 px-5 py-5">
            <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
              <div className="flex min-w-0 items-center gap-2">
                <button
                  onClick={() => parentFolder && openFolder(parentFolder)}
                  disabled={!parentFolder || loading}
                  className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-slate-300 bg-white disabled:cursor-not-allowed disabled:opacity-40"
                  title="Back"
                >
                  <ArrowLeft className="h-4 w-4" />
                </button>
                <nav className="flex min-w-0 flex-wrap items-center gap-1 text-sm">
                  {breadcrumbs.map((item, index) => (
                    <span key={item.id} className="flex items-center gap-1">
                      <button
                        onClick={() => openFolder(item)}
                        className="max-w-48 truncate rounded-md px-2 py-1 font-medium text-slate-700 hover:bg-slate-100"
                      >
                        {item.name}
                      </button>
                      {index < breadcrumbs.length - 1 ? <ChevronRight className="h-4 w-4 text-slate-400" /> : null}
                    </span>
                  ))}
                </nav>
              </div>

              <div className="flex items-center gap-2">
                <button
                  onClick={refreshCurrentFolder}
                  disabled={loading || uploading}
                  className="inline-flex h-10 w-10 items-center justify-center rounded-md border border-slate-300 bg-white disabled:opacity-50"
                  title="Refresh"
                >
                  <RefreshCw className={`h-4 w-4 ${loading ? "animate-spin" : ""}`} />
                </button>
                <input ref={fileInputRef} type="file" className="hidden" onChange={uploadFile} />
                <button
                  onClick={() => fileInputRef.current?.click()}
                  disabled={!currentFolder || loading || uploading}
                  className="inline-flex h-10 items-center gap-2 rounded-md border border-slate-300 bg-white px-4 font-semibold disabled:opacity-50"
                >
                  <Upload className="h-4 w-4" />
                  {uploading ? "Uploading" : "Upload File"}
                </button>
                <button
                  onClick={createFolder}
                  disabled={!currentFolder || loading || uploading}
                  className="inline-flex h-10 items-center gap-2 rounded-md bg-blue-600 px-4 font-semibold text-white disabled:opacity-50"
                >
                  <Plus className="h-4 w-4" />
                  New Folder
                </button>
              </div>
            </div>

            {error ? <p className="mb-4 rounded-md border border-red-200 bg-red-50 p-3 text-red-700">{error}</p> : null}

            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
              <div className="grid grid-cols-[minmax(0,1fr)_120px_160px_auto] gap-3 border-b border-slate-200 bg-slate-50 px-4 py-3 text-sm font-semibold text-slate-600">
                <div>Name</div>
                <div>Size</div>
                <div>Modified</div>
                <div>Actions</div>
              </div>
              {children.length === 0 ? (
                <div className="px-4 py-12 text-center text-slate-500">{loading || uploading ? "Loading..." : "No files or folders"}</div>
              ) : (
                children.map((item) => (
                  <div
                    key={`${item.itemType}-${item.id}`}
                    className="grid grid-cols-[minmax(0,1fr)_120px_160px_auto] items-center gap-3 border-b border-slate-100 px-4 py-2 text-sm last:border-b-0 hover:bg-slate-50"
                  >
                    {item.itemType === "folder" ? (
                      <button onClick={() => openFolder(itemToFolder(item))} className="flex min-w-0 items-center gap-3 rounded-md py-2 text-left">
                        <Folder className="h-5 w-5 shrink-0 text-blue-600" />
                        <span className="truncate font-medium">{item.name}</span>
                      </button>
                    ) : (
                      <div className="flex min-w-0 items-center gap-3 py-2">
                        <FileText className="h-5 w-5 shrink-0 text-slate-500" />
                        <div className="min-w-0">
                          <div className="truncate font-medium">{item.name}</div>
                          <div className="truncate text-xs text-slate-500">{item.contentType || "application/octet-stream"}</div>
                        </div>
                      </div>
                    )}
                    <div className="text-slate-600">{item.itemType === "file" ? formatBytes(item.sizeBytes) : ""}</div>
                    <div className="truncate text-slate-600">{formatDate(item.updatedAt)}</div>
                    <div className="flex items-center justify-end gap-1">
                      {item.itemType === "folder" ? (
                        <>
                          <button
                            onClick={() => renameFolder(itemToFolder(item))}
                            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-transparent hover:border-slate-200 hover:bg-white"
                            title="Rename"
                          >
                            <Pencil className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => deleteFolder(itemToFolder(item))}
                            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-transparent text-red-600 hover:border-red-200 hover:bg-red-50"
                            title="Delete"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </>
                      ) : (
                        <>
                          <button
                            onClick={() => downloadFile(item)}
                            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-transparent hover:border-slate-200 hover:bg-white"
                            title="Download"
                          >
                            <Download className="h-4 w-4" />
                          </button>
                          <button
                            onClick={() => deleteFile(item)}
                            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-transparent text-red-600 hover:border-red-200 hover:bg-red-50"
                            title="Delete"
                          >
                            <Trash2 className="h-4 w-4" />
                          </button>
                        </>
                      )}
                    </div>
                  </div>
                ))
              )}
            </div>
          </section>
        </div>
      )}
    </main>
  );
}
