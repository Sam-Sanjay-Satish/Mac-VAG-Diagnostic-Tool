// macvagdiag web page. Plain JS, no framework. All text from the API goes in through textContent, never as HTML.
"use strict";

const POLL_MS = 500;
const LAST_SCAN_KEY = "macvagdiag.lastScan";

const $ = (id) => document.getElementById(id);

/** Creates an element; children are nodes or strings (strings become text nodes). */
function el(tag, attrs, ...children) {
  const node = document.createElement(tag);
  for (const [key, value] of Object.entries(attrs || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === "class") node.className = value;
    else node.setAttribute(key, value === true ? "" : String(value));
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

async function api(method, path) {
  const options = { method, headers: { Accept: "application/json" } };
  if (method === "POST") {
    options.headers["Content-Type"] = "application/json";
    options.body = "{}";
  }
  const response = await fetch(path, options);
  let body = null;
  try {
    body = await response.json();
  } catch (e) {
    body = null;
  }
  if (!response.ok) {
    const message = body && body.message ? body.message : "HTTP " + response.status;
    const error = new Error(message);
    error.status = response.status;
    error.body = body;
    throw error;
  }
  return body;
}

function showError(message) {
  const box = $("error");
  box.textContent = message || "";
  box.hidden = !message;
}

function setDemo(isDemo) {
  $("demo-banner").hidden = !isDemo;
}

// ---- status bar -----------------------------------------------------------------------------------------------

async function loadStatus() {
  try {
    const s = await api("GET", "/api/status");
    $("status-dot").className = "dot " + (s.adapterConnected ? "dot-ok" : "dot-off");
    const parts = [s.adapterConnected ? "Adapter connected" : "Adapter not connected"];
    if (s.demo) parts.push("demo car");
    parts.push("ignition: " + s.ignition.state.toLowerCase());
    parts.push("engine: " + s.engine.state.toLowerCase());
    $("status-text").textContent = parts.join("  ·  ");
    $("status-text").title = "Ignition: " + s.ignition.reason + "\nEngine: " + s.engine.reason;
    $("status-detail").textContent = s.adapterConnected ? "" : s.adapterDetail;
    setDemo(s.demo);
  } catch (e) {
    $("status-dot").className = "dot dot-unknown";
    $("status-text").textContent = "Status unavailable: " + e.message;
  }
}

// ---- Auto-Scan --------------------------------------------------------------------------------------------------

let polling = null;

async function runScan() {
  showError("");
  $("run-scan").disabled = true;
  try {
    const started = await api("POST", "/api/scans");
    remember(started.id);
    clearResults();
    await poll(started.id);
  } catch (e) {
    showError(e.message);
    $("run-scan").disabled = false;
  }
}

function remember(id) {
  try {
    sessionStorage.setItem(LAST_SCAN_KEY, id);
  } catch (e) {
    // storage blocked: the page still works, it just forgets the scan on reload
  }
}

function recalled() {
  try {
    return sessionStorage.getItem(LAST_SCAN_KEY);
  } catch (e) {
    return null;
  }
}

async function poll(id) {
  clearTimeout(polling);
  let job;
  try {
    job = await api("GET", "/api/scans/" + encodeURIComponent(id));
  } catch (e) {
    $("run-scan").disabled = false;
    $("scan-progress").hidden = true;
    if (e.status !== 404) showError(e.message);
    return;
  }
  render(job);
  if (job.state === "RUNNING") {
    $("run-scan").disabled = true;
    polling = setTimeout(() => poll(id), POLL_MS);
  } else {
    $("run-scan").disabled = false;
    loadStatus();
  }
}

function render(job) {
  if (job.demo) setDemo(true);
  const progress = $("scan-progress");
  progress.hidden = false;
  $("progress-bar").max = Math.max(job.totalModules, 1);
  $("progress-bar").value = job.completedModules;
  $("progress-text").textContent = job.lastMessage || "Starting...";
  if (job.state === "FAILED" && job.failure) showError(job.failure.message);
  renderModules(job.modules);
  renderSummary(job);
}

function clearResults() {
  $("results").replaceChildren();
  $("unreachable-list").replaceChildren();
  $("unreachable").hidden = true;
  $("summary").hidden = true;
}

// ---- results ----------------------------------------------------------------------------------------------------

const STATES = {
  OK: { cls: "ok", label: "OK" },
  FAULTS: { cls: "fault", label: "Fault" },
  NOT_RESPONDING: { cls: "off", label: "Not reachable" },
  ERROR: { cls: "warn", label: "Error" },
  INCOMPLETE: { cls: "warn", label: "Incomplete" },
  SKIPPED: { cls: "off", label: "Skipped" },
};

function renderModules(modules) {
  const results = $("results");
  const unreachable = $("unreachable-list");
  results.replaceChildren();
  unreachable.replaceChildren();
  for (const m of modules) {
    if (m.status === "NOT_RESPONDING") {
      unreachable.append(el("li", { title: m.notes.join("; ") }, m.address + " " + m.name));
    } else {
      results.append(moduleCard(m));
    }
  }
  $("unreachable").hidden = unreachable.children.length === 0;
}

function moduleCard(m) {
  const state = STATES[m.status] || STATES.ERROR;
  const card = el("article", { class: "module state-" + state.cls },
    el("div", { class: "module-head" },
      el("span", { class: "module-addr" }, m.address),
      el("span", { class: "module-name" }, m.name),
      el("span", { class: "badge badge-" + state.cls }, state.label),
      el("span", { class: "muted" }, m.status === "FAULTS" || m.status === "OK" ? "" : m.statusText),
      m.link ? el("span", { class: "module-link" }, m.link.description) : null));

  if (m.identification) card.append(identification(m.identification));
  if (m.status === "OK") card.append(el("p", { class: "muted" }, "No fault codes stored."));
  for (const f of m.faults) card.append(fault(f));
  const notes = [...m.notes];
  if (m.limitHit) notes.push("Limit: " + m.limitHit.message);
  if (!m.faultListComplete && m.faults.length > 0) notes.push("The fault list did not decode cleanly.");
  if (notes.length) card.append(el("ul", { class: "notes" }, notes.map((n) => el("li", {}, n))));
  return card;
}

function identification(id) {
  const facts = [
    ["Part No", id.partNumber],
    ["Component", id.systemName],
    ["Software", id.softwareVersion],
    ["Hardware", id.hardwareNumber],
    ["Coding", id.coding],
    ["VIN", id.vin],
  ].filter(([, value]) => value);
  const box = el("div", {});
  if (facts.length) {
    box.append(el("dl", { class: "facts" }, facts.map(([k, v]) => [el("dt", {}, k), el("dd", { class: "mono" }, v)])));
  }
  if (id.raw.length) {
    box.append(el("details", {},
      el("summary", {}, "Identification, raw (" + id.raw.length + ")"),
      el("div", { class: "body" }, rawTable(id.raw))));
  }
  return box;
}

function rawTable(values) {
  return el("table", { class: "raw" },
    el("thead", {}, el("tr", {}, el("th", {}, "ID"), el("th", {}, "Bytes"), el("th", {}, "As text"))),
    el("tbody", {}, values.map((v) => el("tr", {},
      el("td", { class: "mono" }, v.id),
      el("td", { class: "mono" }, v.hex),
      el("td", {}, v.text ? "“" + v.text + "”" : "")))));
}

function fault(f) {
  const ff = f.freezeFrame;
  const body = el("div", { class: "body" },
    el("dl", { class: "facts" },
      el("dt", {}, "Status"), el("dd", {}, el("code", {}, f.statusHex), " " + f.statusText),
      el("dt", {}, "Record"), el("dd", { class: "mono" }, f.record),
      el("dt", {}, "Protocol"), el("dd", {}, f.protocol),
      f.obdCode ? [el("dt", {}, "OBD code"), el("dd", {}, f.obdCode)] : null),
    f.statusFlags.length ? el("ul", { class: "chips" }, f.statusFlags.map((s) => el("li", {}, s))) : null,
    el("h3", {}, "Freeze frame"),
    freezeFrame(ff));
  return el("details", {},
    el("summary", {}, el("span", { class: "code" }, f.displayCode), "  ", el("span", { class: "muted" }, "status " + f.statusHex)),
    body);
}

function freezeFrame(ff) {
  if (ff.availability !== "READ") {
    const label = { NOT_AVAILABLE: "Not available", FAILED: "Not read", SKIPPED: "Skipped" }[ff.availability] || ff.availability;
    return el("p", { class: "muted" }, label + ": " + ff.message);
  }
  const box = el("div", {});
  if (ff.fields.length) {
    box.append(el("dl", { class: "facts" }, ff.fields.map((x) => [el("dt", {}, x.kind), el("dd", {}, x.value)])));
  }
  if (ff.records.length) {
    box.append(el("p", { class: "muted" }, "Raw records, not decoded yet:"), rawTable(ff.records));
  }
  if (ff.notes.length) box.append(el("ul", { class: "notes" }, ff.notes.map((n) => el("li", {}, n))));
  return box;
}

function renderSummary(job) {
  const r = job.result;
  if (!r) {
    $("summary").hidden = true;
    return;
  }
  $("summary").hidden = false;
  $("summary-title").textContent = (r.demo ? "Demo " : "") + "Auto-Scan " + (r.cancelled ? "(cancelled)" : "finished");
  const facts = [
    ["Started", new Date(job.startedAt).toLocaleString()],
    ["Duration", (r.durationMs / 1000).toFixed(1) + " s"],
    ["VIN", r.vin ? r.vin + (r.vinSource ? "  (from module " + r.vinSource + ")" : "") : "not read"],
    ["Modules", r.modulesChecked + " checked, " + r.modulesResponding + " responding, " + r.modulesWithFaults + " with faults"],
    ["Fault codes", String(r.faultCodes)],
  ];
  $("summary-facts").replaceChildren(...facts.flatMap(([k, v]) => [el("dt", {}, k), el("dd", {}, v)]));
  $("summary-notes").replaceChildren(...r.notes.map((n) => el("li", {}, n)));
  const report = "/api/scans/" + encodeURIComponent(job.id) + "/report.txt";
  $("report-link").href = report;
  $("report-masked-link").href = report + "?maskVin=true";
}

// ---- start --------------------------------------------------------------------------------------------------------

document.addEventListener("DOMContentLoaded", () => {
  $("run-scan").addEventListener("click", runScan);
  $("status-refresh").addEventListener("click", loadStatus);
  loadStatus();
  const last = recalled();
  if (last) poll(last);
});
