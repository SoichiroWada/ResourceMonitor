// Cognito マネージドログイン（認可コード + PKCE）でログインし、
// API Gateway の /status /start /stop を ID トークン付きで呼び出します。
// 設定値は CDK がデプロイ時に生成する config.json から読み込みます。

const $ = (id) => document.getElementById(id);
const TOKEN_KEY = "rm.tokens";
const VERIFIER_KEY = "rm.pkce";
let cfg, pollTimer;

function store(k, v) { try { v == null ? sessionStorage.removeItem(k) : sessionStorage.setItem(k, v); } catch {} }
function load(k) { try { return sessionStorage.getItem(k); } catch { return null; } }

function b64url(bytes) {
  return btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}
function randomString() { return b64url(crypto.getRandomValues(new Uint8Array(32))); }
async function sha256(text) { return crypto.subtle.digest("SHA-256", new TextEncoder().encode(text)); }
function decodeJwt(t) { return JSON.parse(atob(t.split(".")[1].replace(/-/g, "+").replace(/_/g, "/"))); }

async function login() {
  const verifier = randomString(), state = randomString();
  store(VERIFIER_KEY, JSON.stringify({ verifier, state }));
  const q = new URLSearchParams({
    response_type: "code", client_id: cfg.clientId, redirect_uri: cfg.redirectUri,
    scope: "openid email", state,
    code_challenge_method: "S256", code_challenge: b64url(await sha256(verifier)),
  });
  location.assign(`${cfg.cognitoDomain}/oauth2/authorize?${q}`);
}

function logout() {
  store(TOKEN_KEY, null);
  const q = new URLSearchParams({ client_id: cfg.clientId, logout_uri: cfg.redirectUri });
  location.assign(`${cfg.cognitoDomain}/logout?${q}`);
}

// ログイン後に ?code=... 付きで戻ってきたらトークンに交換する
async function handleCallback() {
  const p = new URLSearchParams(location.search);
  const code = p.get("code");
  if (!code) return;
  history.replaceState(null, "", location.pathname);
  const saved = JSON.parse(load(VERIFIER_KEY) || "{}");
  store(VERIFIER_KEY, null);
  if (!saved.verifier || saved.state !== p.get("state")) throw new Error("ログイン状態が一致しません。もう一度ログインしてください。");

  const res = await fetch(`${cfg.cognitoDomain}/oauth2/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "authorization_code", client_id: cfg.clientId,
      code, redirect_uri: cfg.redirectUri, code_verifier: saved.verifier,
    }),
  });
  if (!res.ok) throw new Error("トークン取得に失敗しました: " + res.status);
  store(TOKEN_KEY, await res.text());
}

function idToken() {
  const raw = load(TOKEN_KEY);
  if (!raw) return null;
  const t = JSON.parse(raw).id_token;
  if (!t || decodeJwt(t).exp * 1000 < Date.now() + 30_000) { store(TOKEN_KEY, null); return null; }
  return t;
}

async function api(method, path) {
  const token = idToken();
  if (!token) { showLogin(); throw new Error("ログインの有効期限が切れました"); }
  const res = await fetch(cfg.apiUrl.replace(/\/$/, "") + path, { method, headers: { Authorization: `Bearer ${token}` } });
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}`);
  return res.json();
}

const LABELS = { RUNNING: "稼働中", STOPPED: "停止中", STARTING: "起動中…", STOPPING: "停止中…" };

function render(s) {
  $("state").textContent = LABELS[s.state] || s.state;
  $("state").className = "state " + s.state;
  $("detail").textContent = `desired ${s.desired} / running ${s.running} / pending ${s.pending}`;
  $("start").disabled = s.desired >= 1;
  $("stop").disabled = s.desired === 0;
  $("link").innerHTML = s.state === "RUNNING"
    ? `<a href="${s.dashboardUrl}" target="_blank" rel="noopener">ダッシュボードを開く</a><br><span class="detail">DNS 反映まで 1 分ほどかかることがあります</span>`
    : s.state === "STARTING" ? `<span class="detail">起動には 1〜2 分かかります</span>` : "";
  // 状態が変化中なら 5 秒ごと、それ以外は 30 秒ごとに確認
  clearTimeout(pollTimer);
  pollTimer = setTimeout(refresh, s.state === "STARTING" || s.state === "STOPPING" ? 5000 : 30000);
}

async function run(fn) {
  $("err").textContent = "";
  try { render(await fn()); } catch (e) { $("err").textContent = e.message; }
}
const refresh = () => run(() => api("GET", "/status"));

function showLogin() { $("app").hidden = true; $("login").hidden = false; $("user").textContent = ""; }
function showApp(token) {
  $("login").hidden = true; $("app").hidden = false;
  $("user").textContent = decodeJwt(token).email || "";
  refresh();
}

async function main() {
  cfg = await (await fetch("config.json", { cache: "no-store" })).json();
  $("loginBtn").onclick = login;
  $("logout").onclick = (e) => { e.preventDefault(); logout(); };
  $("start").onclick = () => run(() => api("POST", "/start"));
  $("stop").onclick = () => { if (confirm("Fargate タスクを停止しますか？")) run(() => api("POST", "/stop")); };
  try { await handleCallback(); } catch (e) { $("err").textContent = e.message; }
  const t = idToken();
  t ? showApp(t) : showLogin();
}

main().catch((e) => { document.body.textContent = "初期化に失敗しました: " + e.message; });
