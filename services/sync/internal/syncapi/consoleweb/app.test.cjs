/* SPDX-License-Identifier: GPL-3.0-only */
"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");
const { buildMutation, request, safeContent } = require("./app.js");

const accountA = "10000000-0000-4000-8000-000000000001";
const accountB = "10000000-0000-4000-8000-000000000002";
const adminID = "10000000-0000-4000-8000-000000000009";
const recordID = "20000000-0000-4000-8000-000000000001";
const identity = (admin = false) => ({ account_id: admin ? adminID : accountA, display_name: "合成账号 A", admin, csrf_token: "synthetic-csrf", password_reset_available: true });
const view = (text = "合成词条", revision = 7) => ({ record: { id: recordID, revision: 3, device_id: accountA, deleted: false, content: { type: "dictionary", scheme: "pinyin", code: "hecheng", text, weight: 0 } }, server_revision: revision });
const page = (rows = [], extra = {}) => ({ records: rows, next_after: "", key_state: "available", head: 7, ...extra });
const flush = async () => { for (let i = 0; i < 3; i++) await new Promise(setImmediate); };

// A DOM model checks DOM operations and events; it does not render a browser.
class Element {
  constructor(tag = "#text", value = "") { this.tag = tag; this.value = ""; this.text = value; this.children = []; this.attrs = {}; this.events = {}; this.disabled = false; }
  append(child) { this.children.push(child); }
  replaceChildren(...children) { this.children = children; }
  setAttribute(key, value) { this.attrs[key] = value; }
  addEventListener(type, callback) { this.events[type] = callback; }
  focus() { this.focused = true; }
  setSelectionRange() {}
  get textContent() { return this.text + this.children.map((child) => child.textContent).join(""); }
  all() { return [this, ...this.children.flatMap((child) => child.all())]; }
}
function harness(admin = false) {
  const root = new Element("div"); root.attrs.id = "app";
  const pending = []; const timers = new Map(); const events = {}; let timerID = 0; let uuidID = 0;
  const document = { createElement: (tag) => new Element(tag), createTextNode: (value) => new Element("#text", value), getElementById: (id) => root.all().find((node) => node.attrs.id === id), title: "", visibilityState: "visible", addEventListener: (type, callback) => { events[type] = callback; } };
  const location = { pathname: admin ? "/console/admin/" : "/console/", assign: (url) => { location.assigned = url; } };
  const context = vm.createContext({ document, location, Node: Element, URLSearchParams, AbortController, Intl, Date, TextEncoder, crypto: { randomUUID: () => `30000000-0000-4000-8000-${String(++uuidID).padStart(12, "0")}` },
    fetch: (url, options) => new Promise((resolve, reject) => pending.push({ url, options, resolve, reject, done: false })),
    setTimeout: (callback, delay) => { timers.set(++timerID, { callback, delay }); return timerID; }, clearTimeout: (id) => timers.delete(id), window: { addEventListener: (type, callback) => { events[type] = callback; } } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "app.js"), "utf8"), context);
  return {
    root, pending, events, timers, location, document, text: () => root.textContent,
    find: (predicate) => root.all().find(predicate),
    button: (label) => root.all().find((node) => node.tag === "button" && node.textContent === label),
    async click(label) { const button = this.button(label); assert.ok(button, `missing button: ${label}`); assert.equal(button.disabled, false, `disabled button: ${label}`); button.events.click(); await flush(); },
    input(id, value) { const field = document.getElementById(id); assert.ok(field, `missing field: ${id}`); field.value = value; field.events.input({ target: field }); },
    async submit() { const form = root.all().find((node) => node.tag === "form"); assert.ok(form); form.events.submit({ preventDefault() {} }); await flush(); },
    async respond(fragment, value, status = 200) { const entry = pending.find((r) => !r.done && r.url.includes(fragment)); assert.ok(entry, `missing request: ${fragment}`); entry.done = true; entry.resolve({ status, ok: status >= 200 && status < 300, json: async () => value }); await flush(); return entry; },
    async fail(fragment) { const entry = pending.find((r) => !r.done && r.url.includes(fragment)); assert.ok(entry); entry.done = true; entry.reject(new TypeError("synthetic network failure")); await flush(); }
  };
}
async function userWithRows(rows = [view()]) { const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", page(rows)); return h; }
async function adminWithAccounts() {
  const h = harness(true); await h.respond("/session", identity(true));
  await h.respond("/admin/accounts?", { accounts: [{ account_id: accountA, display_name: "合成用户 A", banned: false }, { account_id: accountB, display_name: "合成用户 B", banned: true }], next_after: "" });
  return h;
}

test("mutations preserve CAS and original content, reject unsafe numeric values", () => {
  const original = view(); const before = JSON.stringify(original); let id = 0;
  const body = buildMutation(original, original.record.content, true, () => String(++id));
  assert.equal(body.base_revision, 7); assert.equal(body.deleted, true); assert.equal(body.record_id, recordID);
  assert.deepEqual(body.content, original.record.content); assert.equal(JSON.stringify(original), before);
  assert.throws(() => buildMutation(view("x", 2 ** 53), original.record.content, false, () => "x"));
  assert.throws(() => buildMutation(original, { ...original.record.content, weight: 2 ** 53 }, true, () => "x"));
  assert.equal(safeContent({ type: "clipboard", expires_at_ms: 2 ** 53 }), false);
  assert.equal(safeContent({ type: "clipboard", expires_at_ms: Date.now() }), true);
});

test("requests carry same-origin credentials, CSRF and expected identity; no automatic retry", async () => {
  const saved = global.fetch; let options; let calls = 0;
  global.fetch = async (_url, input) => { calls++; options = input; return { ok: true, status: 200, json: async () => ({ ok: true }) }; };
  try {
    await request("/console/api/records", { method: "POST", csrf: "synthetic", account: accountA, body: { synthetic: true } });
    assert.equal(options.credentials, "same-origin"); assert.equal(options.cache, "no-store"); assert.equal(options.redirect, "error");
    assert.equal(options.headers["X-CSRF-Token"], "synthetic"); assert.equal(options.headers["X-Console-Account-ID"], accountA);
    assert.deepEqual(JSON.parse(options.body), { synthetic: true });
    global.fetch = async () => { calls++; throw new TypeError("offline"); };
    await assert.rejects(request("/console/api/records")); assert.equal(calls, 2);
  } finally { global.fetch = saved; }
});

test("server error text is not reflected and private response bodies are not logged", async () => {
  const saved = global.fetch;
  global.fetch = async () => ({ ok: false, status: 500, json: async () => ({ error: "<script>private synthetic body</script>" }) });
  try { await assert.rejects(request("/console/api/records"), (error) => !error.message.includes("private synthetic body")); }
  finally { global.fetch = saved; }
});

test("loading and failed reads never become a successful empty state", async () => {
  const h = harness(); await h.respond("/session", identity());
  assert.match(h.text(), /正在载入词库/); assert.doesNotMatch(h.text(), /词库还是空的/);
  await h.fail("/records?"); assert.match(h.text(), /资料暂时无法载入/); assert.doesNotMatch(h.text(), /词库还是空的/);
  assert.equal(h.button("＋ 添加词条").disabled, true);
});

test("record text uses text nodes and never parses server text as HTML", async () => {
  const text = "<img src=x onerror=alert(1)>合成正文"; const h = await userWithRows([view(text)]);
  assert.ok(h.text().includes(text)); assert.equal(h.root.all().some((node) => node.tag === "img"), false);
  assert.equal(h.pending.find((r) => r.url.includes("/records?")).options.headers["X-Console-Account-ID"], accountA);
});

test("late data from another tab selection cannot replace current records", async () => {
  const h = harness(); await h.respond("/session", identity());
  await h.click("云剪贴板");
  await h.respond("kind=clipboard", page([]));
  assert.match(h.text(), /还没有云剪贴板/);
  await h.respond("kind=dictionary", page([view("迟到合成词条")]));
  assert.doesNotMatch(h.text(), /迟到合成词条/); assert.match(h.text(), /还没有云剪贴板/);
});

test("admin account switching clears old text immediately and rejects its late response", async () => {
  const h = await adminWithAccounts();
  const select = async (name) => { const item = h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith(name)); item.events.click(); await flush(); };
  await select("合成用户 A"); assert.match(h.text(), /点击资料类别开始查看/);
  await h.click("查看词库"); await select("合成用户 B");
  assert.doesNotMatch(h.text(), /迟到的A正文/);
  await h.respond("/" + accountA + "/records", page([view("迟到的A正文")]));
  assert.doesNotMatch(h.text(), /迟到的A正文/);
  await h.click("查看词库"); await h.respond("/" + accountB + "/records", page([view("B的合成正文")]));
  assert.match(h.text(), /B的合成正文/); assert.equal(h.button("编辑"), undefined); assert.equal(h.button("删除"), undefined);
});

test("logout removes all data before network confirmation and invalidates pending responses", async () => {
  const h = await userWithRows([view("需要清除的合成正文")]); await h.click("云剪贴板"); await h.click("退出");
  assert.doesNotMatch(h.text(), /需要清除的合成正文/); assert.match(h.text(), /管理我的同步资料/);
  const logout = h.pending.find((r) => r.url.includes("/auth/logout"));
  assert.equal(logout.options.headers["X-Console-Account-ID"], accountA); assert.equal(logout.options.headers["X-CSRF-Token"], "synthetic-csrf");
  await h.respond("kind=clipboard", page([view("退出后的迟到正文")])); assert.doesNotMatch(h.text(), /退出后的迟到正文/);
  await h.respond("/auth/logout", {}, 204);
});

test("expired sessions and denied permission clear record text and unsaved drafts", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "不能保留的合成草稿"); await h.submit();
  await h.respond("/records/", { error: "forbidden" }, 403);
  assert.doesNotMatch(h.text(), /合成词条|不能保留的合成草稿/); assert.match(h.text(), /页面验证或访问权限已失效/);
});

test("conflicting save retains draft and requires an explicit fresh revision before saving", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "我修改的合成草稿"); await h.submit(); await h.submit();
  assert.equal(h.pending.filter((r) => r.options.method === "PUT").length, 1);
  await h.respond("/records/", { error: "conflict" }, 409);
  assert.equal(h.find((node) => node.attrs.id === "record-text").value, "我修改的合成草稿");
  assert.equal(h.button("保存").disabled, true);
  await h.click("查看最新版本"); await h.respond("/records?", page([view("另一个设备的新合成正文", 9)]));
  assert.match(h.text(), /另一个设备的新合成正文/); assert.equal(h.button("保存").disabled, true);
  await h.click("使用最新版本号保留草稿"); await h.submit();
  const writes = h.pending.filter((r) => r.options.method === "PUT"); assert.equal(writes.length, 2);
  assert.equal(JSON.parse(writes[1].options.body).base_revision, 9); assert.equal(JSON.parse(writes[1].options.body).content.text, "我修改的合成草稿");
  await h.respond("/records/", view("我修改的合成草稿", 10)); await h.respond("/records?", page([view("我修改的合成草稿", 10)]));
  assert.match(h.text(), /资料已保存/);
});

test("unknown write results block another submit until explicit reload", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "待核实合成草稿"); await h.submit(); await h.fail("/records/");
  assert.match(h.text(), /提交结果待核实/); assert.equal(h.button("保存").disabled, true);
  await h.submit(); assert.equal(h.pending.filter((r) => r.options.method === "PUT").length, 1);
  await h.click("重新载入核对"); await h.respond("/records?", page([view("待核实合成草稿", 8)]));
  assert.equal(h.button("保存").disabled, true); assert.match(h.text(), /最新版本/);
});

test("migration never masquerades as an empty library and key entry is absent", async () => {
  const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", page([], { key_state: "required" }));
  assert.match(h.text(), /旧资料待迁移/); assert.doesNotMatch(h.text(), /词库还是空的/);
  assert.match(h.text(), /获授权.*管理员可查看/);
  assert.equal(h.button("启用网页资料管理"), undefined);
  assert.equal(h.root.all().some((node) => node.tag === "input" && node.attrs.type !== "checkbox"), false);
});

test("back-forward cache lifecycle clears private DOM before identity is rechecked", async () => {
  const h = await userWithRows([view("缓存中的合成正文")]);
  h.events.pagehide(); assert.equal(h.text(), "");
  h.events.pageshow({ persisted: true }); await flush();
  assert.doesNotMatch(h.text(), /缓存中的合成正文/); assert.match(h.text(), /正在核对会话/);
  await h.respond("/session", { error: "unauthorized" }, 401);
  assert.doesNotMatch(h.text(), /缓存中的合成正文/);
});

test("dictionary creation validates fields and submits normalized pinyin once", async () => {
  const h = await userWithRows([]); await h.click("＋ 添加词条"); await h.submit();
  assert.match(h.text(), /请填写正文/); assert.equal(h.pending.filter((r) => r.options.method === "POST").length, 0);
  h.input("record-text", "合成新增词"); h.input("record-code", "he cheng"); await h.submit();
  const sent = h.pending.find((r) => r.options.method === "POST"); const body = JSON.parse(sent.options.body);
  assert.equal(body.base_revision, 0); assert.equal(body.deleted, false); assert.equal(body.content.code, "hecheng"); assert.equal(body.content.scheme, "pinyin");
  assert.equal(body.content.text, "合成新增词"); assert.notEqual(body.record_id, body.operation_id);
  await h.respond("/records", view("合成新增词", 8)); await h.respond("/records?", page([view("合成新增词", 8)]));
  assert.match(h.text(), /资料已保存/);
});

test("deletion requires adjacent confirmation and sends original content as tombstone", async () => {
  const original = view(); const h = await userWithRows([original]); await h.click("删除");
  assert.match(h.text(), /删除将同步到你的其他设备/); assert.equal(h.pending.filter((r) => r.options.method === "DELETE").length, 0);
  await h.click("确认删除"); const sent = h.pending.find((r) => r.options.method === "DELETE"); const body = JSON.parse(sent.options.body);
  assert.equal(body.deleted, true); assert.equal(body.record_id, recordID); assert.equal(body.base_revision, 7); assert.deepEqual(body.content, original.record.content);
  await h.respond("/records/", {}, 204); await h.respond("/records?", page()); assert.match(h.text(), /资料已删除/);
});

test("clipboard edits preserve original expiry and pin, and expiry ends editing", async () => {
  const clipboard = { ...view(), record: { ...view().record, content: { type: "clipboard", text: "合成剪贴板", expires_at_ms: Date.now() + 60000, pinned: true } } };
  const h = await userWithRows(); await h.click("云剪贴板"); await h.respond("kind=clipboard", page([clipboard])); await h.click("编辑");
  h.input("record-text", "修改的合成剪贴板"); await h.submit();
  const body = JSON.parse(h.pending.find((r) => r.options.method === "PUT").options.body);
  assert.equal(body.content.expires_at_ms, clipboard.record.content.expires_at_ms); assert.equal(body.content.pinned, true);
  await h.respond("/records/", { error: "record_expired" }, 410); await h.respond("kind=clipboard", page());
  assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined); assert.match(h.text(), /这条剪贴板已过期/);
});

test("unsafe numeric content is shown read-only with explicit native-client guidance", async () => {
  const item = view(); item.record.content.weight = 2 ** 53;
  const h = await userWithRows([item]); assert.equal(h.button("编辑").disabled, true); assert.equal(h.button("删除").disabled, true);
  assert.match(h.text(), /请使用原设备编辑或删除/);
});

test("admin ban and unban show exact target and do not act before confirmation", async () => {
  const h = await adminWithAccounts(); h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 A")).events.click(); await flush();
  await h.click("封禁账号"); assert.match(h.text(), /确认封禁「合成用户 A」/); assert.ok(h.text().includes(accountA));
  assert.equal(h.pending.filter((r) => r.options.method === "POST").length, 0);
  await h.click("确认封禁"); assert.equal(h.button("确认封禁"), undefined); assert.equal(h.button("正在处理…").disabled, true);
  const entry = await h.respond("/" + accountA + "/ban", { account_id: accountA, banned: true });
  assert.equal(entry.options.headers["X-Console-Account-ID"], adminID); assert.deepEqual(JSON.parse(entry.options.body), {});
  assert.ok(h.button("解封账号")); await h.click("解封账号"); assert.match(h.text(), /恢复此账号访问用户中心和云同步/);
  await h.click("确认解封"); await h.respond("/" + accountA + "/unban", { account_id: accountA, banned: false }); assert.ok(h.button("封禁账号"));
});

test("password reset reports provider acceptance separately from data-key recovery", async () => {
  const h = await adminWithAccounts(); h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 B")).events.click(); await flush();
  await h.click("重置密码"); assert.match(h.text(), /重置密码不等于恢复资料密钥/); await h.click("发起重置");
  await h.respond("/" + accountB + "/password-reset", { accepted: true }); assert.match(h.text(), /登录服务已受理密码重置/); assert.match(h.text(), /资料密钥未改变/);
});

test("missing password-reset capability disables entry and explains it in Chinese", async () => {
  const h = harness(true); await h.respond("/session", { ...identity(true), password_reset_available: false, password_reset_reason: "password_reset_unavailable" });
  await h.respond("/admin/accounts?", { accounts: [{ account_id: accountA, display_name: "合成用户 A", banned: false }], next_after: "" });
  h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 A")).events.click(); await flush();
  assert.equal(h.button("重置密码").disabled, true); assert.match(h.text(), /登录服务尚未配置密码重置能力/); assert.doesNotMatch(h.text(), /password_reset_unavailable/);
});

test("ordinary users cannot enter the administrator directory", async () => {
  const h = harness(true); await h.respond("/session", identity(false));
  assert.match(h.text(), /没有此操作的权限/); assert.equal(h.pending.some((r) => r.url.includes("/admin/accounts")), false);
});

test("explicit escrow consent precedes enable request and no key material enters the page", async () => {
  const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", page([], { key_state: "empty" }));
  const checkbox = h.find((node) => node.attrs.id === "escrow-consent"); checkbox.checked = true; checkbox.events.change({ target: checkbox });
  await h.click("启用网页资料管理"); const sent = h.pending.find((r) => r.url.includes("/material/enable"));
  assert.deepEqual(JSON.parse(sent.options.body), { consent: true }); assert.equal(sent.options.headers["X-Console-Account-ID"], accountA);
  await h.respond("/material/enable", {}, 204); await h.respond("/records?", page()); assert.match(h.text(), /已启用网页资料管理/);
});

test("administrator searches use server-side q and stale search replies are discarded", async () => {
  const h = await adminWithAccounts(); h.input("account-search", "10000000");
  const searchTimer = [...h.timers.values()].find((timer) => timer.delay === 300); assert.ok(searchTimer); searchTimer.callback(); await flush();
  assert.ok(h.pending.find((r) => !r.done && r.url.includes("q=10000000")));
  h.input("account-search", "20000000"); const nextTimer = [...h.timers.values()].filter((timer) => timer.delay === 300).at(-1); nextTimer.callback(); await flush();
  await h.respond("q=20000000", { accounts: [], next_after: "" });
  await h.respond("q=10000000", { accounts: [{ account_id: accountA, display_name: "迟到搜索合成用户", banned: false }], next_after: "" });
  assert.doesNotMatch(h.text(), /迟到搜索合成用户/); assert.match(h.text(), /没有找到匹配的注册账号/);
});

test("account-status refresh cannot claim a timed-out password reset was resolved", async () => {
  const h = await adminWithAccounts(); h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 A")).events.click(); await flush();
  await h.click("重置密码"); await h.click("发起重置"); await h.fail("/password-reset");
  assert.match(h.text(), /密码重置结果待核实/); assert.equal(h.button("重置密码").disabled, true);
  await h.click("刷新状态"); await h.respond("q=" + accountA, { accounts: [{ account_id: accountA, display_name: "合成用户 A", banned: false }], next_after: "" });
  assert.match(h.text(), /密码重置结果仍待核实/); assert.equal(h.button("重置密码").disabled, true);
  await h.click("查看词库"); await h.respond("kind=dictionary", page()); assert.equal(h.button("重置密码").disabled, true);
});

test("assets remain offline, CSP-compatible, and free of persistent personal-data storage", () => {
  const script = fs.readFileSync(path.join(__dirname, "app.js"), "utf8"); const html = fs.readFileSync(path.join(__dirname, "index.html"), "utf8"); const css = fs.readFileSync(path.join(__dirname, "app.css"), "utf8");
  assert.doesNotMatch(script, /innerHTML|outerHTML|insertAdjacentHTML|localStorage|sessionStorage|console\.log|eval\(/);
  assert.doesNotMatch(html, /<script(?![^>]*src=)|\son\w+=|style=/);
  assert.doesNotMatch(html + css, /https?:\/\/|@import|url\(/);
  assert.match(html, /lang="zh-CN"/); assert.match(html, /aria-live="polite"/); assert.match(css, /prefers-reduced-motion/); assert.match(css, /max-width:650px/);
});

test("loss of readable key state clears an existing draft before migration guidance", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "需要清除的密钥变化草稿"); await h.submit();
  await h.respond("/records/", { error: "conflict" }, 409); await h.click("查看最新版本");
  await h.respond("/records?", page([], { key_state: "required" }));
  assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined); assert.doesNotMatch(h.text(), /需要清除的密钥变化草稿|合成词条/); assert.match(h.text(), /旧资料待迁移/);
});

test("reactivating a tab hides old data before checking a changed identity", async () => {
  const h = await userWithRows([view("账号A的合成正文")]); await h.click("云剪贴板");
  h.events.focus(); await flush(); assert.doesNotMatch(h.text(), /账号A的合成正文/); assert.match(h.text(), /资料已收起/);
  await h.respond("kind=clipboard", page([view("A迟到的合成剪贴板")])); assert.doesNotMatch(h.text(), /A迟到/);
  await h.respond("/session", { ...identity(), account_id: accountB, display_name: "合成账号 B", csrf_token: "new-session" });
  const newRead = h.pending.filter((entry) => entry.url.includes("/records?")).at(-1);
  assert.equal(newRead.options.headers["X-Console-Account-ID"], accountB);
  await h.respond("kind=clipboard", page([])); assert.match(h.text(), /合成账号 B/); assert.doesNotMatch(h.text(), /账号A的|A迟到/);
});

test("same live session retains drafts; replacement session on same account clears them", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "同会话可保留的合成草稿");
  h.events.focus(); await flush(); assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined);
  await h.respond("/session", identity()); assert.equal(h.find((node) => node.attrs.id === "record-text").value, "同会话可保留的合成草稿");
  h.events.focus(); await h.respond("/session", { ...identity(), csrf_token: "replacement-session" });
  await h.respond("/records?", page()); assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined); assert.doesNotMatch(h.text(), /同会话可保留/);
});

test("visibility changes conceal private DOM and a failed recheck remains concealed until retry", async () => {
  const h = await userWithRows([view("可见性切换合成正文")]); h.document.visibilityState = "hidden"; h.events.visibilitychange();
  assert.doesNotMatch(h.text(), /可见性切换合成正文/);
  h.document.visibilityState = "visible"; h.events.visibilitychange(); await h.fail("/session");
  assert.doesNotMatch(h.text(), /可见性切换合成正文/); assert.match(h.text(), /暂时无法核对会话/);
  await h.click("重新核对会话"); await h.respond("/session", identity()); assert.match(h.text(), /可见性切换合成正文/);
});

test("reactivation during a write does not replay it or trust its superseded result", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "切回时待核实合成草稿"); await h.submit();
  h.events.focus(); await h.respond("/session", identity()); assert.match(h.text(), /提交结果待核实/); assert.equal(h.button("保存").disabled, true);
  await h.respond("/records/", view("切回时待核实合成草稿", 8)); assert.equal(h.button("保存").disabled, true);
  assert.equal(h.pending.filter((entry) => entry.options.method === "PUT").length, 1);
});

test("CSRF or permission 403 clears drafts and directs the user to sign in again", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "CSRF拒绝合成草稿"); await h.submit();
  await h.respond("/records/", { error: "forbidden" }, 403); assert.match(h.text(), /页面验证或访问权限已失效，请重新登录/);
  assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined); assert.doesNotMatch(h.text(), /CSRF拒绝合成草稿/);
  assert.ok(h.button("使用账号登录 →"));
});

test("conflict lookup follows later pages before claiming a record is missing", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "分页合成草稿"); await h.submit(); await h.respond("/records/", { error: "conflict" }, 409);
  await h.click("查看最新版本"); await h.respond("/records?", page([], { next_after: "10000000-0000-4000-8000-000000000001" }));
  assert.ok(h.pending.find((entry) => !entry.done && entry.url.includes("after=10000000"))); assert.doesNotMatch(h.text(), /已查到列表末尾/);
  await h.respond("after=10000000", page([view("位于后续分页的合成新值", 13)])); assert.match(h.text(), /位于后续分页的合成新值/);
  await h.click("使用最新版本号保留草稿"); await h.submit();
  const body = JSON.parse(h.pending.filter((entry) => entry.options.method === "PUT").at(-1).options.body);
  assert.equal(body.base_revision, 13); assert.equal(body.content.text, "分页合成草稿");
});

test("conflict lookup has a per-action bound and offers explicit continuation", async () => {
  const h = await userWithRows(); await h.click("编辑"); await h.submit(); await h.respond("/records/", { error: "conflict" }, 409); await h.click("查看最新版本");
  for (let index = 1; index <= 10; index++) await h.respond("/records?", page([], { next_after: `10000000-0000-4000-8000-${String(index).padStart(12, "0")}` }));
  assert.equal(h.pending.filter((entry) => !entry.done && entry.url.includes("/records?")).length, 0);
  assert.match(h.text(), /已查找 10 页，尚有后续资料/); assert.equal(h.button("保存").disabled, true);
  await h.click("继续查找最新版本"); await h.respond("/records?", page([view("明确继续后找到的合成值", 16)])); assert.match(h.text(), /明确继续后找到的合成值/);
});

test("cancelling a paged conflict lookup rejects its late result and releases loading state", async () => {
  const h = await userWithRows(); await h.click("编辑"); await h.submit(); await h.respond("/records/", { error: "conflict" }, 409); await h.click("查看最新版本"); await h.click("取消");
  await h.respond("/records?", page([view("取消后的迟到合成正文", 20)])); assert.doesNotMatch(h.text(), /取消后的迟到合成正文/); assert.ok(h.button("编辑")); assert.equal(h.button("编辑").disabled, false);
});

test("expired clipboard list entries disappear locally without extra network requests", async () => {
  const clipboard = { ...view(), record: { ...view().record, content: { type: "clipboard", text: "会过期的合成剪贴板", expires_at_ms: Date.now() + 60000, pinned: false } } };
  const h = await userWithRows(); await h.click("云剪贴板"); await h.respond("kind=clipboard", page([clipboard]));
  const count = h.pending.length; const timer = [...h.timers.values()].find((entry) => entry.delay > 20000); assert.ok(timer);
  clipboard.record.content.expires_at_ms = Date.now() - 1; timer.callback(); await flush();
  assert.doesNotMatch(h.text(), /会过期的合成剪贴板/); assert.match(h.text(), /还没有云剪贴板/); assert.equal(h.pending.length, count);
});

test("uncertain ban survives switching targets and only live account status resolves it", async () => {
  const h = await adminWithAccounts(); const select = async (name) => { h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith(name)).events.click(); await flush(); };
  await select("合成用户 A"); await h.click("封禁账号"); await h.click("确认封禁"); await h.fail("/ban");
  await select("合成用户 B"); await select("合成用户 A"); assert.equal(h.button("封禁账号").disabled, true); assert.match(h.text(), /变更结果待核实/);
  await h.click("重新获取账号状态"); await h.respond("q=" + accountA, { accounts: [{ account_id: accountA, display_name: "合成用户 A", banned: true }], next_after: "" });
  assert.ok(h.button("解封账号")); assert.equal(h.button("解封账号").disabled, false); assert.doesNotMatch(h.text(), /变更结果待核实/);
});

test("uncertain reset survives target switching and acknowledgment never sends another reset", async () => {
  const h = await adminWithAccounts(); const select = async (name) => { h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith(name)).events.click(); await flush(); };
  await select("合成用户 A"); await h.click("重置密码"); await h.click("发起重置"); await h.fail("/password-reset");
  await select("合成用户 B"); await select("合成用户 A"); assert.equal(h.button("重置密码").disabled, true);
  const count = h.pending.length; await h.click("我已到登录服务核对"); assert.equal(h.pending.length, count); assert.equal(h.button("重置密码").disabled, false);
  assert.match(h.text(), /密码重置状态以登录服务结果为准/); assert.doesNotMatch(h.text(), /登录服务已受理/);
});

test("empty, registered-unescrowed and legacy accounts have distinct safe setup paths", async () => {
  for (const keyState of ["empty", "required", "legacy"]) {
    const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", page([], { key_state: keyState }));
    if (keyState === "empty") { assert.match(h.text(), /尚未启用网页管理/); assert.doesNotMatch(h.text(), /旧资料待迁移|旧数据待核验/); assert.equal(h.button("启用网页资料管理").disabled, true); }
    if (keyState === "required") { assert.match(h.text(), /账号与同步.*启用网页资料管理/); assert.equal(h.button("启用网页资料管理"), undefined); }
    if (keyState === "legacy") { assert.match(h.text(), /旧数据待核验/); assert.match(h.text(), /仅导入恢复包不能自动完成网页开通/); assert.equal(h.button("启用网页资料管理"), undefined); }
    assert.doesNotMatch(h.text(), /词库还是空的/);
  }
});

test("a filtered empty scan page retains a route to later live records", async () => {
  const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", page([], { next_after: accountA }));
  assert.doesNotMatch(h.text(), /词库还是空的/); assert.match(h.text(), /仍有后续资料/);
  await h.click("继续载入资料"); await h.respond("after=" + accountA, page([view("墓碑后页的合成词条")])); assert.match(h.text(), /墓碑后页的合成词条/);
});

test("a fully missing conflict target preserves the draft and removes the stale list entry", async () => {
  const h = await userWithRows(); await h.click("编辑"); h.input("record-text", "被删条目的合成草稿"); await h.submit(); await h.respond("/records/", { error: "conflict" }, 409);
  await h.click("查看最新版本"); await h.respond("/records?", page()); assert.match(h.text(), /已查到列表末尾，未找到这条资料/); assert.equal(h.button("保存").disabled, true);
  assert.equal(h.find((node) => node.attrs.id === "record-text").value, "被删条目的合成草稿"); await h.click("取消"); assert.doesNotMatch(h.text(), /合成词条/); assert.match(h.text(), /词库还是空的/);
});

test("a repeated pagination cursor fails safely rather than scanning indefinitely", async () => {
  const h = await userWithRows(); await h.click("编辑"); await h.submit(); await h.respond("/records/", { error: "conflict" }, 409); await h.click("查看最新版本");
  await h.respond("/records?", page([], { next_after: accountA })); await h.respond("/records?", page([], { next_after: accountA }));
  assert.equal(h.pending.filter((entry) => !entry.done && entry.url.includes("/records?")).length, 0); assert.match(h.text(), /请求失败/); assert.equal(h.button("保存").disabled, true); assert.doesNotMatch(h.text(), /已查到列表末尾/);
});

test("viewing records cannot erase an unresolved administrator ban result", async () => {
  const h = await adminWithAccounts(); h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 A")).events.click(); await flush();
  await h.click("封禁账号"); await h.click("确认封禁"); await h.fail("/ban"); await h.click("查看词库"); await h.respond("kind=dictionary", page());
  assert.match(h.text(), /变更结果待核实/); assert.equal(h.button("封禁账号").disabled, true); assert.ok(h.button("重新获取账号状态"));
});

test("malformed reset acknowledgment is treated as unknown instead of accepted", async () => {
  const h = await adminWithAccounts(); h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成用户 A")).events.click(); await flush();
  await h.click("重置密码"); await h.click("发起重置"); await h.respond("/password-reset", {});
  assert.match(h.text(), /密码重置结果待核实/); assert.doesNotMatch(h.text(), /登录服务已受理/); assert.equal(h.button("重置密码").disabled, true);
});

test("the administrator cannot trigger the forbidden self-ban route from the interface", async () => {
  const h = harness(true); await h.respond("/session", identity(true)); await h.respond("/admin/accounts?", { accounts: [{ account_id: adminID, display_name: "合成管理员自己", banned: false }], next_after: "" });
  h.root.all().find((node) => node.tag === "button" && node.textContent.startsWith("合成管理员自己")).events.click(); await flush();
  assert.equal(h.button("封禁账号").disabled, true); assert.match(h.text(), /不能在此封禁自己/);
});

test("clipboard text is concealed when its expiry passes during a pending write", async () => {
  const clipboard = { ...view(), record: { ...view().record, content: { type: "clipboard", text: "到期的合成编辑正文", expires_at_ms: Date.now() + 60000, pinned: false } } };
  const h = await userWithRows(); await h.click("云剪贴板"); await h.respond("kind=clipboard", page([clipboard])); await h.click("编辑"); h.input("record-text", "到期时的合成新正文"); await h.submit();
  const timer = [...h.timers.values()].find((entry) => entry.delay > 20000); assert.ok(timer); clipboard.record.content.expires_at_ms = Date.now() - 1; timer.callback(); await flush();
  assert.equal(h.find((node) => node.attrs.id === "record-text"), undefined); assert.doesNotMatch(h.text(), /到期时的合成新正文/); assert.match(h.text(), /这条剪贴板已过期/);
  assert.equal(h.pending.filter((entry) => entry.options.method === "PUT").length, 1);
});

test("decryption failure never becomes a fresh empty-account setup flow", async () => {
  const h = harness(); await h.respond("/session", identity()); await h.respond("/records?", { error: "storage_unavailable" }, 503);
  assert.match(h.text(), /资料暂时无法载入/); assert.doesNotMatch(h.text(), /词库还是空的|尚未启用网页管理/); assert.equal(h.button("启用网页资料管理"), undefined);
});
