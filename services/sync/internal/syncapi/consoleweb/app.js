/* SPDX-License-Identifier: GPL-3.0-only */
"use strict";
(() => {
  const API = "/console/api";
  const messages = {
    unauthorized: "会话已失效，请重新登录。", forbidden: "没有此操作的权限。",
    account_banned: "此账号已被封禁，暂时无法访问用户中心或云同步。",
    conflict: "另一设备已修改这条资料。草稿已保留，请查看最新版本后再决定如何保存。",
    revision_conflict: "另一设备已修改这条资料。草稿已保留，请查看最新版本后再决定如何保存。",
    invalid_request: "提交内容未通过检查，请检查字段后重试。",
    console_key_unavailable: "旧资料待迁移。请回到已解锁的原设备，或先使用恢复包与恢复码恢复原密钥。",
    legacy_account: "此账号已有加密资料，请在持有原密钥的设备上确认迁移。",
    password_reset_unavailable: "登录服务尚未配置密码重置能力。",
    password_reset_identity_ambiguous: "此账号绑定了多个登录身份，请在登录服务中核对后重置密码。",
    duplicate_dictionary_entry: "相同的词条和编码已经存在，请修改草稿后再保存。",
    csrf_failed: "页面验证已失效，请重新载入后再操作。",
    record_expired: "这条剪贴板已过期，请重新载入列表。",
    record_deleted: "这条资料已删除，请重新载入列表。",
    not_found: "这条资料已不存在，请重新载入列表。"
  };
  class APIError extends Error {
    constructor(status, code) { super(messages[code] || (status === 409 ? messages.conflict : status === 410 ? messages.record_expired : "请求失败，请稍后重试。")); this.status = status; this.code = code; }
  }
  function newGate() {
    let generation = 0;
    return { advance: () => ++generation, current: (value) => generation === value, value: () => generation };
  }
  function validRevision(value) { return Number.isSafeInteger(value) && value >= 0; }
  function validKeyState(value) { return ["available", "empty", "required", "legacy"].includes(value); }
  function safeContent(content) {
    return content?.type === "dictionary" ? Number.isSafeInteger(content.weight) : content?.type === "clipboard" && validRevision(content.expires_at_ms);
  }
  function buildMutation(view, content, deleted, uuid) {
    const revision = view ? view.server_revision : 0;
    if (!validRevision(revision)) throw new Error("资料版本超出网页安全处理范围，请使用原设备处理。");
    if (!safeContent(content)) throw new Error("资料数值超出网页安全处理范围，请使用原设备处理。");
    return { operation_id: uuid(), record_id: view ? view.record.id : uuid(), base_revision: revision, deleted, content: { ...content } };
  }
  async function request(path, options = {}) {
    const headers = { Accept: "application/json" };
    if (options.body !== undefined) headers["Content-Type"] = "application/json";
    if (options.csrf) headers["X-CSRF-Token"] = options.csrf;
    if (options.account) headers["X-Console-Account-ID"] = options.account;
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 20000);
    try {
      const response = await fetch(path, { method: options.method || "GET", credentials: "same-origin", cache: "no-store", redirect: "error", headers, signal: controller.signal, ...(options.body !== undefined ? { body: JSON.stringify(options.body) } : {}) });
      let value = {};
      if (response.status !== 204) {
        try { value = await response.json(); } catch { throw new APIError(response.status, "invalid_response"); }
      }
      if (!response.ok) throw new APIError(response.status, typeof value.error === "string" ? value.error : "request_failed");
      return value;
    } finally { clearTimeout(timer); }
  }
  // Node's built-in runner verifies protocol and asynchronous isolation without a browser.
  if (typeof module !== "undefined" && module.exports) { module.exports = { APIError, buildMutation, newGate, request, safeContent, validRevision }; return; }

  const app = document.getElementById("app");
  const adminMode = location.pathname.startsWith("/console/admin");
  const gate = newGate();
  const accountsGate = newGate();
  let expiryTimer;
  let searchTimer;
  const uncertainAccounts = new Map();
  const state = { session: null, kind: "dictionary", rows: [], accounts: [], account: null, next: "", accountNext: "", loading: false, error: "", notice: "", editor: null, confirm: null, busy: false, unknown: false, unknownAction: "", keyRequired: false, keyState: "available", filter: "", accountsLoading: false, accountsError: "", started: false };
  function el(tag, attrs = {}, ...children) {
    const node = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs)) {
      if (value === undefined || value === null || value === false) continue;
      if (key.startsWith("on")) node.addEventListener(key.slice(2).toLowerCase(), value);
      else if (key === "class") node.className = value;
      else if (key === "value") node.value = value;
      else if (key === "checked" || key === "disabled") node[key] = value;
      else node.setAttribute(key, String(value));
    }
    for (const child of children.flat(Infinity)) {
      if (child === undefined || child === null || child === false) continue;
      node.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return node;
  }
  const btn = (text, click, cls = "", disabled = false, attrs = {}) => el("button", { type: "button", class: cls, onclick: click, disabled, "data-focus": typeof text === "string" ? text : undefined, ...attrs }, text);
  function dateText(value) { const d = new Date(value); return Number.isNaN(d.getTime()) ? "未知" : new Intl.DateTimeFormat("zh-CN", { dateStyle: "medium", timeStyle: "short" }).format(d); }
  function accountID(account) { return account.account_id; }
  function accountName(account) { return account.display_name || account.account_id; }
  function errorText(error) { return error instanceof APIError ? error.message : "网络连接中断或响应超时，请检查连接后重试。"; }
  function authenticated(path, options = {}) { return request(path, { ...options, account: state.session?.account_id }); }
  function clearPrivate() {
    clearTimeout(expiryTimer); clearTimeout(searchTimer);
    gate.advance(); accountsGate.advance(); state.rows = []; state.accounts = []; state.account = null; state.editor = null; state.confirm = null; state.next = ""; state.accountNext = ""; state.filter = ""; state.keyRequired = false; state.notice = ""; state.error = ""; state.unknown = false; state.unknownAction = ""; state.busy = false; state.loading = false; state.accountsLoading = false;
    state.checking = false; state.checkError = ""; state.inflightAction = ""; state.keyState = "available"; uncertainAccounts.clear();
    state.suspended = document.visibilityState === "hidden";
  }
  function authFailure(error) {
    if (!(error instanceof APIError) || ![401, 403].includes(error.status)) return false;
    const text = error.code === "account_banned" || error.code === "banned" ? messages.account_banned : error.status === 401 ? messages.unauthorized : "页面验证或访问权限已失效，请重新登录。";
    clearPrivate(); state.session = null; state.error = text; render(); return true;
  }
  function announce(message) { state.notice = message; }
  function navigate(kind) {
    if (state.busy || state.checking || state.checkError || state.suspended) return;
    state.kind = kind; state.editor = null; state.confirm = null; state.notice = "";
    if (!adminMode || !state.unknown) { state.error = ""; state.unknown = false; }
    loadRows();
  }
  async function session() {
    const generation = gate.advance();
    state.loading = true; state.error = ""; render();
    try {
      const result = await request(API + "/session");
      if (!gate.current(generation)) return;
      state.session = result;
      if (adminMode && !result.admin) { state.session = null; state.error = messages.forbidden; state.loading = false; render(); return; }
      state.loading = false;
      if (adminMode) await loadAccounts(); else await loadRows();
    } catch (error) {
      if (!gate.current(generation)) return;
      state.loading = false;
      if (!(error instanceof APIError && error.status === 401)) state.error = errorText(error);
      render();
    }
  }
  async function revalidateSession() {
    if (!state.session || state.checking) return;
    const previous = state.session;
    const generation = gate.advance(); accountsGate.advance(); const pendingSearch = Boolean(searchTimer); clearTimeout(searchTimer); searchTimer = undefined;
    const interrupted = state.busy; const action = state.inflightAction;
    const reloadRows = state.loading; const reloadAccounts = state.accountsLoading || pendingSearch;
    state.checking = true; state.checkError = ""; state.busy = false; state.loading = false; state.accountsLoading = false; state.inflightAction = "";
    render();
    try {
      const result = await request(API + "/session");
      if (!gate.current(generation)) return;
      if (result.account_id !== previous.account_id || result.csrf_token !== previous.csrf_token || result.admin !== previous.admin) {
        clearPrivate(); state.session = result;
        if (adminMode && !result.admin) { state.session = null; state.error = messages.forbidden; render(); return; }
        if (adminMode) await loadAccounts(); else await loadRows();
        return;
      }
      state.session = result; state.checking = false;
      if (interrupted) {
        state.unknown = true; state.unknownAction = adminMode ? action : "";
        state.error = action === "password-reset" ? "密码重置结果待核实，请到登录服务核对后再操作。" : "页面重新激活前的提交结果待核实，请先重新载入核对。";
        if (adminMode && state.account) uncertainAccounts.set(accountID(state.account), { action, error: state.error });
      }
      if (reloadAccounts) await loadAccounts();
      if (!gate.current(generation)) return;
      if (reloadRows && !state.editor) { await loadRows(); return; }
      render();
    } catch (error) {
      if (!gate.current(generation) || authFailure(error)) return;
      state.checking = false; state.checkError = "暂时无法核对当前登录会话。确认之前已收起资料，请检查连接后重试。"; render();
    }
  }
  async function loadAccounts(after = "", append = false) {
    clearTimeout(searchTimer); searchTimer = undefined;
    const generation = accountsGate.advance(); state.accountsLoading = true; state.accountsError = ""; render();
    try {
      const query = new URLSearchParams(); if (after) query.set("after", after);
      if (state.filter.trim()) query.set("q", state.filter.trim());
      const result = await authenticated(API + "/admin/accounts?" + query);
      if (!accountsGate.current(generation)) return;
      if (!Array.isArray(result.accounts)) throw new APIError(502, "invalid_response");
      state.accounts = append ? state.accounts.concat(result.accounts) : result.accounts;
      state.accountNext = result.next_after || "";
    } catch (error) { if (!accountsGate.current(generation) || authFailure(error)) return; state.accountsError = errorText(error); }
    if (!accountsGate.current(generation)) return;
    state.accountsLoading = false; render();
  }
  function chooseAccount(account) {
    if (state.busy || state.checking || state.checkError || state.suspended) return;
    gate.advance(); state.account = account; state.rows = []; state.next = ""; state.editor = null; state.confirm = null; state.error = ""; state.notice = ""; state.unknown = false; state.unknownAction = ""; state.keyRequired = false; state.loading = false;
    const uncertain = uncertainAccounts.get(accountID(account));
    if (uncertain) { state.unknown = true; state.unknownAction = uncertain.action; state.error = uncertain.error; }
    // Viewing another account's contents is a separate, deliberate, audited action.
    state.started = false; render();
  }
  async function loadRows(after = "", append = false, keepDraft = false) {
    if (keepDraft && state.editor) return lookupLatest();
    if (adminMode && !state.account) return;
    const generation = gate.advance();
    state.loading = true; if (!adminMode || !state.unknown) state.error = ""; state.confirm = null; state.keyRequired = false;
    if (!keepDraft) state.editor = null;
    if (!append) state.rows = [];
    state.started = true; render();
    const query = new URLSearchParams({ kind: state.kind }); if (after) query.set("after", after);
    const path = adminMode ? "/admin/accounts/" + encodeURIComponent(accountID(state.account)) + "/records" : "/records";
    try {
      const result = await authenticated(API + path + "?" + query);
      if (!gate.current(generation)) return;
      if (!Array.isArray(result.records) || !validKeyState(result.key_state)) throw new APIError(502, "invalid_response");
      state.keyState = result.key_state; state.keyRequired = result.key_state !== "available"; if (!adminMode) state.unknown = false;
      state.rows = state.keyRequired ? [] : append ? state.rows.concat(result.records) : result.records;
      state.next = result.next_after || "";
      if (state.keyRequired) state.editor = null;
    } catch (error) { if (!gate.current(generation) || authFailure(error)) return; state.error = errorText(error); }
    if (!gate.current(generation)) return;
    state.loading = false; render();
  }
  async function lookupLatest(continuing = false) {
    const draft = state.editor;
    if (!draft || state.loading || state.busy) return;
    const target = draft.view?.record.id || draft.pending?.record_id;
    if (!target) return;
    const generation = gate.advance();
    const lookup = continuing && draft.lookup ? draft.lookup : { after: "", pages: 0, more: false };
    draft.lookup = lookup; draft.checked = false; draft.latest = null; draft.conflict = true;
    state.loading = true; state.error = ""; render();
    try {
      for (let count = 0; count < 10; count++) {
        const query = new URLSearchParams({ kind: state.kind, limit: "40" }); if (lookup.after) query.set("after", lookup.after);
        const result = await authenticated(API + "/records?" + query);
        if (!gate.current(generation) || state.editor !== draft) return;
        if (!Array.isArray(result.records) || !validKeyState(result.key_state)) throw new APIError(502, "invalid_response");
        if (result.key_state !== "available") { state.rows = []; state.editor = null; state.keyState = result.key_state; state.keyRequired = true; state.next = ""; break; }
        lookup.pages++;
        const latest = result.records.find((entry) => entry.record.id === target);
        if (latest) {
          draft.latest = latest; draft.checked = true; lookup.more = false; state.unknown = false;
          state.rows = state.rows.map((entry) => entry.record.id === target ? latest : entry); break;
        }
        const next = result.next_after || "";
        if (next && next <= lookup.after) throw new APIError(502, "invalid_response");
        lookup.after = next; lookup.more = Boolean(next);
        if (!next) { draft.checked = true; state.unknown = false; state.rows = state.rows.filter((entry) => entry.record.id !== target); break; }
      }
    } catch (error) { if (!gate.current(generation) || authFailure(error)) return; state.error = errorText(error); }
    if (!gate.current(generation)) return;
    state.loading = false; render();
  }
  async function logout() {
    if (state.busy) return;
    const csrf = state.session.csrf_token; const account = state.session.account_id; clearPrivate(); state.session = null; state.loading = true; render();
    try { await request("/console/auth/logout", { method: "POST", csrf, account }); }
    catch { state.error = "退出请求未确认，请关闭本页并在登录服务中核对会话。"; }
    state.loading = false; render();
  }
  function startEdit(view = null) {
    if (state.checking || state.checkError || state.suspended) return;
    state.confirm = null; state.notice = "";
    state.editor = { view, text: view?.record.content.text || "", code: view?.record.content.code || "", error: "", conflict: false, checked: false, latest: null };
    render(); document.getElementById("record-text")?.focus();
  }
  function draftContent(editor) {
    const old = editor.view?.record.content;
    if (state.kind === "clipboard") return { ...old, text: editor.text };
    return { type: "dictionary", scheme: old?.scheme || "pinyin", code: editor.code.trim().replaceAll(" ", "").toLowerCase(), text: editor.text.trim(), weight: old?.weight || 0 };
  }
  async function mutate(body, method, path, success) {
    if (state.busy || state.unknown) return;
    const generation = gate.value(); state.busy = true; state.error = ""; render();
    try {
      await authenticated(API + path, { method, body, csrf: state.session.csrf_token });
      if (!gate.current(generation)) return;
      state.busy = false; state.editor = null; state.confirm = null; announce(success); await loadRows();
    } catch (error) {
      if (!gate.current(generation) || authFailure(error)) return;
      state.busy = false;
      if (error instanceof APIError && error.code === "duplicate_dictionary_entry") {
        state.error = error.message;
      } else if (error instanceof APIError && error.status === 409) {
        if (state.editor) state.editor.conflict = true;
        state.error = messages.conflict;
      } else if (error instanceof APIError && (error.status === 410 || error.code === "record_expired" || error.code === "record_deleted")) {
        state.editor = null; state.confirm = null; announce(error.code === "record_deleted" ? messages.record_deleted : messages.record_expired); await loadRows(); return;
      } else if (!(error instanceof APIError) || error.status >= 500 || error.code === "invalid_response") {
        state.unknown = true; state.error = "提交结果待核实。请先重新载入核对，避免重复修改。草稿暂时保留。";
      } else state.error = errorText(error);
      render();
    }
  }
  function save(event) {
    event.preventDefault();
    const editor = state.editor;
    if (!editor || state.busy || state.unknown || editor.conflict || state.checking || state.checkError || state.suspended) return;
    if (!editor.text.trim()) { editor.error = "请填写正文。"; render(); document.getElementById("record-text")?.focus(); return; }
    if (new TextEncoder().encode(editor.text).length > 65536) { editor.error = "正文不能超过 64 KiB。"; render(); return; }
    const content = draftContent(editor);
    if (state.kind === "dictionary" && !/^[a-z0-9']{1,128}$/.test(content.code)) { editor.error = "拼音或编码须为 1–128 个英文字母、数字或英文单引号。"; render(); document.getElementById("record-code")?.focus(); return; }
    if (state.kind === "clipboard" && content.expires_at_ms <= Date.now()) { state.editor = null; announce(messages.record_expired); loadRows(); return; }
    try {
      const body = buildMutation(editor.view, content, false, () => crypto.randomUUID());
      editor.pending = body;
      mutate(body, editor.view ? "PUT" : "POST", "/records" + (editor.view ? "/" + encodeURIComponent(editor.view.record.id) : ""), "资料已保存，变更将同步到你的其他设备。");
    } catch (error) { editor.error = error.message; render(); }
  }
  function remove(view) {
    if (state.checking || state.checkError || state.suspended) return;
    try { mutate(buildMutation(view, view.record.content, true, () => crypto.randomUUID()), "DELETE", "/records/" + encodeURIComponent(view.record.id), "资料已删除，变更将同步到你的其他设备。"); }
    catch (error) { state.error = error.message; render(); }
  }
  async function accountAction(action) {
    if (state.busy || !state.account || state.checking || state.checkError || state.suspended) return;
    const generation = gate.value(); const target = accountID(state.account); state.busy = true; state.inflightAction = action; state.error = ""; render();
    try {
      const result = await authenticated(API + "/admin/accounts/" + encodeURIComponent(target) + "/" + action, { method: "POST", csrf: state.session.csrf_token, body: {} });
      if (!gate.current(generation)) return;
      if (action === "password-reset" ? result.accepted !== true : result.account_id !== target || result.banned !== (action === "ban")) throw new APIError(502, "invalid_response");
      if (action !== "password-reset") {
        state.account = { ...state.account, banned: action === "ban" };
        state.accounts = state.accounts.map((v) => accountID(v) === target ? state.account : v);
      }
      state.confirm = null; announce(action === "password-reset" ? "登录服务已受理密码重置，请用户按登录服务指引设置新密码。资料密钥未改变。" : action === "ban" ? "账号已封禁，已有会话和新的云同步访问均会被拒绝。" : "账号已解封，已恢复访问资格。");
    } catch (error) {
      if (!gate.current(generation) || authFailure(error)) return;
      const known = error instanceof APIError && (error.status < 500 || error.code === "password_reset_unavailable");
      state.error = known ? errorText(error) : action === "password-reset" ? "密码重置结果待核实，请到登录服务核对后再操作。" : "变更结果待核实，请重新获取账号状态。";
      if (!known) { state.unknown = true; state.unknownAction = action; uncertainAccounts.set(target, { action, error: state.error }); }
    }
    if (!gate.current(generation)) return;
    state.busy = false; state.inflightAction = ""; render();
  }
  async function refreshAccount() {
    const target = accountID(state.account); const generation = gate.value(); state.loading = true; render();
    try {
      const result = await authenticated(API + "/admin/accounts?q=" + encodeURIComponent(target));
      if (!gate.current(generation)) return;
      const fresh = result.accounts?.find((v) => accountID(v) === target);
      if (!fresh) throw new APIError(404, "not_found");
      state.account = fresh; state.accounts = state.accounts.map((v) => accountID(v) === target ? fresh : v); state.confirm = null;
      if (state.unknownAction !== "password-reset") { uncertainAccounts.delete(target); state.unknown = false; state.unknownAction = ""; state.error = ""; }
      announce(state.unknownAction === "password-reset" ? "账号状态已刷新。密码重置结果仍待核实，请到登录服务核对。" : "已重新获取账号状态。");
    } catch (error) { if (!gate.current(generation) || authFailure(error)) return; state.error = errorText(error); }
    if (!gate.current(generation)) return;
    state.loading = false; render();
  }
  async function enableMaterial() {
    if (state.checking || state.checkError || state.suspended) return;
    const consent = document.getElementById("escrow-consent"); if (!consent?.checked || state.busy) return;
    const generation = gate.value(); state.busy = true; render();
    try {
      await authenticated(API + "/material/enable", { method: "POST", csrf: state.session.csrf_token, body: { consent: true } });
      if (!gate.current(generation)) return;
      state.busy = false; announce("已启用网页资料管理。"); await loadRows();
    } catch (error) {
      if (!gate.current(generation) || authFailure(error)) return;
      state.busy = false; state.error = error instanceof APIError ? errorText(error) : "开通结果待核实，请重新载入检查。"; render();
    }
  }
  function brand() { return el("div", { class: "brand" }, el("span", { class: "logo", "aria-hidden": "true" }, "ϟ"), el("div", {}, "ZapIME", el("small", {}, "闪电输入法"))); }
  function notice() {
    return [state.error && el("div", { class: "notice error", role: "alert" }, state.error,
      state.unknown && state.unknownAction !== "password-reset" && btn(adminMode ? "重新获取账号状态" : "重新载入核对", () => {
        if (adminMode) return refreshAccount();
        if (state.editor) state.editor.conflict = true;
        return loadRows("", false, Boolean(state.editor));
      }, "small", state.busy)),
      state.notice && el("div", { class: "notice success", role: "status" }, state.notice)];
  }
  function busyState(text) { return el("div", { class: "state", role: "status" }, el("span", { class: "loading", "aria-hidden": "true" }), el("p", {}, text)); }
  function statePanel(title, description, action) { return el("div", { class: "state" }, el("div", { class: "state-symbol", "aria-hidden": "true" }, "◇"), el("h2", {}, title), el("p", {}, description), action); }
  function tabs() {
    return el("div", { class: "tabs", role: "group", "aria-label": "资料类别" },
      btn(adminMode ? "词库" : "我的词库", () => navigate("dictionary"), state.kind === "dictionary" ? "active" : "", state.busy, { "aria-pressed": state.kind === "dictionary" }),
      btn("云剪贴板", () => navigate("clipboard"), state.kind === "clipboard" ? "active" : "", state.busy, { "aria-pressed": state.kind === "clipboard" }));
  }
  function migration() {
    const empty = state.keyState === "empty"; const legacy = state.keyState === "legacy";
    return el("section", { class: "migration" }, el("span", { class: "badge neutral" }, empty ? "尚未启用网页管理" : legacy ? "旧数据待核验" : "旧资料待迁移"), el("h2", {}, empty ? "在网页上管理你的同步资料" : legacy ? "先核验旧资料的密钥来源" : "在原设备确认资料管理授权"),
      el("p", {}, empty ? "此账号尚无同步资料和已登记的资料密钥。确认托管后即可在网页添加词条。" : "资料尚未开放网页读取。这里不会把未能解密的资料显示为空词库。"),
      !empty && (legacy ? el("p", {}, "这个账号有同步历史，但尚无可信的密钥登记。请保留原设备、原密钥、恢复包和恢复码，先核验旧数据及密钥来源。仅导入恢复包不能自动完成网页开通；本页不会替换原密钥。") : el("ol", {}, el("li", {}, "回到已解锁的原设备，确认正在使用同一账号。"), el("li", {}, "如原设备不可用，先在支持恢复的客户端中使用恢复包与恢复码恢复原密钥。"), el("li", {}, "打开安卓版「账号与同步」→「启用网页资料管理」，在持有原密钥的设备上明确确认托管，再返回本页重新载入。"))),
      el("p", {}, "启用托管后，服务器可用账户密钥解密同步资料。获授权的管理员可查看已同步词条与剪贴板。密码重置不能恢复资料密钥。"),
      btn("重新载入", () => loadRows(), "primary", state.busy),
      !adminMode && empty && el("div", {},
        el("label", { class: "check" }, el("input", { id: "escrow-consent", type: "checkbox", disabled: state.busy, onchange: (event) => { const button = document.getElementById("enable-material"); if (button) button.disabled = !event.target.checked; } }), "我同意服务器托管本账号的同步密钥，并知悉授权管理员可查看已同步词条和剪贴板。"),
        btn("启用网页资料管理", enableMaterial, "primary", true, { id: "enable-material" })));
  }
  function row(view) {
    const record = view.record; const c = record.content;
    const safe = validRevision(view.server_revision) && safeContent(c);
    const actions = !adminMode && el("div", { class: "row-actions" }, btn("编辑", () => startEdit(view), "link small", state.busy || state.unknown || !safe, { "aria-label": "编辑这条" + (state.kind === "dictionary" ? "词条" : "剪贴板") }), btn("删除", () => { state.confirm = { type: "delete", view }; render(); }, "link small danger", state.busy || state.unknown || !safe, { "aria-label": "删除这条" + (state.kind === "dictionary" ? "词条" : "剪贴板") }));
    const tr = el("tr", {}, el("td", {}, el("div", { class: "record-text" }, c.text), state.kind === "clipboard" && el("div", { class: "record-sub" }, "有效期至 " + dateText(c.expires_at_ms), c.pinned ? " · 已置顶" : ""), !safe && !adminMode && el("div", { class: "record-sub" }, "数值超出网页安全处理范围，请使用原设备编辑或删除。")), state.kind === "dictionary" && el("td", { class: "reading" }, c.code), !adminMode && el("td", { class: "actions" }, actions));
    if (state.confirm?.type !== "delete" || state.confirm.view.record.id !== record.id) return tr;
    return [tr, el("tr", {}, el("td", { colspan: state.kind === "dictionary" ? 3 : 2, class: "inline-confirm" },
      el("h3", {}, state.kind === "dictionary" ? "删除这条词条？" : "删除这条剪贴板？"), el("p", {}, "删除将同步到你的其他设备。"),
      el("div", { class: "form-actions" }, btn("取消", () => { state.confirm = null; render(); }, "", state.busy), btn(state.busy ? "正在删除…" : "确认删除", () => remove(view), "danger", state.busy || state.unknown))))];
  }
  function records() {
    if (state.loading) return busyState("正在载入" + (state.kind === "dictionary" ? "词库" : "云剪贴板") + "…");
    if (state.keyRequired) return migration();
    if (state.kind === "clipboard") state.rows = state.rows.filter((entry) => entry.record.content.expires_at_ms > Date.now());
    if (state.error && !state.rows.length && !state.editor) return statePanel("资料暂时无法载入", "请检查连接后重新载入。读取成功前不会显示空列表。", btn("重新载入", () => loadRows(), "primary"));
    if (!state.rows.length && state.next) return statePanel("这一页没有可显示的资料", "仍有后续资料，继续载入后才能确认列表是否为空。", btn("继续载入资料", () => loadRows(state.next, true), "primary", state.busy));
    if (!state.rows.length) return statePanel(state.kind === "dictionary" ? "词库还是空的" : "还没有云剪贴板", state.kind === "dictionary" ? adminMode ? "该账号还没有可读取的同步词条。" : "添加常用词条，在其他设备上也能使用。" : "仅显示在设备上手动保存并同步的剪贴板。", !adminMode && state.kind === "dictionary" && btn("＋ 添加词条", () => startEdit(), "primary"));
    return [el("table", { class: "table", "aria-label": state.kind === "dictionary" ? "已同步词条" : "已同步剪贴板" }, el("thead", {}, el("tr", {}, el("th", { scope: "col" }, state.kind === "dictionary" ? "词条" : "内容"), state.kind === "dictionary" && el("th", { scope: "col", class: "reading" }, "拼音 / 编码"), !adminMode && el("th", { scope: "col", class: "actions" }, "操作"))), el("tbody", {}, state.rows.map(row))),
      state.next && el("div", { class: "pagination" }, btn("载入更多", () => loadRows(state.next, true), "small", state.busy)),
      el("div", { class: "table-footer" }, "本页已读取 " + state.rows.length + " 条" + (state.next ? " · 还有更多资料" : " · 已到列表末尾"))];
  }
  function editor() {
    const draft = state.editor; const clipboard = state.kind === "clipboard";
    if (clipboard && draft.view.record.content.expires_at_ms <= Date.now()) return statePanel("这条剪贴板已过期", state.busy ? "正在核对已提交请求的结果，请稍候。" : "请重新载入列表。", !state.busy && btn("重新载入", () => loadRows(), "primary"));
    return el("form", { class: "editor", onsubmit: save }, el("h2", {}, (draft.view ? "编辑" : "添加") + (clipboard ? "剪贴板" : "词条")),
      el("label", { class: "field", for: "record-text" }, clipboard ? "剪贴板正文" : "词条", el(clipboard ? "textarea" : "input", { id: "record-text", value: draft.text, required: true, maxlength: 65536, autocomplete: "off", disabled: state.busy, oninput: (event) => { draft.text = event.target.value; }, "aria-describedby": draft.error ? "field-error" : undefined })),
      !clipboard && el("label", { class: "field", for: "record-code" }, draft.view?.record.content.scheme === "english" ? "英文编码" : "拼音", el("input", { id: "record-code", value: draft.code, required: true, maxlength: 128, autocomplete: "off", autocapitalize: "none", spellcheck: "false", disabled: state.busy, oninput: (event) => { draft.code = event.target.value; } }), el("span", { class: "hint" }, "英文字母、数字或英文单引号；拼音中的空格会在保存时去除。")),
      clipboard && el("p", { class: "record-sub" }, "原有效期及置顶状态保持不变。有效期至 " + dateText(draft.view.record.content.expires_at_ms)),
      draft.error && el("p", { id: "field-error", class: "field-error", role: "alert" }, draft.error),
      draft.conflict && el("div", { class: "notice" }, "草稿已保留。先查看最新版本，再重新决定保存内容。", btn(state.loading ? "正在查找最新版本…" : "查看最新版本", () => lookupLatest(), "small", state.busy || state.loading)),
      draft.lookup?.more && !state.loading && !draft.latest && el("div", { class: "notice" }, "已查找 " + draft.lookup.pages + " 页，尚有后续资料；尚未确认这条资料是否存在。", btn("继续查找最新版本", () => lookupLatest(true), "small", state.busy)),
      draft.checked && el("div", { class: "notice" }, el("strong", {}, draft.latest ? "最新版本" : "已查到列表末尾，未找到这条资料"), draft.latest ? [el("p", { class: "record-text" }, draft.latest.record.content.text), draft.latest.record.content.code && el("p", {}, "编码：" + draft.latest.record.content.code)] : el("p", {}, "这条资料已删除、过期或不在当前类别。草稿保留供你核对，不会自动新增或重试。"), draft.latest && btn("使用最新版本号保留草稿", () => { draft.view = draft.latest; draft.conflict = false; draft.checked = false; state.error = ""; render(); }, "small", !validRevision(draft.latest.server_revision) || !safeContent(draft.latest.record.content))),
      el("div", { class: "form-actions" }, el("button", { type: "submit", class: "primary", disabled: state.busy || state.loading || state.unknown || draft.conflict }, state.busy ? "正在保存…" : "保存"), btn("取消", () => { gate.advance(); state.loading = false; state.editor = null; if (!state.unknown) state.error = ""; render(); }, "", state.busy)),
      el("p", { class: "record-sub" }, "服务端确认后变更才会显示为已保存。"));
  }
  function recordCard() { return el("section", { class: "card", "aria-label": "同步资料", "aria-busy": state.loading || state.busy }, el("div", { class: "card-toolbar" }, tabs(), !adminMode && state.kind === "dictionary" && !state.editor && !state.keyRequired && btn("＋ 添加词条", () => startEdit(), "primary small", state.loading || state.busy || state.unknown || Boolean(state.error))), state.editor ? editor() : records()); }
  function accountConfirmation() {
    const type = state.confirm?.type;
    if (!["ban", "unban", "password-reset"].includes(type)) return null;
    const name = accountName(state.account); const banned = state.account.banned;
    return el("section", { class: "inline-confirm", role: "region", "aria-label": "确认账号操作" }, el("h3", {}, type === "password-reset" ? "为「" + name + "」发起密码重置？" : (type === "ban" ? "确认封禁「" : "确认解封「") + name + "」？"),
      el("p", {}, "账号：" + accountID(state.account) + " · 当前状态：" + (banned ? "已封禁" : "正常")),
      el("p", {}, type === "password-reset" ? "由登录服务引导用户设置新密码。现有词库、剪贴板和资料密钥保持原状；重置密码不等于恢复资料密钥。" : type === "ban" ? "用户将无法访问用户中心或继续云同步，已有会话也会被拒绝。设备上已有的本地资料仍会保留。" : "将恢复此账号访问用户中心和云同步的资格。"),
      el("div", { class: "form-actions" }, btn("取消", () => { state.confirm = null; render(); }, "", state.busy), btn(state.busy ? "正在处理…" : type === "password-reset" ? "发起重置" : type === "ban" ? "确认封禁" : "确认解封", () => accountAction(type), type === "ban" ? "danger" : "primary", state.busy || state.unknown)));
  }
  function directory() {
    const filtered = state.accounts;
    return el("section", { class: "card directory", "aria-label": "注册用户目录" }, el("div", { class: "card-toolbar" }, el("h2", {}, "用户目录"), el("p", {}, "按账号标识查找，选择后查看详情"), el("label", { class: "search", for: "account-search" }, "查找注册账号", el("input", { id: "account-search", type: "search", placeholder: "账号 ID 前缀或完整登录标识", value: state.filter, disabled: state.busy, oninput: (event) => { state.filter = event.target.value; clearTimeout(searchTimer); accountsGate.advance(); searchTimer = setTimeout(() => loadAccounts(), 300); } }))),
      state.accountsError && el("div", { class: "notice error", role: "alert" }, state.accountsError, btn("重试目录", () => loadAccounts(), "small")),
      state.accountsLoading ? busyState("正在载入账号…") : el("ul", { class: "user-list" }, filtered.map((account) => el("li", {}, btn([el("span", { class: "user-title" }, accountName(account), el("small", {}, accountID(account))), el("span", { class: "badge" + (account.banned ? " blocked" : "") }, account.banned ? "已封禁" : "正常")], () => chooseAccount(account), "user-item" + (state.account && accountID(state.account) === accountID(account) ? " active" : ""), state.busy, { "aria-pressed": Boolean(state.account && accountID(state.account) === accountID(account)) })))),
      !state.accountsLoading && !state.accountsError && !filtered.length && el("div", { class: "state" }, el("p", {}, state.filter.trim() ? "没有找到匹配的注册账号。" : "暂无注册账号。")),
      state.accountNext && el("div", { class: "pagination" }, btn("载入更多用户", () => loadAccounts(state.accountNext, true), "small", state.busy || state.accountsLoading)));
  }
  function adminDetail() {
    if (!state.account) return el("section", { class: "card" }, statePanel("选择一个账号", "从左侧目录选择注册用户，再点击资料类别进行查看。"));
    const account = state.account;
    const resetEnabled = state.session.password_reset_available;
    return el("section", { class: "card", "aria-label": "所选账号详情" }, el("div", { class: "account-head" }, el("div", { class: "page-heading" }, el("h2", {}, accountName(account)), el("span", { class: "badge" + (account.banned ? " blocked" : "") }, account.banned ? "已封禁" : "正常")), el("p", { class: "account-id" }, "账号标识 · " + accountID(account)),
      el("div", { class: "account-actions" }, btn("重置密码", () => { state.confirm = { type: "password-reset" }; render(); }, "small", state.busy || !resetEnabled || state.unknown, { title: resetEnabled ? "由登录服务受理" : messages.password_reset_unavailable }), btn(account.banned ? "解封账号" : "封禁账号", () => { state.confirm = { type: account.banned ? "unban" : "ban" }; render(); }, account.banned ? "small" : "danger small", state.busy || state.unknown || accountID(account) === state.session.account_id), btn("刷新状态", refreshAccount, "link small", state.busy || state.loading)),
      accountID(account) === state.session.account_id && el("p", { class: "record-sub" }, "当前管理员账号不能在此封禁自己。"),
      !resetEnabled && el("p", { class: "record-sub" }, messages.password_reset_unavailable)),
      state.unknownAction === "password-reset" && el("div", { class: "inline-confirm" }, el("h3", {}, "密码重置结果待核实"), el("p", {}, "请先到登录服务核对。以下操作仅清除本页待核实提示，不会再次发起重置或声称重置成功。"), btn("我已到登录服务核对", () => { uncertainAccounts.delete(accountID(account)); state.unknown = false; state.unknownAction = ""; state.error = ""; state.confirm = null; announce("已清除本页待核实提示；密码重置状态以登录服务结果为准。"); render(); }, "small", state.busy)),
      accountConfirmation(), el("div", { class: "card-toolbar" }, tabs()), el("p", { class: "audit-note" }, "此次查看会记录管理员、目标账号、资料类别和时间。正文仅供读取，不写入审计日志。"),
      state.started ? records() : statePanel("点击资料类别开始查看", "仅在权限与审计记录均确认后载入正文。", btn("查看词库", () => navigate("dictionary"), "primary")));
  }
  function login() {
    return el("div", { class: "login-layout" }, el("aside", { class: "login-intro" }, brand(), el("h1", {}, adminMode ? "管理后台" : "用户中心")),
      el("main", { id: "main", class: "login-form" }, el("div", { class: "login-panel" }, el("h2", {}, adminMode ? "登录管理后台" : "管理我的同步资料"), el("p", {}, "使用与手机相同的账号登录。"), state.error && el("div", { class: "notice error", role: "alert" }, state.error),
        state.loading ? busyState("正在核对会话…") : btn("使用账号登录 →", () => { location.assign("/console/auth/login" + (adminMode ? "?return_to=admin" : "")); }, "primary"),
        el("div", { class: "login-note" }, "这里只展示已同步到当前账号的词条与剪贴板。服务端托管模式下，获授权管理员可查看同步正文。", el("p", { class: "record-sub" }, "登录取消后可重新尝试。资料密钥恢复与密码重置分别处理。")))));
  }
  function render() {
    const activeID = document.activeElement?.id;
    const activeLabel = document.activeElement?.getAttribute?.("data-focus");
    const restoreFocus = () => {
      if (!activeID && !activeLabel) return;
      const target = activeID ? document.getElementById(activeID) : [...(app.querySelectorAll?.("button[data-focus]") || [])].find((button) => button.getAttribute("data-focus") === activeLabel);
      (target && !target.disabled ? target : document.getElementById("main"))?.focus({ preventScroll: true });
    };
    clearTimeout(expiryTimer);
    if (state.checking || state.checkError || state.suspended) {
      app.replaceChildren(el("main", { id: "main", class: "noscript", tabindex: "-1" }, brand(), state.checkError ? statePanel("暂时无法核对会话", state.checkError, btn("重新核对会话", revalidateSession, "primary")) : busyState("正在核对当前会话，资料已收起…")));
      return;
    }
    const expiring = state.editor;
    if (expiring?.view?.record.content.type === "clipboard") {
      const remaining = expiring.view.record.content.expires_at_ms - Date.now();
      if (remaining > 0 || !state.busy) expiryTimer = setTimeout(() => { if (state.editor !== expiring || !state.session) return; if (expiring.view.record.content.expires_at_ms > Date.now() || state.busy) { render(); return; } state.editor = null; announce(messages.record_expired); loadRows(); }, Math.min(Math.max(remaining, 0), 2147483647));
    }
    if (!state.editor && state.kind === "clipboard" && state.rows.length) {
      const nextExpiry = Math.min(...state.rows.map((entry) => entry.record.content.expires_at_ms));
      const remaining = nextExpiry - Date.now();
      expiryTimer = setTimeout(() => {
        const now = Date.now(); state.rows = state.rows.filter((entry) => entry.record.content.expires_at_ms > now);
        if (state.confirm?.view?.record.content.expires_at_ms <= now) state.confirm = null;
        render();
      }, Math.min(Math.max(remaining, 0), 2147483647));
    }
    const live = document.getElementById("live");
    if (live) live.textContent = state.error || state.notice || (state.busy ? "正在提交，请稍候。" : state.loading ? "正在载入资料。" : "");
    document.title = "ZapIME · " + (adminMode ? "管理后台" : "用户中心");
    if (!state.session) { app.replaceChildren(login()); restoreFocus(); return; }
    const name = state.session.display_name || state.session.account_id || "当前账号";
    const nav = adminMode ? [btn([el("span", { class: "nav-icon", "aria-hidden": "true" }, "▤"), "用户管理"], () => {}, "active")] : [btn([el("span", { class: "nav-icon", "aria-hidden": "true" }, "▤"), "我的词库"], () => navigate("dictionary"), state.kind === "dictionary" ? "active" : "", state.busy), btn([el("span", { class: "nav-icon", "aria-hidden": "true" }, "▣"), "云剪贴板"], () => navigate("clipboard"), state.kind === "clipboard" ? "active" : "", state.busy)];
    if (state.session.admin) nav.push(btn([el("span", { class: "nav-icon", "aria-hidden": "true" }, "⚙"), adminMode ? "返回用户中心" : "管理后台"], () => { clearPrivate(); location.assign(adminMode ? "/console/" : "/console/admin/"); }, "", state.busy));
    app.replaceChildren(el("div", { class: "shell" }, el("aside", { class: "sidebar" }, brand(), el("nav", { class: "nav", "aria-label": "主要导航" }, nav)),
      el("div", { class: "workspace" }, el("header", { class: "topbar" }, el("span", { class: "crumb" }, adminMode ? "管理后台 / 用户管理" : "用户中心 / " + (state.kind === "dictionary" ? "我的词库" : "云剪贴板")), el("div", { class: "identity" }, el("span", { class: "avatar", "aria-hidden": "true" }, name.slice(0, 1)), el("span", { class: "identity-name" }, name), btn("退出", logout, "link small", state.busy))),
        el("main", { id: "main", class: "content", tabindex: "-1" },
          el("h1", {}, adminMode ? "用户管理" : state.kind === "dictionary" ? "我的词库" : "云剪贴板"),
          notice(),
          adminMode ? el("div", { class: "account-layout" }, directory(), adminDetail()) : recordCard(),
          !adminMode && el("p", { class: "footnote" }, "离线设备会在下次成功同步时取得变更。")))));
    restoreFocus();
  }
  // Returning from another identity-provider session must never reveal cached data.
  window.addEventListener("pageshow", (event) => { if (event.persisted) { clearPrivate(); state.session = null; session(); } });
  window.addEventListener("pagehide", () => { clearPrivate(); state.session = null; app.replaceChildren(); });
  window.addEventListener("focus", () => { if (document.visibilityState !== "hidden") revalidateSession(); });
  document.addEventListener("visibilitychange", () => { state.suspended = document.visibilityState === "hidden"; if (!state.suspended && state.session) revalidateSession(); else render(); });
  session();
})();
