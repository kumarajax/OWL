"use client";

import { ChangeEvent, FormEvent, useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  ArrowLeft,
  ChevronRight,
  Copy,
  Download,
  FileText,
  Folder,
  HardDrive,
  KeyRound,
  Linkedin,
  LogOut,
  Pencil,
  Plus,
  RefreshCw,
  Share2,
  Trash2,
  Upload,
  UserCircle
} from "lucide-react";

type User = {
  id: string;
  email: string;
  username: string;
  role?: string;
  quotaBytes?: number | null;
  usedBytes?: number;
  deactivatedAt?: string | null;
};

type StorageInfo = {
  role: "ADMIN" | "USER";
  quotaBytes: number | null;
  usedBytes: number;
  isUnlimited: boolean;
};

type RegistrationStatus = {
  activeUsers: number;
  maxUsers: number;
  registrationAvailable: boolean;
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

type FileShare = {
  id: string;
  fileId: string;
  ownerId: string;
  shareUrl: string | null;
  expiresAt: string | null;
  revokedAt: string | null;
  downloadCount: number;
  lastDownloadedAt: string | null;
  createdAt: string;
};

const realm = process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "owldrive";
const clientId = process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "owl-drive-web";
const linkedInProfileUrl = "https://www.linkedin.com/in/ajaykumarpandit/";

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

const dateFormatter = new Intl.DateTimeFormat("en-US", {
  dateStyle: "medium",
  timeStyle: "short",
  timeZone: "UTC"
});

function formatStorage(info: StorageInfo | null) {
  if (!info) return "Loading storage...";
  if (info.isUnlimited) return "Unlimited storage";
  return `${formatBytes(info.usedBytes)} of ${formatBytes(info.quotaBytes)} used`;
}

function formatDate(value: string) {
  return dateFormatter.format(new Date(value));
}

function getBrowserOrigin() {
  return typeof window === "undefined" ? "" : window.location.origin;
}

function resolveServiceBaseUrl(envValue: string | undefined, port: number) {
  if (envValue) {
    try {
      const parsed = new URL(envValue);
      if (parsed.hostname !== "localhost" && parsed.hostname !== "127.0.0.1") {
        return envValue;
      }
    } catch {
      return envValue;
    }
  }

  if (typeof window !== "undefined") {
    return `http://${window.location.hostname}:${port}`;
  }

  return `http://localhost:${port}`;
}

export default function Home() {
  const [token, setToken] = useState<string | null>(null);
  const [authMode, setAuthMode] = useState<"login" | "register">("login");
  const [loginUsername, setLoginUsername] = useState("");
  const [loginPassword, setLoginPassword] = useState("");
  const [loginSubmitting, setLoginSubmitting] = useState(false);
  const [user, setUser] = useState<User | null>(null);
  const [rootFolder, setRootFolder] = useState<FolderRecord | null>(null);
  const [storageInfo, setStorageInfo] = useState<StorageInfo | null>(null);
  const [currentFolder, setCurrentFolder] = useState<FolderRecord | null>(null);
  const [children, setChildren] = useState<DriveItem[]>([]);
  const [breadcrumbs, setBreadcrumbs] = useState<FolderRecord[]>([]);
  const [selectedFileIds, setSelectedFileIds] = useState<Set<string>>(new Set());
  const [batchMoveTargetId, setBatchMoveTargetId] = useState("");
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);
  const [uploading, setUploading] = useState(false);
  const [deactivationDialogOpen, setDeactivationDialogOpen] = useState(false);
  const [deactivationConfirmation, setDeactivationConfirmation] = useState("");
  const [deactivating, setDeactivating] = useState(false);
  const [activating, setActivating] = useState(false);
  const [passwordDialogOpen, setPasswordDialogOpen] = useState(false);
  const [currentPassword, setCurrentPassword] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [confirmNewPassword, setConfirmNewPassword] = useState("");
  const [changingPassword, setChangingPassword] = useState(false);
  const [registrationStatus, setRegistrationStatus] = useState<RegistrationStatus | null>(null);
  const [shareDialogUrl, setShareDialogUrl] = useState("");
  const [shareCopied, setShareCopied] = useState(false);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const shareLinkInputRef = useRef<HTMLInputElement | null>(null);
  const keycloakBaseUrl = resolveServiceBaseUrl(process.env.NEXT_PUBLIC_KEYCLOAK_URL, 8080);
  const apiBaseUrl = resolveServiceBaseUrl(process.env.NEXT_PUBLIC_API_BASE_URL, 8081);

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

  useEffect(() => {
    async function loadRegistrationStatus() {
      try {
        const response = await fetch(`${apiBaseUrl}/api/public/registration`);
        setRegistrationStatus(await readJson<RegistrationStatus>(response));
      } catch {
        setRegistrationStatus(null);
      }
    }

    loadRegistrationStatus();
  }, [apiBaseUrl]);

  useEffect(() => {
    if (registrationStatus && !registrationStatus.registrationAvailable && authMode === "register") {
      setAuthMode("login");
    }
  }, [registrationStatus, authMode]);

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
    setSelectedFileIds(new Set());
    setBatchMoveTargetId("");
    setLoading(false);
    setUploading(false);
    setDeactivationDialogOpen(false);
    setDeactivationConfirmation("");
    setDeactivating(false);
    setActivating(false);
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
        setSelectedFileIds(new Set());
        setBatchMoveTargetId("");
      } catch (err) {
        handleRequestError(err, "Unable to load folder contents");
      } finally {
        setLoading(false);
      }
    },
    [jsonHeaders]
  );

  async function loadActiveDrive(headers: Record<string, string>) {
    const storage = await loadStorage(headers);

    const rootResponse = await fetch(`${apiBaseUrl}/api/drive/root`, { headers });
    const root = await readJson<FolderRecord>(rootResponse);

    const childResponse = await fetch(`${apiBaseUrl}/api/folders/${root.id}/children`, { headers });
    const rootChildren = await readJson<DriveItem[]>(childResponse);

    setStorageInfo(storage);
    setRootFolder(root);
    setCurrentFolder(root);
    setBreadcrumbs([root]);
    setChildren(rootChildren);
    setSelectedFileIds(new Set());
    setBatchMoveTargetId("");
  }

  async function loadStorage(headers: Record<string, string>) {
    const storageResponse = await fetch(`${apiBaseUrl}/api/me/storage`, { headers });
    return readJson<StorageInfo>(storageResponse);
  }

  async function refreshStorage() {
    if (!jsonHeaders) return;
    setStorageInfo(await loadStorage(jsonHeaders));
  }

  useEffect(() => {
    if (!token || !jsonHeaders) return;
    const headers = jsonHeaders;

    async function loadDrive() {
      setLoading(true);
      setError("");
      try {
        const meResponse = await fetch(`${apiBaseUrl}/api/me`, { headers });
        const me = await readJson<User>(meResponse);
        setUser(me);
        if (me.deactivatedAt) {
          setStorageInfo(null);
          setRootFolder(null);
          setCurrentFolder(null);
          setBreadcrumbs([]);
          setChildren([]);
          return;
        }
        await loadActiveDrive(headers);
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
    const redirectUri = getBrowserOrigin();
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
    if (registrationStatus && !registrationStatus.registrationAvailable) {
      setError("Max usage reached.");
      return;
    }
    const verifier = randomString();
    const challenge = await sha256(verifier);
    sessionStorage.setItem("owl_pkce_verifier", verifier);
    const redirectUri = getBrowserOrigin();
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

  async function loginWithPassword(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!loginUsername.trim() || !loginPassword) {
      setError("Username and password are required.");
      return;
    }

    setLoginSubmitting(true);
    setLoading(true);
    setError("");
    try {
      const body = new URLSearchParams({
        grant_type: "password",
        client_id: clientId,
        username: loginUsername.trim(),
        password: loginPassword,
        scope: "openid email profile"
      });
      const response = await fetch(`${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/token`, {
        method: "POST",
        headers: { "Content-Type": "application/x-www-form-urlencoded" },
        body
      });
      if (!response.ok) {
        let message = "Invalid username or password.";
        try {
          const data = await response.json();
          message = data.error_description || data.error || message;
        } catch {
          // Keep the default login error.
        }
        throw new Error(message);
      }
      const data = await response.json();
      localStorage.setItem("owl_access_token", data.access_token);
      if (data.id_token) localStorage.setItem("owl_id_token", data.id_token);
      setLoginPassword("");
      setToken(data.access_token);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Login failed.");
    } finally {
      setLoginSubmitting(false);
      setLoading(false);
    }
  }

  function logout() {
    const idToken = localStorage.getItem("owl_id_token");
    clearSession();
    const origin = getBrowserOrigin();
    const params = new URLSearchParams({
      client_id: clientId,
      post_logout_redirect_uri: origin
    });
    if (idToken) params.set("id_token_hint", idToken);
    window.location.href = `${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/logout?${params}`;
  }

  function openDeactivateAccountDialog() {
    setError("");
    setDeactivationConfirmation("");
    setDeactivationDialogOpen(true);
  }

  function openPasswordDialog() {
    setError("");
    setCurrentPassword("");
    setNewPassword("");
    setConfirmNewPassword("");
    setPasswordDialogOpen(true);
  }

  async function changePassword() {
    if (!jsonHeaders) return;
    if (!currentPassword || !newPassword || !confirmNewPassword) {
      setError("All password fields are required.");
      return;
    }
    if (newPassword.length < 8) {
      setError("New password must be at least 8 characters.");
      return;
    }
    if (newPassword !== confirmNewPassword) {
      setError("New password and confirmation do not match.");
      return;
    }
    setChangingPassword(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/me/password`, {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({ currentPassword, newPassword })
      });
      if (!response.ok) await readJson(response);
      setPasswordDialogOpen(false);
      setCurrentPassword("");
      setNewPassword("");
      setConfirmNewPassword("");
      setError("Password updated.");
    } catch (err) {
      handleRequestError(err, "Unable to update password");
    } finally {
      setChangingPassword(false);
    }
  }

  async function activateAccount() {
    if (!jsonHeaders) return;
    setActivating(true);
    setLoading(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/me/activate`, {
        method: "POST",
        headers: jsonHeaders
      });
      const activatedUser = await readJson<User>(response);
      setUser(activatedUser);
      await loadActiveDrive(jsonHeaders);
      setError("Account activated. Your default quota is restored.");
    } catch (err) {
      handleRequestError(err, "Unable to activate account");
    } finally {
      setActivating(false);
      setLoading(false);
    }
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
      await refreshStorage();
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
      await refreshStorage();
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

  async function shareFile(item: DriveItem) {
    if (!jsonHeaders) return;
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/files/${item.id}/shares`, {
        method: "POST",
        headers: jsonHeaders,
        body: JSON.stringify({})
      });
      const share = await readJson<FileShare>(response);
      if (!share.shareUrl) {
        throw new Error("Share link was not returned.");
      }
      setShareDialogUrl(share.shareUrl);
      setShareCopied(false);
    } catch (err) {
      handleRequestError(err, "Unable to create share link");
    }
  }

  async function copyShareLink() {
    if (!shareDialogUrl) return;
    let copied = false;
    try {
      if (navigator.clipboard?.writeText && window.isSecureContext) {
        await navigator.clipboard.writeText(shareDialogUrl);
        copied = true;
      }
    } catch {
      copied = false;
    }
    if (!copied && shareLinkInputRef.current) {
      shareLinkInputRef.current.focus();
      shareLinkInputRef.current.select();
      copied = document.execCommand("copy");
    }
    if (copied) {
      setShareCopied(true);
      window.setTimeout(() => setShareCopied(false), 1800);
      return;
    }
    setShareCopied(false);
  }

  function toggleFileSelection(fileId: string) {
    setSelectedFileIds((current) => {
      const next = new Set(current);
      if (next.has(fileId)) {
        next.delete(fileId);
      } else {
        next.add(fileId);
      }
      return next;
    });
  }

  function toggleAllFiles() {
    const fileIds = children.filter((item) => item.itemType === "file").map((item) => item.id);
    setSelectedFileIds((current) => {
      if (fileIds.length > 0 && fileIds.every((id) => current.has(id))) {
        return new Set();
      }
      return new Set(fileIds);
    });
  }

  async function downloadSelectedFiles() {
    const selectedFiles = children.filter((item) => item.itemType === "file" && selectedFileIds.has(item.id));
    for (const file of selectedFiles) {
      await downloadFile(file);
    }
  }

  async function deleteSelectedFiles() {
    if (!jsonHeaders || !currentFolder || selectedFileIds.size === 0) return;
    const confirmed = window.confirm(`Delete ${selectedFileIds.size} selected file${selectedFileIds.size === 1 ? "" : "s"}?`);
    if (!confirmed) return;
    setLoading(true);
    setError("");
    try {
      for (const fileId of selectedFileIds) {
        const response = await fetch(`${apiBaseUrl}/api/files/${fileId}`, {
          method: "DELETE",
          headers: jsonHeaders
        });
        if (!response.ok) await readJson(response);
      }
      setSelectedFileIds(new Set());
      await loadChildren(currentFolder);
      await refreshStorage();
    } catch (err) {
      handleRequestError(err, "Unable to delete selected files");
      setLoading(false);
    }
  }

  async function moveSelectedFiles() {
    if (!jsonHeaders || !currentFolder || !batchMoveTargetId || selectedFileIds.size === 0) return;
    setLoading(true);
    setError("");
    try {
      for (const fileId of selectedFileIds) {
        const response = await fetch(`${apiBaseUrl}/api/files/${fileId}`, {
          method: "PATCH",
          headers: jsonHeaders,
          body: JSON.stringify({ parentFolderId: batchMoveTargetId })
        });
        await readJson<DriveItem>(response);
      }
      setSelectedFileIds(new Set());
      setBatchMoveTargetId("");
      await loadChildren(currentFolder);
    } catch (err) {
      handleRequestError(err, "Unable to move selected files");
      setLoading(false);
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
      await refreshStorage();
    } catch (err) {
      handleRequestError(err, "Unable to delete file");
      setLoading(false);
    }
  }

  async function deactivateAccount() {
    if (!jsonHeaders || !user) return;
    const typedEmail = deactivationConfirmation.trim();
    if (typedEmail.toLowerCase() !== user.email.toLowerCase()) {
      setError("Confirmation did not match your email.");
      return;
    }

    setLoading(true);
    setDeactivating(true);
    setError("");
    try {
      const response = await fetch(`${apiBaseUrl}/api/me`, {
        method: "DELETE",
        headers: jsonHeaders,
        body: JSON.stringify({ confirmation: typedEmail })
      });
      if (!response.ok) await readJson(response);
      const idToken = localStorage.getItem("owl_id_token");
      window.alert("Account deactivated. Your OWL Drive files were deleted.");
      clearSession();
      const params = new URLSearchParams({
        client_id: clientId,
        post_logout_redirect_uri: getBrowserOrigin()
      });
      if (idToken) params.set("id_token_hint", idToken);
      window.location.href = `${keycloakBaseUrl}/realms/${realm}/protocol/openid-connect/logout?${params}`;
    } catch (err) {
      handleRequestError(err, "Unable to deactivate account");
      setLoading(false);
      setDeactivating(false);
    }
  }

  const parentFolder = breadcrumbs.length > 1 ? breadcrumbs[breadcrumbs.length - 2] : null;
  const accountDeactivated = Boolean(user?.deactivatedAt);
  const registrationFull = registrationStatus ? !registrationStatus.registrationAvailable : false;
  const visibleFileIds = children.filter((item) => item.itemType === "file").map((item) => item.id);
  const allVisibleFilesSelected = visibleFileIds.length > 0 && visibleFileIds.every((id) => selectedFileIds.has(id));
  const moveTargets = [
    ...(parentFolder ? [{ id: parentFolder.id, name: `Parent: ${parentFolder.name}` }] : []),
    ...children.filter((item) => item.itemType === "folder").map((item) => ({ id: item.id, name: item.name }))
  ];

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

      {deactivationDialogOpen && user ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4">
          <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-5 shadow-xl">
            <div className="flex items-start gap-3">
              <div className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-red-50 text-red-700">
                <Trash2 className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <h2 className="text-lg font-semibold text-slate-900">Deactivate account</h2>
                <p className="mt-1 text-sm text-slate-600">
                  This will permanently delete all OWL Drive files for {user.email}.
                </p>
              </div>
            </div>

            <label className="mt-5 block text-sm font-medium text-slate-700" htmlFor="deactivate-confirmation">
              Type your email to confirm
            </label>
            <input
              id="deactivate-confirmation"
              value={deactivationConfirmation}
              onChange={(event) => setDeactivationConfirmation(event.target.value)}
              disabled={deactivating}
              className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 text-sm outline-none focus:border-red-400 focus:ring-2 focus:ring-red-100 disabled:bg-slate-100"
              autoFocus
            />

            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => {
                  setDeactivationDialogOpen(false);
                  setDeactivationConfirmation("");
                }}
                disabled={deactivating}
                className="inline-flex h-10 items-center rounded-md border border-slate-300 bg-white px-4 font-semibold disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={deactivateAccount}
                disabled={deactivating || deactivationConfirmation.trim().toLowerCase() !== user.email.toLowerCase()}
                className="inline-flex h-10 items-center gap-2 rounded-md bg-red-600 px-4 font-semibold text-white disabled:opacity-50"
              >
                <Trash2 className="h-4 w-4" />
                {deactivating ? "Deactivating" : "Deactivate"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {shareDialogUrl ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4">
          <div className="w-full max-w-lg rounded-lg border border-slate-200 bg-white p-5 shadow-xl">
            <div className="flex items-start gap-3">
              <div className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-50 text-blue-700">
                <Share2 className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <h2 className="text-lg font-semibold text-slate-900">Public share link</h2>
                <p className="mt-1 text-sm text-slate-600">Anyone with this link can download this file.</p>
              </div>
            </div>

            <label className="mt-5 block text-sm font-medium text-slate-700" htmlFor="share-link">
              Download link
            </label>
            <div className="mt-2 flex gap-2">
              <input
                id="share-link"
                ref={shareLinkInputRef}
                value={shareDialogUrl}
                readOnly
                className="h-11 min-w-0 flex-1 rounded-md border border-slate-300 px-3 text-sm outline-none"
                onFocus={(event) => event.target.select()}
              />
              <button
                type="button"
                onClick={copyShareLink}
                className="inline-flex h-11 shrink-0 items-center gap-2 rounded-md bg-blue-600 px-4 font-semibold text-white"
              >
                <Copy className="h-4 w-4" />
                Copy
              </button>
            </div>
            {shareCopied ? <p className="mt-3 text-sm font-semibold text-green-700">Link copied</p> : null}

            <div className="mt-5 flex justify-end">
              <button
                type="button"
                onClick={() => {
                  setShareDialogUrl("");
                  setShareCopied(false);
                }}
                className="inline-flex h-10 items-center rounded-md border border-slate-300 bg-white px-4 font-semibold"
              >
                Close
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {passwordDialogOpen ? (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 px-4">
          <div className="w-full max-w-md rounded-lg border border-slate-200 bg-white p-5 shadow-xl">
            <div className="flex items-start gap-3">
              <div className="mt-1 flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-50 text-blue-700">
                <KeyRound className="h-5 w-5" />
              </div>
              <div className="min-w-0">
                <h2 className="text-lg font-semibold text-slate-900">Reset password</h2>
                <p className="mt-1 text-sm text-slate-600">Update the password for your OWL Drive login.</p>
              </div>
            </div>

            <div className="mt-5 space-y-4">
              <div>
                <label className="block text-sm font-medium text-slate-700" htmlFor="current-password">
                  Current password
                </label>
                <input
                  id="current-password"
                  type="password"
                  value={currentPassword}
                  onChange={(event) => setCurrentPassword(event.target.value)}
                  disabled={changingPassword}
                  className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                  autoComplete="current-password"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700" htmlFor="new-password">
                  New password
                </label>
                <input
                  id="new-password"
                  type="password"
                  value={newPassword}
                  onChange={(event) => setNewPassword(event.target.value)}
                  disabled={changingPassword}
                  className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                  autoComplete="new-password"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700" htmlFor="confirm-new-password">
                  Confirm new password
                </label>
                <input
                  id="confirm-new-password"
                  type="password"
                  value={confirmNewPassword}
                  onChange={(event) => setConfirmNewPassword(event.target.value)}
                  disabled={changingPassword}
                  className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                  autoComplete="new-password"
                />
              </div>
            </div>

            <div className="mt-5 flex justify-end gap-2">
              <button
                type="button"
                onClick={() => setPasswordDialogOpen(false)}
                disabled={changingPassword}
                className="inline-flex h-10 items-center rounded-md border border-slate-300 bg-white px-4 font-semibold disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                onClick={changePassword}
                disabled={changingPassword || !currentPassword || !newPassword || !confirmNewPassword}
                className="inline-flex h-10 items-center gap-2 rounded-md bg-blue-600 px-4 font-semibold text-white disabled:opacity-50"
              >
                <KeyRound className="h-4 w-4" />
                {changingPassword ? "Updating" : "Update password"}
              </button>
            </div>
          </div>
        </div>
      ) : null}

      {!token ? (
        <section className="mx-auto max-w-3xl px-6 py-12">
          <div className="rounded-lg border border-slate-200 bg-white p-8 shadow-sm">
            <h1 className="text-2xl font-semibold">OWL Drive</h1>
            <form className="mt-6 space-y-4" onSubmit={loginWithPassword}>
              <div>
                <label className="block text-sm font-medium text-slate-700" htmlFor="owl-username">
                  Username
                </label>
                <input
                  id="owl-username"
                  value={loginUsername}
                  onChange={(event) => setLoginUsername(event.target.value)}
                  className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                  autoComplete="username"
                />
              </div>
              <div>
                <label className="block text-sm font-medium text-slate-700" htmlFor="owl-password">
                  Password
                </label>
                <input
                  id="owl-password"
                  type="password"
                  value={loginPassword}
                  onChange={(event) => setLoginPassword(event.target.value)}
                  className="mt-2 h-11 w-full rounded-md border border-slate-300 px-3 outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                  autoComplete="current-password"
                />
              </div>
              <button
                type="submit"
                disabled={loginSubmitting || loading}
                className="inline-flex h-11 w-full items-center justify-center rounded-md bg-blue-600 px-5 font-semibold text-white disabled:opacity-50"
              >
                {loginSubmitting ? "Logging in" : "Login"}
              </button>
            </form>
            {registrationFull ? (
              <p className="mt-4 rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">Max usage reached.</p>
            ) : (
              <button
                type="button"
                onClick={() => registerAccount()}
                className="mt-4 inline-flex h-11 w-full items-center justify-center gap-2 rounded-md border border-slate-300 bg-white px-5 font-semibold text-slate-800"
              >
                <Plus className="h-4 w-4" />
                Create account
              </button>
            )}
            <a
              href={linkedInProfileUrl}
              target="_blank"
              rel="noreferrer"
              className="mt-5 inline-flex w-full items-center justify-center gap-2 rounded-md border border-slate-200 bg-slate-50 px-4 py-3 text-sm font-semibold text-slate-700 hover:bg-slate-100"
            >
              <Linkedin className="h-4 w-4 text-blue-700" />
              Ajay Kumar Pandit on LinkedIn
            </a>
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
              <div className="font-semibold text-slate-700">{user?.role ?? storageInfo?.role ?? "USER"}</div>
              <div className="mt-1 text-slate-600">{accountDeactivated ? "Account deactivated" : formatStorage(storageInfo)}</div>
            </div>
            <div className="mt-5 border-t border-slate-200 pt-5 text-sm">
              <div className="flex min-w-0 items-center gap-2 text-slate-700">
                <UserCircle className="h-4 w-4 shrink-0" />
                <span className="truncate font-medium">{user?.email || user?.username}</span>
              </div>
              {accountDeactivated ? (
                <button
                  type="button"
                  onClick={activateAccount}
                  disabled={loading || activating}
                  className="mt-3 inline-flex h-10 w-full items-center justify-center gap-2 rounded-md bg-green-600 px-3 font-semibold text-white hover:bg-green-700 disabled:opacity-50"
                >
                  <RefreshCw className={`h-4 w-4 ${activating ? "animate-spin" : ""}`} />
                  Activate account
                </button>
              ) : (
                <div className="mt-3 space-y-2">
                  <button
                    type="button"
                    onClick={openPasswordDialog}
                    disabled={loading || uploading}
                    className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-md border border-slate-300 bg-white px-3 font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-50"
                  >
                    <KeyRound className="h-4 w-4" />
                    Reset password
                  </button>
                  <button
                    type="button"
                    onClick={openDeactivateAccountDialog}
                    disabled={loading || uploading}
                    className="inline-flex h-10 w-full items-center justify-center gap-2 rounded-md border border-red-200 bg-white px-3 font-semibold text-red-700 hover:bg-red-50 disabled:opacity-50"
                  >
                    <Trash2 className="h-4 w-4" />
                    Deactivate account
                  </button>
                </div>
              )}
            </div>
          </aside>

          <section className="flex-1 px-5 py-5">
            <div className="mb-4 flex items-center justify-between gap-3 border-b border-slate-200 pb-4 text-sm md:hidden">
              <div className="flex min-w-0 items-center gap-2 text-slate-700">
                <UserCircle className="h-4 w-4 shrink-0" />
                <span className="truncate font-medium">{user?.email || user?.username}</span>
              </div>
              {accountDeactivated ? (
                <button
                  type="button"
                  onClick={activateAccount}
                  disabled={loading || activating}
                  className="inline-flex h-10 shrink-0 items-center gap-2 rounded-md bg-green-600 px-3 font-semibold text-white disabled:opacity-50"
                >
                  <RefreshCw className={`h-4 w-4 ${activating ? "animate-spin" : ""}`} />
                  Activate
                </button>
              ) : (
                <div className="flex shrink-0 items-center gap-2">
                  <button
                    type="button"
                    onClick={openPasswordDialog}
                    disabled={loading || uploading}
                    className="inline-flex h-10 items-center justify-center rounded-md border border-slate-300 bg-white px-3 font-semibold disabled:opacity-50"
                    title="Reset password"
                  >
                    <KeyRound className="h-4 w-4" />
                  </button>
                  <button
                    type="button"
                    onClick={openDeactivateAccountDialog}
                    disabled={loading || uploading}
                    className="inline-flex h-10 shrink-0 items-center gap-2 rounded-md border border-red-200 bg-white px-3 font-semibold text-red-700 disabled:opacity-50"
                  >
                    <Trash2 className="h-4 w-4" />
                    Deactivate
                  </button>
                </div>
              )}
            </div>

            {accountDeactivated ? (
              <div className="rounded-lg border border-green-200 bg-white p-6 shadow-sm">
                <div className="flex items-start gap-3">
                  <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-green-50 text-green-700">
                    <UserCircle className="h-5 w-5" />
                  </div>
                  <div className="min-w-0">
                    <h1 className="text-xl font-semibold text-slate-900">Account deactivated</h1>
                    <p className="mt-2 text-sm text-slate-600">
                      Activate this account to create a fresh OWL Drive with the default quota.
                    </p>
                    <button
                      type="button"
                      onClick={activateAccount}
                      disabled={loading || activating}
                      className="mt-5 inline-flex h-11 items-center gap-2 rounded-md bg-green-600 px-5 font-semibold text-white hover:bg-green-700 disabled:opacity-50"
                    >
                      <RefreshCw className={`h-4 w-4 ${activating ? "animate-spin" : ""}`} />
                      {activating ? "Activating" : "Activate account"}
                    </button>
                  </div>
                </div>
              </div>
            ) : (
              <>
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

            {selectedFileIds.size > 0 ? (
              <div className="mb-4 flex flex-wrap items-center gap-2 rounded-md border border-slate-200 bg-white p-3 text-sm shadow-sm">
                <span className="font-semibold text-slate-700">
                  {selectedFileIds.size} selected
                </span>
                <button
                  type="button"
                  onClick={downloadSelectedFiles}
                  disabled={loading || uploading}
                  className="inline-flex h-9 items-center gap-2 rounded-md border border-slate-300 bg-white px-3 font-semibold disabled:opacity-50"
                >
                  <Download className="h-4 w-4" />
                  Download
                </button>
                <select
                  value={batchMoveTargetId}
                  onChange={(event) => setBatchMoveTargetId(event.target.value)}
                  disabled={loading || uploading || moveTargets.length === 0}
                  className="h-9 min-w-44 rounded-md border border-slate-300 bg-white px-3 font-medium disabled:opacity-50"
                >
                  <option value="">Move to...</option>
                  {moveTargets.map((target) => (
                    <option key={target.id} value={target.id}>
                      {target.name}
                    </option>
                  ))}
                </select>
                <button
                  type="button"
                  onClick={moveSelectedFiles}
                  disabled={loading || uploading || !batchMoveTargetId}
                  className="inline-flex h-9 items-center gap-2 rounded-md border border-slate-300 bg-white px-3 font-semibold disabled:opacity-50"
                >
                  <Folder className="h-4 w-4" />
                  Move
                </button>
                <button
                  type="button"
                  onClick={deleteSelectedFiles}
                  disabled={loading || uploading}
                  className="inline-flex h-9 items-center gap-2 rounded-md border border-red-200 bg-white px-3 font-semibold text-red-700 disabled:opacity-50"
                >
                  <Trash2 className="h-4 w-4" />
                  Delete
                </button>
              </div>
            ) : null}

            <div className="overflow-hidden rounded-lg border border-slate-200 bg-white shadow-sm">
              <div className="grid grid-cols-[44px_minmax(0,1fr)_120px_160px_auto] gap-3 border-b border-slate-200 bg-slate-50 px-4 py-3 text-sm font-semibold text-slate-600">
                <div className="flex items-center justify-center">
                  <input
                    type="checkbox"
                    checked={allVisibleFilesSelected}
                    onChange={toggleAllFiles}
                    disabled={visibleFileIds.length === 0}
                    aria-label="Select all files"
                    className="h-4 w-4"
                  />
                </div>
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
                    className="grid grid-cols-[44px_minmax(0,1fr)_120px_160px_auto] items-center gap-3 border-b border-slate-100 px-4 py-2 text-sm last:border-b-0 hover:bg-slate-50"
                  >
                    <div className="flex items-center justify-center">
                      {item.itemType === "file" ? (
                        <input
                          type="checkbox"
                          checked={selectedFileIds.has(item.id)}
                          onChange={() => toggleFileSelection(item.id)}
                          aria-label={`Select ${item.name}`}
                          className="h-4 w-4"
                        />
                      ) : null}
                    </div>
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
                            onClick={() => shareFile(item)}
                            className="inline-flex h-9 w-9 items-center justify-center rounded-md border border-transparent hover:border-slate-200 hover:bg-white"
                            title="Share public link"
                          >
                            <Share2 className="h-4 w-4" />
                          </button>
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
              </>
            )}
          </section>
          <a
            href={linkedInProfileUrl}
            target="_blank"
            rel="noreferrer"
            className="fixed bottom-4 right-4 z-40 inline-flex h-11 items-center gap-2 rounded-md border border-slate-200 bg-white px-4 text-sm font-semibold text-slate-700 shadow-sm hover:bg-slate-50"
          >
            <Linkedin className="h-4 w-4 text-blue-700" />
            LinkedIn
          </a>
        </div>
      )}
    </main>
  );
}
