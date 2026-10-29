/* MaintiFlow Pro – interface web servie par le serveur (téléphone maître ou PC).
   JavaScript sans dépendance. Tous les droits sont vérifiés par le serveur : l'interface n'affiche
   que ce que le serveur autorise (allowed_actions, editable_fields). */
'use strict';

const API = '/api/v1';
const S = { token: null, user: null, refs: null, idle: 30, lastActivity: Date.now(), notifTimer: null };

// ------------------------------------------------------------------ utilitaires
function store(k, v) {
  try { if (v === undefined) return sessionStorage.getItem(k); if (v === null) sessionStorage.removeItem(k); else sessionStorage.setItem(k, v); } catch (_) { return null; }
  return null;
}

/** Construit un élément : h('div', {class: 'x', onclick: f}, 'texte', enfant…). Aucun HTML injecté. */
function h(tag, attrs, ...children) {
  const el = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v === null || v === undefined || v === false) continue;
    if (k.startsWith('on')) el.addEventListener(k.slice(2), v);
    else if (k === 'class') el.className = v;
    else if (k === 'value') el.value = v;
    else if (k === 'checked' || k === 'disabled' || k === 'selected' || k === 'hidden' || k === 'multiple' || k === 'required') el[k] = !!v;
    else el.setAttribute(k, v === true ? '' : v);
  }
  for (const c of children.flat(Infinity)) {
    if (c === null || c === undefined || c === false) continue;
    el.append(c instanceof Node ? c : document.createTextNode(String(c)));
  }
  return el;
}
const $ = (sel, root) => (root || document).querySelector(sel);
const app = () => $('#app');

function toast(msg, err) {
  const t = $('#toast');
  t.textContent = msg; t.className = 'toast' + (err ? ' err' : ''); t.hidden = false;
  clearTimeout(toast.t); toast.t = setTimeout(() => { t.hidden = true; }, err ? 6000 : 3000);
}

function fmtDate(iso) {
  if (!iso) return '—';
  const d = new Date(iso);
  return d.toLocaleString('fr-FR', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
}
function fmtDur(s) {
  if (s === null || s === undefined || s === '') return '—';
  s = Math.round(Number(s));
  if (s < 60) return s + ' s';
  const m = Math.floor(s / 60), hh = Math.floor(m / 60), d = Math.floor(hh / 24);
  if (d > 0) return d + ' j ' + (hh % 24) + ' h';
  if (hh > 0) return hh + ' h ' + String(m % 60).padStart(2, '0');
  return m + ' min';
}
function toLocalInput(iso) {
  if (!iso) return '';
  const d = new Date(iso); const p = n => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}`;
}
function fromLocalInput(v) { return v ? new Date(v).toISOString() : null; }
function uuid() {
  if (crypto.randomUUID) return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16)); b[6] = (b[6] & 15) | 64; b[8] = (b[8] & 63) | 128;
  const x = [...b].map(v => v.toString(16).padStart(2, '0')).join('');
  return `${x.slice(0, 8)}-${x.slice(8, 12)}-${x.slice(12, 16)}-${x.slice(16, 20)}-${x.slice(20)}`;
}
const label = (group, code) => (S.refs && S.refs.labels[group] && S.refs.labels[group][code]) || code || '—';
const role = (...r) => S.user && r.includes(S.user.role);

// ------------------------------------------------------------------ appels au serveur
class ApiError extends Error { constructor(status, msg) { super(msg); this.status = status; } }

async function api(method, path, body, opts = {}) {
  const headers = {};
  if (S.token) headers.Authorization = 'Bearer ' + S.token;
  if (opts.background) headers['X-Background'] = '1';
  let payload;
  if (body instanceof FormData) payload = body;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  if (!opts.background) S.lastActivity = Date.now();
  let r;
  try { r = await fetch(API + path, { method, headers, body: payload }); } catch (_) {
    throw new ApiError(0, 'Serveur injoignable : vérifiez le Wi-Fi et le téléphone maître.');
  }
  if (r.status === 401 && S.token && path !== '/auth/login') {
    const msg = (await r.json().catch(() => ({}))).detail || 'Session expirée';
    logout(msg);
    throw new ApiError(401, msg);
  }
  if (opts.blob) {
    if (!r.ok) throw new ApiError(r.status, (await r.json().catch(() => ({}))).detail || 'Erreur ' + r.status);
    return { blob: await r.blob(), name: (r.headers.get('content-disposition') || '').match(/filename="?([^";]+)/)?.[1] };
  }
  if (r.status === 204) return null;
  const data = await r.json().catch(() => ({}));
  if (!r.ok) {
    if (r.status === 403 && data.detail === 'PASSWORD_CHANGE_REQUIRED') { go('#/mot-de-passe'); }
    throw new ApiError(r.status, typeof data.detail === 'string' ? data.detail : 'Erreur ' + r.status);
  }
  return data;
}

async function download(path, open) {
  try {
    const { blob, name } = await api('GET', path, undefined, { blob: true });
    const url = URL.createObjectURL(blob);
    const a = h('a', { href: url, download: open ? null : (name || 'export'), target: open ? '_blank' : null, rel: 'noopener' });
    document.body.append(a); a.click(); a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 60000);
  } catch (e) { toast(e.message, true); }
}

async function guard(fn) {
  try { return await fn(); } catch (e) { if (e.status !== 401) toast(e.message, true); return undefined; }
}

// ------------------------------------------------------------------ session, inactivité, notifications
function logout(msg) {
  if (S.token) api('POST', '/auth/logout').catch(() => {});
  S.token = null; S.user = null; S.refs = null;
  store('mf_token', null);
  clearInterval(S.notifTimer);
  $('#top').hidden = true; $('#notif-panel').hidden = true;
  go('#/connexion');
  if (msg) setTimeout(() => toast(msg, true), 50);
}

['click', 'keydown', 'touchstart'].forEach(ev => document.addEventListener(ev, () => { S.lastActivity = Date.now(); }, { passive: true }));
setInterval(() => {
  if (S.token && S.idle > 0 && Date.now() - S.lastActivity > S.idle * 60000) {
    logout(`Déconnexion automatique après ${S.idle} minutes d'inactivité.`);
  }
}, 15000);

async function pollNotifications() {
  if (!S.token || !S.user || S.user.must_change_password) return;
  try {
    const n = await api('GET', '/notifications?limit=30', undefined, { background: true });
    const c = $('#bell-count');
    c.hidden = !n.unread; c.textContent = n.unread > 99 ? '99+' : n.unread;
    S.notifs = n.items;
    if (!$('#notif-panel').hidden) renderNotifPanel();
  } catch (_) { /* hors ligne : on réessaie au prochain tour */ }
}

function renderNotifPanel() {
  const p = $('#notif-panel');
  p.replaceChildren(
    h('div', { class: 'row', style: 'padding:10px 14px' },
      h('b', {}, 'Notifications'),
      h('button', { class: 'secondary', style: 'flex:0 0 auto', onclick: async () => { await guard(() => api('POST', '/notifications/read', {})); pollNotifications(); } }, 'Tout marquer comme lu')),
    (S.notifs || []).length === 0 ? h('p', { class: 'muted', style: 'padding:0 14px' }, 'Aucune notification.') : null,
    (S.notifs || []).map(n => h('div', {
      class: 'notif' + (n.read ? '' : ' unread'),
      onclick: async () => {
        await guard(() => api('POST', '/notifications/read', { ids: [n.id] }));
        p.hidden = true; pollNotifications();
        if (n.intervention_id) go('#/panne/' + n.intervention_id);
      },
    }, h('div', { class: 't' }, n.title), h('div', { class: 'small' }, n.body), h('div', { class: 'small muted' }, fmtDate(n.created_at)))),
  );
}

$('#bell').addEventListener('click', () => { const p = $('#notif-panel'); p.hidden = !p.hidden; if (!p.hidden) renderNotifPanel(); });
$('#logout').addEventListener('click', () => logout());

async function startSession(login) {
  S.token = login.access_token; S.user = login.user; S.idle = login.idle_minutes ?? 30;
  store('mf_token', S.token); store('mf_idle', String(S.idle));
  S.lastActivity = Date.now();
  await afterAuth();
}

async function afterAuth() {
  if (S.user.must_change_password) { go('#/mot-de-passe'); return; }
  S.refs = await api('GET', '/referentials');
  $('#top').hidden = false;
  $('#who-name').textContent = `${S.user.full_name} (${S.user.matricule}) · ${S.user.role_label}`;
  renderNav();
  clearInterval(S.notifTimer);
  pollNotifications();
  S.notifTimer = setInterval(pollNotifications, 20000);
  if (!location.hash || location.hash.startsWith('#/connexion') || location.hash.startsWith('#/mot-de-passe')) {
    go(role('ADMIN') ? '#/admin/users' : '#/pannes');
  } else route();
}

function renderNav() {
  const items = [['#/pannes', 'Pannes']];
  if (!role('ADMIN', 'LOGISTIQUE')) items.push(['#/nouvelle', '+ Déclarer (page rose)']);
  if (!role('OPERATEUR')) items.push(['#/tableau-de-bord', 'Tableau de bord']);
  if (role('ADMIN')) items.push(['#/admin/users', 'Administration']);
  items.push(['#/mot-de-passe', 'Mot de passe']);
  const cur = location.hash.split('?')[0];
  $('#nav').replaceChildren(...items.map(([href, t]) => h('a', { href, class: cur.startsWith(href.split('/').slice(0, 2).join('/')) ? 'on' : null }, t)));
}

// ------------------------------------------------------------------ routage
function go(hash) { if (location.hash === hash) route(); else location.hash = hash; }
window.addEventListener('hashchange', route);

async function route() {
  const [path, query] = location.hash.replace(/^#/, '').split('?');
  const parts = path.split('/').filter(Boolean);
  const params = new URLSearchParams(query || '');
  $('#dialog-back').hidden = true;
  if (!S.token) { if (parts[0] !== 'connexion') { location.hash = '#/connexion'; return; } return viewLogin(); }
  if (parts[0] === 'mot-de-passe') return viewPassword();
  if (!S.refs) return; // afterAuth() rappellera route()
  renderNav();
  try {
    switch (parts[0]) {
      case 'nouvelle': return await viewNew();
      case 'panne': return await viewDetail(parts[1]);
      case 'machine': return await viewMachine(parts[1]);
      case 'tableau-de-bord': return await viewDashboard(params);
      case 'admin': return await viewAdmin(parts[1] || 'users');
      default: return await viewList(params);
    }
  } catch (e) {
    if (e.status === 401) return;
    app().replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Erreur'), h('p', { class: 'err-text' }, e.message),
      h('a', { href: '#/pannes', class: 'btn secondary' }, 'Retour aux pannes')));
  }
}

// ------------------------------------------------------------------ connexion
function viewLogin() {
  const err = h('p', { class: 'err-text', hidden: true });
  const form = h('form', { class: 'card login', onsubmit: async e => {
    e.preventDefault();
    err.hidden = true;
    try {
      const r = await api('POST', '/auth/login', { username: form.matricule.value.trim(), password: form.password.value, device: 'Navigateur web' });
      await startSession(r);
    } catch (ex) { err.textContent = ex.message; err.hidden = false; }
  } },
  h('h1', {}, 'MaintiFlow Pro'),
  h('p', { class: 'muted' }, 'Gestion des pannes et de la maintenance'),
  h('label', { for: 'm' }, 'Matricule'), h('input', { id: 'm', name: 'matricule', autocomplete: 'username', required: true, autofocus: true }),
  h('label', { for: 'p', style: 'margin-top:10px' }, 'Mot de passe'), h('input', { id: 'p', name: 'password', type: 'password', autocomplete: 'current-password', required: true }),
  err,
  h('div', { class: 'actions' }, h('button', { type: 'submit' }, 'Se connecter')));
  app().replaceChildren(form);
}

function viewPassword() {
  const err = h('p', { class: 'err-text', hidden: true });
  const forced = S.user && S.user.must_change_password;
  const form = h('form', { class: 'card login', onsubmit: async e => {
    e.preventDefault();
    if (form.n1.value !== form.n2.value) { err.textContent = 'Les deux saisies ne correspondent pas.'; err.hidden = false; return; }
    try {
      await api('POST', '/auth/change-password', { current_password: form.cur.value, new_password: form.n1.value });
      S.user.must_change_password = false;
      toast('Mot de passe modifié.');
      await afterAuth();
      go('#/pannes');
    } catch (ex) { err.textContent = ex.message; err.hidden = false; }
  } },
  h('h1', {}, 'Changer le mot de passe'),
  forced ? h('p', {}, 'Première connexion : choisissez votre mot de passe personnel.') : null,
  h('p', { class: 'muted small' }, '8 caractères minimum, avec au moins une lettre et un chiffre.'),
  h('label', {}, 'Mot de passe actuel'), h('input', { name: 'cur', type: 'password', autocomplete: 'current-password', required: true }),
  h('label', { style: 'margin-top:10px' }, 'Nouveau mot de passe'), h('input', { name: 'n1', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 }),
  h('label', { style: 'margin-top:10px' }, 'Confirmer'), h('input', { name: 'n2', type: 'password', autocomplete: 'new-password', required: true, minlength: 8 }),
  err,
  h('div', { class: 'actions' }, h('button', { type: 'submit' }, 'Enregistrer'), forced ? h('button', { type: 'button', class: 'secondary', onclick: () => logout() }, 'Annuler') : null));
  app().replaceChildren(form);
}

// ------------------------------------------------------------------ champs de formulaire
function options(list, value, empty, text) {
  return [empty !== undefined ? h('option', { value: '' }, empty) : null,
    ...list.map(o => h('option', { value: o.id ?? o[0], selected: String(o.id ?? o[0]) === String(value ?? '') }, text ? text(o) : (o.code ? `${o.code} – ${o.name}` : (o.name ?? o[1]))))];
}
function field(lbl, input, wide) { return h('div', { style: wide ? 'grid-column:1/-1' : null }, h('label', {}, lbl), input); }
function labelPairs(group) { return Object.entries(S.refs.labels[group] || {}); }

function statusBadge(st, lbl) { return h('span', { class: 'badge st-' + st }, lbl || label('statuses', st)); }
function priorityBadge(p, lbl) { return h('span', { class: 'badge pr-' + p }, lbl || label('priorities', p)); }

// ------------------------------------------------------------------ liste et recherche
const WORK = ['EN_DIAGNOSTIC', 'INTERVENTION_MAINTENANCE', 'INTERVENTION_METHODE', 'EN_ATTENTE_PIECE', 'EN_ATTENTE_QUALITE', 'RESOLUE'];

async function viewList(params) {
  const R = S.refs;
  const f = Object.fromEntries(params.entries());
  if (!params.toString()) f.open_only = 'true';
  const form = h('form', { class: 'card no-print', onsubmit: e => {
    e.preventDefault();
    const q = new URLSearchParams();
    for (const el of form.elements) {
      if (!el.name) continue;
      if (el.type === 'checkbox') { if (el.checked) q.set(el.name, 'true'); } else if (el.value) q.set(el.name, el.value);
    }
    if (!q.has('open_only')) q.set('open_only', 'false');
    go('#/pannes?' + q.toString());
  } },
  h('div', { class: 'row' },
    field('Recherche (n°, machine, matricule, texte)', h('input', { name: 'q', value: f.q || '', placeholder: 'BI-2026-… ou M1 ou description' })),
    field('Statut', h('select', { name: 'status' }, options(labelPairs('statuses'), f.status, 'Tous'))),
    field('Ligne', h('select', { name: 'line_id' }, options(R.lines, f.line_id, 'Toutes'))),
    field('Machine', h('select', { name: 'machine_id' }, options(R.machines, f.machine_id, 'Toutes'))),
  ),
  h('details', { open: ['poste_id', 'category_id', 'priority', 'service', 'date_from', 'date_to', 'matricule', 'cable_ref_id'].some(k => f[k]) },
    h('summary', { style: 'cursor:pointer;margin:10px 0' }, 'Plus de filtres'),
    h('div', { class: 'row' },
      field('Poste', h('select', { name: 'poste_id' }, options(R.postes, f.poste_id, 'Tous'))),
      field('Catégorie', h('select', { name: 'category_id' }, options(R.categories, f.category_id, 'Toutes'))),
      field('Priorité', h('select', { name: 'priority' }, options(labelPairs('priorities'), f.priority, 'Toutes'))),
      field('Service', h('select', { name: 'service' }, options(labelPairs('services'), f.service, 'Tous'))),
      field('Référence câble', h('select', { name: 'cable_ref_id' }, options(R.cable_refs, f.cable_ref_id, 'Toutes'))),
      field('Matricule (déclarant ou intervenant)', h('input', { name: 'matricule', value: f.matricule || '' })),
      field('Du', h('input', { name: 'date_from', type: 'date', value: f.date_from || '' })),
      field('Au', h('input', { name: 'date_to', type: 'date', value: f.date_to || '' })),
    )),
  h('div', { class: 'row', style: 'margin-top:8px' },
    h('label', { class: 'check' }, h('input', { type: 'checkbox', name: 'open_only', checked: f.open_only === 'true' }), 'En cours seulement'),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', name: 'recurring', checked: f.recurring === 'true' }), 'Récurrentes'),
    h('label', { class: 'check' }, h('input', { type: 'checkbox', name: 'mine', checked: f.mine === 'true' }), 'Mes pannes'),
    h('button', { type: 'submit' }, 'Rechercher'),
    h('button', { type: 'button', class: 'secondary', onclick: () => { const q = new URLSearchParams(f); q.delete('offset'); download('/interventions/export.xlsx?' + q); } }, 'Export Excel'),
  ));
  const q = new URLSearchParams(f); q.set('limit', '100');
  const data = await api('GET', '/interventions?' + q);
  const list = h('div', { class: 'list' }, data.items.map(itemCard));
  app().replaceChildren(
    h('div', { class: 'row', style: 'align-items:center;margin-bottom:10px' }, h('h1', { style: 'margin:0' }, 'Pannes'),
      !role('ADMIN', 'LOGISTIQUE') ? h('a', { href: '#/nouvelle', class: 'btn pink', style: 'flex:0 0 auto;background:var(--pink)' }, '+ Déclarer une panne') : null),
    form,
    h('p', { class: 'muted' }, `${data.total} panne(s)` + (data.total > data.items.length ? ` – ${data.items.length} affichées, affinez la recherche` : '')),
    data.items.length ? list : h('div', { class: 'card muted' }, 'Aucune panne pour ces critères.'));
}

function itemCard(iv) {
  const kind = iv.status === 'CLOTURE' ? 'done' : (iv.priority === 'CRITIQUE' || iv.late) ? 'urgent' : WORK.includes(iv.status) ? 'work' : '';
  return h('a', { class: 'item ' + kind, href: '#/panne/' + iv.id },
    h('div', {},
      h('div', {}, h('span', { class: 'n' }, iv.number), ' · ', iv.title || iv.category_name),
      h('div', { class: 'meta' }, [iv.line_code, iv.poste_code, `${iv.machine_code} ${iv.machine_name}`].filter(Boolean).join(' · ')),
      h('div', { class: 'meta' }, `Déclarée ${fmtDate(iv.declared_at)} par ${iv.reported_by?.full_name || '—'} (${iv.reported_by?.matricule || ''})`,
        iv.assigned_to?.full_name ? ` · ${label('services', iv.service)} : ${iv.assigned_to.full_name}` : '')),
    h('div', { class: 'tags' }, statusBadge(iv.status, iv.status_label), priorityBadge(iv.priority, iv.priority_label),
      iv.production_stopped ? h('span', { class: 'badge pr-HAUTE' }, 'Machine arrêtée') : null,
      iv.late ? h('span', { class: 'badge late' }, 'En retard') : null,
      iv.recurring ? h('span', { class: 'badge' }, 'Récurrente') : null));
}

// ------------------------------------------------------------------ page rose : déclaration
function machineSelectors(R, values, disabled) {
  const line = h('select', { name: 'line_id', disabled }, options(R.lines, values.line_id, 'Choisir la ligne'));
  const poste = h('select', { name: 'poste_id', disabled });
  const machine = h('select', { name: 'machine_id', required: true, disabled });
  function refill() {
    const l = line.value;
    poste.replaceChildren(...options(R.postes.filter(p => !l || String(p.line_id) === l), values.poste_id, '—'));
    const p = poste.value;
    machine.replaceChildren(...options(R.machines.filter(m => (!l || String(m.line_id) === l) && (!p || !m.poste_id || String(m.poste_id) === p)), values.machine_id, 'Choisir la machine'));
  }
  line.addEventListener('change', () => { values.poste_id = null; values.machine_id = null; refill(); });
  poste.addEventListener('change', () => { values.poste_id = poste.value; refill(); });
  machine.addEventListener('change', () => {
    const m = R.machines.find(x => String(x.id) === machine.value);
    if (m && m.poste_id && !poste.value) { values.machine_id = m.id; values.poste_id = m.poste_id; refill(); }
  });
  refill();
  return { line, poste, machine };
}

async function viewNew() {
  const R = S.refs;
  const values = {};
  if (R.lines.length === 1) values.line_id = R.lines[0].id;
  const sel = machineSelectors(R, values, false);
  const cat = h('select', { name: 'category_id', required: true }, options(R.categories, null, 'Choisir la catégorie'));
  const prio = h('select', { name: 'priority' }, options(labelPairs('priorities'), null, 'Selon la catégorie'));
  const stopped = h('input', { type: 'checkbox', name: 'production_stopped' });
  const stopAt = h('input', { type: 'datetime-local', name: 'stop_start_at', value: toLocalInput(new Date().toISOString()) });
  const service = h('select', { name: 'service' }, options(labelPairs('services'), null, 'Non affectée (à prendre en charge)'));
  const tech = h('select', { name: 'assigned_to_id' }, options(R.technicians, null, '—', t => `${t.full_name} (${t.matricule || t.username}) – ${label('roles', t.role)}`));
  const canAssign = role('CHEF_LIGNE', 'CHEF_EQUIPE', 'RESPONSABLE');
  const clientUuid = uuid();
  const form = h('form', { class: 'page pink', onsubmit: async e => {
    e.preventDefault();
    const body = {
      client_uuid: clientUuid,
      machine_id: Number(sel.machine.value), category_id: Number(cat.value),
      poste_id: sel.poste.value ? Number(sel.poste.value) : null,
      cable_ref_id: form.cable_ref_id.value ? Number(form.cable_ref_id.value) : null,
      title: form.title.value.trim() || null, description: form.description.value.trim(),
      production_stopped: stopped.checked,
      stop_start_at: stopped.checked ? fromLocalInput(stopAt.value) : null,
    };
    if (prio.value) body.priority = prio.value;
    if (canAssign && tech.value) body.assigned_to_id = Number(tech.value);
    else if (canAssign && service.value) body.service = service.value;
    const btn = form.querySelector('button[type=submit]'); btn.disabled = true;
    try {
      const iv = await api('POST', '/interventions', body);
      toast(`Panne ${iv.number} déclarée.`);
      go('#/panne/' + iv.id);
    } catch (ex) { toast(ex.message, true); btn.disabled = false; }
  } },
  h('div', { class: 'ph' }, h('h2', {}, 'PAGE ROSE – Déclaration de panne'), h('span', { class: 'muted small' }, `Déclarant : ${S.user.full_name} (${S.user.matricule}) · ${S.user.role_label}`)),
  h('div', { class: 'grid' },
    field('Ligne', sel.line), field('Poste (A, B, C…)', sel.poste), field('Machine *', sel.machine),
    field('Référence câble', h('select', { name: 'cable_ref_id' }, options(R.cable_refs, null, '—'))),
    field('Catégorie de panne *', cat), field('Priorité', prio),
    field('Nom de la panne', h('input', { name: 'title', maxlength: 160, placeholder: 'Ex. : bourrage en sortie' }), true),
    field('Description *', h('textarea', { name: 'description', required: true, minlength: 3, placeholder: 'Ce qui se passe, depuis quand, ce qui a été essayé' }), true),
    h('label', { class: 'check' }, stopped, 'Machine arrêtée'),
    field('Heure d\'arrêt', stopAt),
    canAssign ? field('Affecter au service', service) : null,
    canAssign ? field('ou à une personne', tech) : null,
  ),
  h('div', { class: 'actions' }, h('button', { type: 'submit', class: 'pink' }, 'Enregistrer la déclaration'), h('a', { href: '#/pannes', class: 'btn secondary' }, 'Annuler')));
  app().replaceChildren(h('h1', {}, 'Déclarer une panne'), form);
}

// ------------------------------------------------------------------ détail : page rose + page jaune
const NEEDS_REASON = ['transfer', 'pause', 'part_unavailable', 'quality_reject', 'close', 'reject_resolution', 'cancel', 'reopen'];
const ACTION_STYLE = { finish: 'ok', close: 'ok', quality_validate: 'ok', validate_maintenance: 'ok', part_delivered: 'ok',
  cancel: 'danger', reject_resolution: 'danger', quality_reject: 'danger', part_unavailable: 'danger', reopen: 'danger',
  accept: 'yellow', start: 'yellow', flag_recurring: 'secondary', pause: 'secondary', arrive: 'secondary', resume: 'secondary' };

async function viewDetail(id) {
  const iv = await api('GET', '/interventions/' + encodeURIComponent(id));
  const R = S.refs;
  const ed = new Set(iv.editable_fields);
  const pending = {};
  const mark = name => ev => { const el = ev.target; pending[name] = el.type === 'checkbox' ? el.checked : el.value; saveBtn.hidden = false; };

  function input(name, value, kind, extra) {
    const dis = !ed.has(name);
    if (kind === 'textarea') return h('textarea', { name, disabled: dis, oninput: mark(name), ...extra }, value || '');
    if (kind === 'select') return h('select', { name, disabled: dis, onchange: mark(name) }, extra);
    if (kind === 'check') return h('input', { type: 'checkbox', name, checked: !!value, disabled: dis, onchange: mark(name) });
    if (kind === 'datetime') return dis ? h('input', { value: fmtDate(value), disabled: true })
      : h('input', { type: 'datetime-local', name, value: toLocalInput(value), onchange: mark(name) });
    return h('input', { name, value: value || '', disabled: dis, oninput: mark(name), ...extra });
  }

  const saveBtn = h('button', { hidden: true, onclick: async () => {
    const body = { version: iv.version };
    for (const [k, v] of Object.entries(pending)) {
      if (k.endsWith('_id')) body[k] = v ? Number(v) : null;
      else if (k === 'stop_start_at') body[k] = fromLocalInput(v);
      else if (typeof v === 'string') body[k] = v.trim() || null;
      else body[k] = v;
    }
    const r = await guard(() => api('PATCH', '/interventions/' + iv.id, body));
    if (r) { toast('Enregistré.'); viewDetail(iv.id); }
  } }, 'Enregistrer les modifications');

  const machineTools = R.tools.filter(t => !t.machine_id || t.machine_id === iv.machine_id);
  const causes = R.causes.filter(c => c.category_id === iv.category_id);
  const pink = h('section', { class: 'page pink' },
    h('div', { class: 'ph' }, h('h2', {}, 'PAGE ROSE – Déclaration'), h('span', { class: 'small' }, `N° ${iv.number}`)),
    h('div', { class: 'kv' },
      h('div', {}, h('b', {}, 'Ligne / poste / machine'), [iv.line_code, iv.poste?.code, `${iv.machine_code} – ${iv.machine_name}`].filter(Boolean).join(' · '),
        ' ', h('a', { href: '#/machine/' + iv.machine_id, class: 'small' }, 'historique machine')),
      h('div', {}, h('b', {}, 'Déclarant'), `${iv.reported_by?.full_name || '—'} (${iv.reported_by?.matricule || ''}) · ${iv.reporter_role_label || ''}`),
      h('div', {}, h('b', {}, 'Déclarée le'), fmtDate(iv.declared_at)),
      h('div', {}, h('b', {}, 'Priorité'), ed.has('priority') ? input('priority', null, 'select', options(labelPairs('priorities'), iv.priority)) : priorityBadge(iv.priority, iv.priority_label)),
    ),
    h('div', { class: 'grid', style: 'margin-top:10px' },
      field('Catégorie', input('category_id', null, 'select', options(R.categories, iv.category_id, null))),
      field('Poste', input('poste_id', null, 'select', options(R.postes.filter(p => p.line_id === iv.line_id), iv.poste_id, '—'))),
      field('Référence câble', input('cable_ref_id', null, 'select', options(R.cable_refs, iv.cable_ref_id, '—'))),
      field('Nom de la panne', input('title', iv.title)),
      field('Description', input('description', iv.description, 'textarea'), true),
      h('label', { class: 'check' }, input('production_stopped', iv.production_stopped, 'check'), 'Machine arrêtée'),
      field('Arrêt depuis', input('stop_start_at', iv.stop_start_at, 'datetime')),
      iv.stop_end_at ? field('Redémarrée le', h('input', { value: fmtDate(iv.stop_end_at), disabled: true })) : null,
    ),
    photos(iv, 'PROBLEME'));

  const yellow = h('section', { class: 'page yellow' },
    h('div', { class: 'ph' }, h('h2', {}, 'PAGE JAUNE – Intervention et clôture'), h('span', { class: 'small' }, `N° ${iv.number}`)),
    h('div', { class: 'kv' },
      h('div', {}, h('b', {}, 'Service'), label('services', iv.service)),
      h('div', {}, h('b', {}, 'Intervenant'), iv.assigned_to?.full_name ? `${iv.assigned_to.full_name} (${iv.assigned_to.matricule})` : 'Non affectée'),
      h('div', {}, h('b', {}, 'Prise en charge'), fmtDate(iv.accepted_at)),
      h('div', {}, h('b', {}, 'Début / fin d\'intervention'), `${fmtDate(iv.started_at)} → ${fmtDate(iv.repaired_at)}`),
    ),
    h('div', { class: 'grid', style: 'margin-top:10px' },
      field('Type d\'intervention', input('intervention_type_id', null, 'select', options(R.intervention_types, iv.intervention_type_id, '—'))),
      field('Outil / posage', input('tool_id', null, 'select', options(machineTools, iv.tool_id, '—'))),
      field('Diagnostic', input('diagnosis', iv.diagnosis, 'textarea'), true),
      field('Cause (liste)', input('cause_id', null, 'select', options(causes, iv.cause_id, '—', c => c.name))),
      field('Cause (détail)', input('root_cause', iv.root_cause)),
      field('Actions réalisées', input('corrective_action', iv.corrective_action, 'textarea'), true),
      field('Résultat des tests', input('test_result', iv.test_result)),
      field('État final de la machine', input('machine_final_state', null, 'select', options(labelPairs('machine_states'), iv.machine_final_state, '—'))),
      field('Observations', input('observations', iv.observations, 'textarea'), true),
      iv.requires_quality || iv.quality_observations || ed.has('quality_observations') ? field('Observations Qualité', input('quality_observations', iv.quality_observations, 'textarea'), true) : null,
    ),
    partsBlock(iv),
    h('div', { class: 'kv', style: 'margin-top:10px' },
      iv.requires_quality ? h('div', {}, h('b', {}, 'Validation Qualité'), iv.quality_validated_at ? `${fmtDate(iv.quality_validated_at)} – ${iv.quality_by?.full_name || ''}` : 'Requise') : null,
      h('div', {}, h('b', {}, 'Clôture'), iv.closed_at ? `${fmtDate(iv.closed_at)} – ${iv.closed_by?.full_name || ''} (${iv.closed_by?.matricule || ''})` : '—'),
      iv.final_comment ? h('div', {}, h('b', {}, 'Justification de clôture'), iv.final_comment) : null,
      iv.cancel_reason ? h('div', {}, h('b', {}, 'Motif d\'annulation'), iv.cancel_reason) : null,
    ),
    photos(iv, 'TRAVAUX'));

  const acts = h('div', { class: 'actions no-print' }, iv.allowed_actions.map(a =>
    h('button', { class: ACTION_STYLE[a] || '', onclick: () => actionDialog(iv, a, pending) }, label('actions', a))));

  app().replaceChildren(
    h('div', { class: 'ivhead' },
      h('a', { href: '#/pannes', class: 'btn secondary no-print' }, '← Pannes'),
      h('span', { class: 'num' }, iv.number), statusBadge(iv.status, iv.status_label), priorityBadge(iv.priority, iv.priority_label),
      iv.late ? h('span', { class: 'badge late' }, 'Prise en charge en retard') : null,
      iv.recurring ? h('span', { class: 'badge' }, 'Récurrente') : null,
      iv.locked ? h('span', { class: 'badge st-CLOTURE' }, 'Figée (non modifiable)') : null,
      h('span', { style: 'flex:1' }),
      h('button', { class: 'secondary no-print', onclick: () => download('/interventions/' + iv.id + '/pdf', true) }, iv.locked ? 'PDF archivé' : 'PDF provisoire'),
      h('button', { class: 'secondary no-print', onclick: () => window.print() }, 'Imprimer')),
    acts, h('div', { class: 'no-print' }, saveBtn),
    pink, yellow, historyBlock(iv));
}

function photos(iv, group) {
  const list = iv.attachments.filter(a => group === 'PROBLEME' ? a.kind === 'PROBLEME' : a.kind !== 'PROBLEME');
  const box = h('div', { class: 'row', style: 'margin-top:10px' });
  list.forEach(a => {
    const img = h('img', { alt: 'Photo', style: 'max-width:160px;max-height:120px;border-radius:6px;cursor:pointer' });
    api('GET', '/attachments/' + a.id, undefined, { blob: true }).then(({ blob }) => { img.src = URL.createObjectURL(blob); img.addEventListener('click', () => window.open(img.src, '_blank')); }).catch(() => {});
    box.append(h('div', { style: 'flex:0 0 auto' }, img, h('div', { class: 'small muted' }, a.kind)));
  });
  const kinds = group === 'PROBLEME' ? ['PROBLEME'] : ['AVANT', 'APRES'];
  const canUpload = !iv.locked && (group === 'PROBLEME' ? (iv.editable_fields.includes('production_stopped') || iv.reported_by?.id === S.user.id) : iv.editable_fields.includes('corrective_action'));
  if (canUpload) {
    kinds.forEach(k => {
      const file = h('input', { type: 'file', accept: 'image/jpeg,image/png,image/webp', capture: 'environment', hidden: true, onchange: async () => {
        if (!file.files[0]) return;
        const fd = new FormData(); fd.append('kind', k); fd.append('file', file.files[0]);
        const r = await guard(() => api('POST', `/interventions/${iv.id}/attachments`, fd));
        if (r) { toast('Photo ajoutée.'); viewDetail(iv.id); }
      } });
      box.append(h('div', { style: 'flex:0 0 auto' }, file, h('button', { class: 'secondary', onclick: () => file.click() },
        k === 'PROBLEME' ? '+ Photo du problème' : k === 'AVANT' ? '+ Photo avant' : '+ Photo après')));
    });
  }
  return list.length || canUpload ? h('div', {}, h('h3', {}, 'Photos'), box) : null;
}

function partsBlock(iv) {
  const out = h('div', {});
  if (iv.part_requests.length) {
    out.append(h('h3', {}, 'Demandes de pièces (Logistique)'), h('div', { class: 'table-wrap' }, h('table', {},
      h('tr', {}, h('th', {}, 'Référence'), h('th', {}, 'Désignation'), h('th', {}, 'Qté'), h('th', {}, 'Statut'), h('th', {}, 'Demandée'), h('th', {}, 'Traitée')),
      iv.part_requests.map(p => h('tr', {}, h('td', {}, p.reference), h('td', {}, p.designation || ''), h('td', {}, p.quantity),
        h('td', {}, p.status), h('td', {}, `${fmtDate(p.requested_at)} ${p.requested_by || ''}`), h('td', {}, p.handled_at ? `${fmtDate(p.handled_at)} ${p.handled_by || ''}` : '—'))))));
  }
  const canAdd = !iv.locked && iv.editable_fields.includes('corrective_action');
  if (iv.parts.length || canAdd) {
    out.append(h('h3', {}, 'Pièces utilisées'));
    if (iv.parts.length) {
      out.append(h('div', { class: 'table-wrap' }, h('table', {}, h('tr', {}, h('th', {}, 'Référence'), h('th', {}, 'Désignation'), h('th', {}, 'Quantité'), h('th', {})),
        iv.parts.map(p => h('tr', {}, h('td', {}, p.reference), h('td', {}, p.designation), h('td', {}, `${p.quantity} ${p.unit || ''}`),
          h('td', {}, canAdd ? h('button', { class: 'secondary', onclick: async () => { if (await guard(() => api('DELETE', `/interventions/${iv.id}/parts/${p.id}`)) !== undefined) viewDetail(iv.id); } }, 'Retirer') : null))))));
    }
    if (canAdd) {
      const f = h('form', { class: 'row', onsubmit: async e => {
        e.preventDefault();
        const r = await guard(() => api('POST', `/interventions/${iv.id}/parts`, { reference: f.reference.value.trim(), designation: f.designation.value.trim(), quantity: Number(f.quantity.value) }));
        if (r) viewDetail(iv.id);
      } }, field('Référence', h('input', { name: 'reference', required: true })), field('Désignation', h('input', { name: 'designation', required: true })),
        field('Quantité', h('input', { name: 'quantity', type: 'number', min: '0.01', step: '0.01', value: '1', required: true })),
        h('button', { type: 'submit', class: 'secondary' }, 'Ajouter la pièce'));
      out.append(f);
    }
  }
  return out;
}

function historyBlock(iv) {
  const d = iv.durations || {};
  const sd = iv.status_durations || {};
  return h('section', { class: 'card' },
    h('h2', {}, 'Historique et traçabilité'),
    h('div', { class: 'tiles' },
      [['Délai de prise en charge', d.response_s], ['Attente prise en charge', d.waiting_takeover_s], ['Diagnostic', d.diagnosis_s],
        ['Intervention active', d.active_maintenance_s], ['Attente pièces', d.waiting_parts_s], ['Attente Qualité', d.waiting_quality_s],
        ['Attente clôture', d.waiting_closure_s], ['Arrêt machine', d.downtime_s]]
        .filter(([, v]) => v !== undefined && v !== null).map(([l, v]) => h('div', { class: 'tile' }, h('div', { class: 'v' }, fmtDur(v)), h('div', { class: 'l' }, l)))),
    Object.keys(sd).length ? h('details', {}, h('summary', { style: 'cursor:pointer' }, 'Temps passé dans chaque statut'),
      h('table', {}, Object.entries(sd).map(([k, v]) => h('tr', {}, h('td', {}, label('statuses', k)), h('td', {}, fmtDur(v)))))) : null,
    h('h3', {}, 'Journal (non modifiable)'),
    h('ul', { class: 'timeline' }, iv.events.map(e => h('li', {},
      h('div', {}, h('b', {}, e.label), e.to_status ? [' → ', statusBadge(e.to_status)] : null),
      h('div', { class: 'small muted' }, `${fmtDate(e.at)} · ${e.user_name || 'système'}${e.matricule ? ' (' + e.matricule + ')' : ''}${e.user_role_label ? ' · ' + e.user_role_label : ''}`),
      e.comment ? h('div', { class: 'small' }, '« ' + e.comment + ' »') : null,
      e.data && e.data.changes ? h('div', { class: 'small muted' }, Object.entries(e.data.changes).map(([k, v]) => `${k} : ${JSON.stringify(v)}`).join(' · ')) : null))),
    h('h3', {}, 'Signatures'),
    iv.signatures.length ? h('ul', {}, iv.signatures.map(s => h('li', {}, `${s.role} – ${s.user_name} (${s.matricule || ''}) – ${fmtDate(s.signed_at)}${s.superseded ? ' (remplacée)' : ''}`))) : h('p', { class: 'muted' }, 'Aucune.'));
}

// ------------------------------------------------------------------ dialogue d'action
function openDialog(...content) {
  const d = $('#dialog'); d.replaceChildren(...content); $('#dialog-back').hidden = false;
  const first = d.querySelector('input,select,textarea'); if (first) first.focus();
}
function closeDialog() { $('#dialog-back').hidden = true; }
$('#dialog-back').addEventListener('click', e => { if (e.target.id === 'dialog-back') closeDialog(); });

function signaturePad() {
  const c = h('canvas', { class: 'sig', width: 600, height: 200 });
  const ctx = c.getContext('2d'); ctx.lineWidth = 3; ctx.lineCap = 'round'; ctx.strokeStyle = '#111';
  let down = false, used = false;
  const pos = e => { const r = c.getBoundingClientRect(); return [(e.clientX - r.left) * c.width / r.width, (e.clientY - r.top) * c.height / r.height]; };
  c.addEventListener('pointerdown', e => { down = true; used = true; c.setPointerCapture(e.pointerId); ctx.beginPath(); ctx.moveTo(...pos(e)); });
  c.addEventListener('pointermove', e => { if (down) { ctx.lineTo(...pos(e)); ctx.stroke(); } });
  c.addEventListener('pointerup', () => { down = false; });
  return { el: c, value: () => used ? c.toDataURL('image/png').split(',')[1] : null, clear: () => { ctx.clearRect(0, 0, c.width, c.height); used = false; } };
}

function actionDialog(iv, action, pending) {
  const R = S.refs;
  const body = { version: iv.version };
  const parts = [];
  const err = h('p', { class: 'err-text', hidden: true });
  const fields = {};
  const add = (name, lbl, el) => { fields[name] = el; parts.push(field(lbl, el, true)); };
  // Les valeurs saisies dans la page jaune mais pas encore enregistrées sont envoyées avec l'action.
  const val = k => pending[k] !== undefined ? pending[k] : iv[k];

  if (action === 'assign' || action === 'transfer') {
    const service = h('select', {}, options(labelPairs('services'), action === 'transfer' ? (iv.service === 'METHODE' ? 'MAINTENANCE' : 'METHODE') : iv.service || 'MAINTENANCE'));
    const tech = h('select', {});
    const refill = () => tech.replaceChildren(...options(R.technicians.filter(t => (service.value === 'METHODE') === (t.role === 'METHODE')), null, 'Tout le service', t => `${t.full_name} (${t.matricule || t.username})`));
    service.addEventListener('change', refill); refill();
    add('service', 'Service', service); add('technician_id', 'Personne', tech);
    if (action === 'assign' && role('CHEF_LIGNE', 'CHEF_EQUIPE', 'RESPONSABLE')) add('priority', 'Priorité', h('select', {}, options(labelPairs('priorities'), iv.priority)));
  }
  if (action === 'start') add('diagnosis', 'Diagnostic (obligatoire)', h('textarea', { required: true }, val('diagnosis') || ''));
  if (action === 'finish') {
    parts.push(h('p', { class: 'muted small' }, 'Fiche jaune obligatoire avant de terminer : diagnostic, cause, actions réalisées, résultat des tests et état final.'));
    add('diagnosis', 'Diagnostic', h('textarea', {}, val('diagnosis') || ''));
    add('cause_id', 'Cause (liste)', h('select', {}, options(R.causes.filter(c => c.category_id === iv.category_id), val('cause_id'), '—', c => c.name)));
    add('root_cause', 'Cause (détail)', h('input', { value: val('root_cause') || '' }));
    add('corrective_action', 'Actions réalisées', h('textarea', {}, val('corrective_action') || ''));
    add('test_result', 'Résultat des tests', h('input', { value: val('test_result') || '' }));
    add('machine_final_state', 'État final de la machine', h('select', {}, options(labelPairs('machine_states'), val('machine_final_state') || 'OPERATIONNELLE')));
  }
  if (action === 'wait_part') {
    add('reference', 'Référence de la pièce', h('input', {}));
    add('designation', 'Désignation', h('input', {}));
    add('quantity', 'Quantité', h('input', { type: 'number', min: '0.01', step: '0.01', value: '1' }));
  }
  if (action === 'quality_validate' || action === 'quality_reject') add('quality_observations', 'Observations Qualité', h('textarea', {}, val('quality_observations') || ''));
  const needs = NEEDS_REASON.includes(action);
  add('comment', action === 'close' ? 'Justification de la clôture (obligatoire)' : needs ? 'Motif (obligatoire, 5 caractères minimum)' : 'Commentaire', h('textarea', { required: needs }));
  let pad = null;
  if (action === 'finish' || action === 'close') {
    pad = signaturePad();
    parts.push(h('div', {}, h('label', {}, 'Signature (facultative)'), pad.el, h('button', { type: 'button', class: 'secondary', onclick: () => pad.clear() }, 'Effacer')));
  }

  openDialog(h('h2', {}, `${label('actions', action)} – ${iv.number}`), ...parts, err,
    h('div', { class: 'actions' },
      h('button', { class: ACTION_STYLE[action] || '', onclick: async ev => {
        for (const [k, el] of Object.entries(fields)) {
          const v = el.value.trim();
          if (!v) continue;
          body[k] = k.endsWith('_id') ? Number(v) : k === 'quantity' ? Number(v) : v;
        }
        // Les autres champs de la page jaune modifiés et non enregistrés partent avec l'action « finish ».
        if (action === 'finish') for (const [k, v] of Object.entries(pending)) if (!(k in body) && v !== '') body[k] = k.endsWith('_id') ? Number(v) : v;
        if (needs && (!body.comment || body.comment.length < 5)) { err.textContent = 'Motif obligatoire (5 caractères minimum).'; err.hidden = false; return; }
        const sig = pad && pad.value(); if (sig) body.signature_png_base64 = sig;
        ev.target.disabled = true;
        try {
          await api('POST', `/interventions/${iv.id}/actions/${action}`, body);
          closeDialog(); toast(label('actions', action) + ' : fait.');
          viewDetail(iv.id);
        } catch (ex) {
          ev.target.disabled = false; err.textContent = ex.message; err.hidden = false;
          if (ex.status === 409) err.textContent += ' (la fiche a été modifiée par quelqu\'un d\'autre : fermez et rouvrez-la)';
        }
      } }, 'Confirmer'),
      h('button', { class: 'secondary', onclick: closeDialog }, 'Annuler')));
}

// ------------------------------------------------------------------ historique machine
async function viewMachine(id) {
  const m = await api('GET', `/machines/${encodeURIComponent(id)}/history`);
  app().replaceChildren(
    h('div', { class: 'ivhead' }, h('a', { href: '#/pannes', class: 'btn secondary' }, '← Pannes'), h('h1', { style: 'margin:0' }, `Historique ${m.machine.code} – ${m.machine.name}`)),
    h('div', { class: 'tiles' },
      h('div', { class: 'tile' }, h('div', { class: 'v' }, m.count), h('div', { class: 'l' }, 'Pannes')),
      h('div', { class: 'tile red' }, h('div', { class: 'v' }, m.open), h('div', { class: 'l' }, 'En cours')),
      h('div', { class: 'tile' }, h('div', { class: 'v' }, m.recurring), h('div', { class: 'l' }, 'Récurrentes')),
      h('div', { class: 'tile' }, h('div', { class: 'v' }, fmtDur(m.downtime_s)), h('div', { class: 'l' }, 'Arrêt cumulé'))),
    h('div', { class: 'list' }, (m.items || []).map(itemCard)));
}

// ------------------------------------------------------------------ tableau de bord
function bars(list, valueKey, fmt) {
  const max = Math.max(1, ...list.map(x => Number(x[valueKey]) || 0));
  return h('div', { class: 'bars' }, list.slice(0, 12).map(x => h('div', { class: 'bar' },
    h('span', { title: x.name }, x.code || x.name || '—'),
    h('div', { class: 'track' }, h('div', { class: 'fill', style: `width:${(Number(x[valueKey]) || 0) * 100 / max}%` })),
    h('span', {}, fmt ? fmt(x[valueKey]) : x[valueKey]))));
}

function timelineChart(tl) {
  const NS = 'http://www.w3.org/2000/svg';
  const svg = document.createElementNS(NS, 'svg');
  const W = 600, H = 200, pad = 24;
  svg.setAttribute('viewBox', `0 0 ${W} ${H}`); svg.setAttribute('role', 'img'); svg.setAttribute('aria-label', 'Pannes déclarées et clôturées par période');
  const max = Math.max(1, ...tl.map(t => Math.max(t.declared, t.closed)));
  const bw = (W - pad * 2) / Math.max(1, tl.length);
  tl.forEach((t, i) => {
    [['declared', 'var(--pink)', 0], ['closed', 'var(--green)', 1]].forEach(([k, color, j]) => {
      const r = document.createElementNS(NS, 'rect');
      const hgt = (H - pad * 2) * t[k] / max;
      r.setAttribute('x', pad + i * bw + j * bw / 2 + 1); r.setAttribute('width', Math.max(1, bw / 2 - 2));
      r.setAttribute('y', H - pad - hgt); r.setAttribute('height', hgt); r.setAttribute('fill', color);
      const tt = document.createElementNS(NS, 'title'); tt.textContent = `${t.period} : ${t[k]} ${k === 'declared' ? 'déclarée(s)' : 'clôturée(s)'}`; r.append(tt);
      svg.append(r);
    });
  });
  const ax = document.createElementNS(NS, 'text'); ax.setAttribute('x', pad); ax.setAttribute('y', H - 6); ax.setAttribute('font-size', '12'); ax.setAttribute('fill', 'currentColor');
  ax.textContent = tl.length ? `${tl[0].period} → ${tl[tl.length - 1].period}   (rose : déclarées, vert : clôturées, max ${max})` : 'Aucune donnée';
  svg.append(ax);
  return h('div', { class: 'chart' }, svg);
}

async function viewDashboard(params) {
  const R = S.refs;
  const f = Object.fromEntries(params.entries());
  const qs = new URLSearchParams(f).toString();
  const d = await api('GET', '/dashboard' + (qs ? '?' + qs : ''));
  const form = h('form', { class: 'card no-print', onsubmit: e => {
    e.preventDefault();
    const q = new URLSearchParams();
    for (const el of form.elements) if (el.name && el.value) q.set(el.name, el.value);
    go('#/tableau-de-bord?' + q);
  } }, h('div', { class: 'row' },
    field('Du', h('input', { type: 'date', name: 'date_from', value: f.date_from || (d.period.from || '').slice(0, 10) })),
    field('Au', h('input', { type: 'date', name: 'date_to', value: f.date_to || '' })),
    field('Ligne', h('select', { name: 'line_id' }, options(R.lines, f.line_id, 'Toutes'))),
    field('Machine', h('select', { name: 'machine_id' }, options(R.machines, f.machine_id, 'Toutes'))),
    field('Poste', h('select', { name: 'poste_id' }, options(R.postes, f.poste_id, 'Tous'))),
    field('Catégorie', h('select', { name: 'category_id' }, options(R.categories, f.category_id, 'Toutes'))),
    h('button', { type: 'submit' }, 'Actualiser'),
    h('button', { type: 'button', class: 'secondary', onclick: () => download('/dashboard/export.xlsx' + (qs ? '?' + qs : '')) }, 'Excel'),
    h('button', { type: 'button', class: 'secondary', onclick: () => download('/dashboard/export.pdf' + (qs ? '?' + qs : ''), true) }, 'PDF')));
  const L = d.live, T = d.totals;
  const tile = (v, l, cls, href) => h(href ? 'a' : 'div', { class: 'tile ' + (cls || ''), href, style: href ? 'text-decoration:none;color:inherit' : null }, h('div', { class: 'v' }, v), h('div', { class: 'l' }, l));
  app().replaceChildren(
    h('h1', {}, 'Tableau de bord'), form,
    h('h2', {}, 'En direct'),
    h('div', { class: 'tiles' },
      tile(L.open, 'Pannes en cours', '', '#/pannes?open_only=true'),
      tile(L.critical_open, 'Critiques en cours', 'red', '#/pannes?open_only=true&priority=CRITIQUE'),
      tile(L.machines_stopped, 'Machines arrêtées', 'red'),
      tile(L.waiting_takeover, 'En attente de prise en charge', '', '#/pannes?status=NOUVEAU,EN_ATTENTE,REOUVERTE'),
      tile(L.late, 'En retard', 'red'),
      tile(L.waiting_parts, 'En attente de pièces', '', '#/pannes?status=EN_ATTENTE_PIECE'),
      tile(L.waiting_quality, 'En attente Qualité', '', '#/pannes?status=EN_ATTENTE_QUALITE'),
      tile(L.to_close, 'Résolues à clôturer', 'green', '#/pannes?status=RESOLUE')),
    h('h2', {}, `Période du ${fmtDate(d.period.from)} au ${fmtDate(d.period.to)}`),
    h('div', { class: 'tiles' },
      tile(T.declared, 'Déclarées'), tile(T.closed, 'Clôturées', 'green'), tile(T.critical, 'Critiques', 'red'),
      tile(fmtDur(d.mttr_s), 'MTTR (temps moyen de réparation)'), tile(fmtDur(d.mtbf_s), 'MTBF (temps moyen entre pannes)'),
      tile(fmtDur(d.response_avg_s), 'Délai moyen de prise en charge'),
      tile(d.recurrence_rate === null ? '—' : d.recurrence_rate + ' %', 'Taux de récurrence'), tile(fmtDur(d.downtime_s), 'Arrêt machine cumulé')),
    h('div', { class: 'card' }, h('h2', {}, 'Chronologie'), timelineChart(d.timeline)),
    h('div', { class: 'grid' },
      h('div', { class: 'card' }, h('h2', {}, 'Par machine'), bars(d.by_machine, 'count')),
      h('div', { class: 'card' }, h('h2', {}, 'Par ligne'), bars(d.by_line, 'count')),
      h('div', { class: 'card' }, h('h2', {}, 'Par catégorie'), bars(d.by_category, 'count')),
      h('div', { class: 'card' }, h('h2', {}, 'Par poste'), bars(d.by_poste, 'count')),
      h('div', { class: 'card' }, h('h2', {}, 'Arrêt par machine'), bars([...d.by_machine].sort((a, b) => b.downtime_s - a.downtime_s), 'downtime_s', fmtDur)),
      h('div', { class: 'card' }, h('h2', {}, 'Par priorité'), bars(Object.entries(d.by_priority).map(([k, v]) => ({ code: label('priorities', k), count: v })), 'count'))));
}

// ------------------------------------------------------------------ administration
const ADMIN_TABS = [
  ['users', 'Utilisateurs'], ['lines', 'Lignes'], ['postes', 'Postes'], ['machines', 'Machines'], ['cable-refs', 'Réf. câbles'],
  ['tools', 'Outils / posages'], ['categories', 'Catégories'], ['causes', 'Causes'], ['intervention-types', 'Types d\'intervention'],
  ['site', 'Société / site'], ['settings', 'Paramètres'], ['audit', 'Journal d\'audit'], ['backup', 'Sauvegarde'],
];

/** Colonnes des listes de configuration : [champ, libellé, type, liste liée]. */
function refColumns(tab, lists) {
  const prios = labelPairs('priorities');
  return {
    lines: [['code', 'Code'], ['name', 'Nom']],
    postes: [['line_id', 'Ligne', 'select', lists.lines], ['code', 'Code (A, B, C…)'], ['name', 'Nom']],
    machines: [['line_id', 'Ligne', 'select', lists.lines], ['poste_id', 'Poste', 'select', lists.postes, true], ['code', 'Code'], ['name', 'Nom']],
    'cable-refs': [['code', 'Référence'], ['name', 'Désignation']],
    tools: [['code', 'Code'], ['name', 'Nom'], ['machine_id', 'Machine', 'select', lists.machines, true]],
    categories: [['code', 'Code'], ['name', 'Nom'], ['default_priority', 'Priorité par défaut', 'select', prios.map(([id, name]) => ({ id, name }))], ['requires_quality', 'Validation Qualité', 'check']],
    causes: [['category_id', 'Catégorie', 'select', lists.categories], ['code', 'Code (facultatif)', 'text', null, true], ['name', 'Cause']],
    'intervention-types': [['code', 'Code'], ['name', 'Nom']],
  }[tab];
}

async function viewAdmin(tab) {
  if (!role('ADMIN')) { app().replaceChildren(h('div', { class: 'card' }, 'Réservé à l\'administrateur.')); return; }
  const tabs = h('div', { class: 'tabs' }, ADMIN_TABS.map(([k, t]) => h('a', { href: '#/admin/' + k, class: k === tab ? 'on' : null }, t)));
  const body = h('div', {});
  app().replaceChildren(h('h1', {}, 'Administration'), tabs, body);
  if (tab === 'users') return adminUsers(body);
  if (tab === 'site') return adminSite(body);
  if (tab === 'settings') return adminSettings(body);
  if (tab === 'audit') return adminAudit(body);
  if (tab === 'backup') return adminBackup(body);
  return adminRefs(body, tab);
}

async function adminLists() {
  const [lines, postes, machines, categories] = await Promise.all(['lines', 'postes', 'machines', 'categories'].map(p => api('GET', '/admin/' + p)));
  return { lines, postes, machines, categories };
}

async function adminRefs(body, tab) {
  const lists = await adminLists();
  const rows = await api('GET', '/admin/' + tab);
  const cols = refColumns(tab, lists);
  const nameOf = (list, id) => { const x = (list || []).find(o => String(o.id) === String(id)); return x ? (x.code ? `${x.code} – ${x.name}` : x.name) : '—'; };
  function editor(row) {
    const els = {};
    const content = cols.map(([k, l, type, list, optional]) => {
      let el;
      if (type === 'select') el = h('select', {}, options(list, row?.[k], optional ? '—' : undefined));
      else if (type === 'check') el = h('input', { type: 'checkbox', checked: !!row?.[k] });
      else el = h('input', { value: row?.[k] ?? '' });
      els[k] = el;
      return type === 'check' ? h('label', { class: 'check' }, el, l) : field(l, el, true);
    });
    const active = h('input', { type: 'checkbox', checked: row ? row.active : true });
    const err = h('p', { class: 'err-text', hidden: true });
    openDialog(h('h2', {}, row ? 'Modifier' : 'Ajouter'), ...content, h('label', { class: 'check' }, active, 'Actif'), err,
      h('div', { class: 'actions' }, h('button', { onclick: async () => {
        const b = { active: active.checked };
        cols.forEach(([k, , type]) => { const el = els[k]; b[k] = type === 'check' ? el.checked : (k.endsWith('_id') ? (el.value ? Number(el.value) : null) : el.value.trim()); });
        try { await api(row ? 'PUT' : 'POST', `/admin/${tab}` + (row ? '/' + row.id : ''), b); closeDialog(); toast('Enregistré.'); await refreshRefs(); adminRefs(body, tab); }
        catch (ex) { err.textContent = ex.message; err.hidden = false; }
      } }, 'Enregistrer'), h('button', { class: 'secondary', onclick: closeDialog }, 'Annuler')));
  }
  body.replaceChildren(h('div', { class: 'card' },
    h('div', { class: 'row', style: 'align-items:center' }, h('h2', { style: 'margin:0' }, ADMIN_TABS.find(t => t[0] === tab)[1]), h('button', { style: 'flex:0 0 auto', onclick: () => editor(null) }, '+ Ajouter')),
    h('p', { class: 'muted small' }, 'Une valeur déjà utilisée dans une panne ne peut pas être supprimée : désactivez-la, l\'historique reste intact.'),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('tr', {}, cols.map(c => h('th', {}, c[1])), h('th', {}, 'Actif'), h('th', {}, 'Utilisé'), h('th', {})),
      rows.map(r => h('tr', {},
        cols.map(([k, , type, list]) => h('td', {}, type === 'select' && k.endsWith('_id') ? nameOf(list, r[k]) : type === 'check' ? (r[k] ? 'Oui' : 'Non') : type === 'select' ? label('priorities', r[k]) : r[k] ?? '')),
        h('td', {}, r.active ? 'Oui' : 'Non'), h('td', {}, r.used),
        h('td', {}, h('button', { class: 'secondary', onclick: () => editor(r) }, 'Modifier'), ' ',
          !r.used ? h('button', { class: 'danger', onclick: async () => { if (await guard(() => api('DELETE', `/admin/${tab}/${r.id}`)) !== undefined) { await refreshRefs(); adminRefs(body, tab); } } }, 'Supprimer') : null)))))));
}

async function refreshRefs() { S.refs = await api('GET', '/referentials'); }

async function adminUsers(body) {
  const [users, lists] = await Promise.all([api('GET', '/admin/users'), adminLists()]);
  const roles = labelPairs('roles').filter(([k]) => k !== 'TECHNICIEN');
  function editor(u) {
    const f = {
      matricule: h('input', { value: u?.matricule || '', disabled: !!u, autocomplete: 'off' }),
      first_name: h('input', { value: u?.first_name || '' }), last_name: h('input', { value: u?.last_name || '' }),
      role: h('select', {}, options(roles, u?.role || 'OPERATEUR')),
      password: h('input', { type: 'password', autocomplete: 'new-password', placeholder: u ? 'Laisser vide pour ne pas changer' : '8 caractères, lettres et chiffres' }),
      lines: h('select', { multiple: true, size: Math.min(6, lists.lines.length) }, lists.lines.map(l => h('option', { value: l.id, selected: (u?.line_ids || []).includes(l.id) }, `${l.code} – ${l.name}`))),
      active: h('input', { type: 'checkbox', checked: u ? u.active : true }),
    };
    const err = h('p', { class: 'err-text', hidden: true });
    openDialog(h('h2', {}, u ? `Modifier ${u.matricule}` : 'Nouvel utilisateur'),
      field('Matricule (identifiant de connexion)', f.matricule, true), field('Prénom', f.first_name, true), field('Nom', f.last_name, true),
      field('Rôle', f.role, true), field(u ? 'Nouveau mot de passe provisoire' : 'Mot de passe provisoire', f.password, true),
      field('Lignes (opérateur et chef de ligne ; aucune = toutes)', f.lines, true),
      h('label', { class: 'check' }, f.active, 'Compte actif'),
      h('p', { class: 'muted small' }, 'L\'utilisateur devra changer le mot de passe provisoire à sa première connexion.'), err,
      h('div', { class: 'actions' }, h('button', { onclick: async () => {
        const b = { first_name: f.first_name.value.trim(), last_name: f.last_name.value.trim(), role: f.role.value, active: f.active.checked,
          line_ids: [...f.lines.selectedOptions].map(o => Number(o.value)) };
        try {
          if (u) { if (f.password.value) b.reset_password = f.password.value; await api('PATCH', '/admin/users/' + u.id, b); }
          else { b.matricule = f.matricule.value.trim(); b.password = f.password.value; await api('POST', '/admin/users', b); }
          closeDialog(); toast('Enregistré.'); adminUsers(body);
        } catch (ex) { err.textContent = ex.message; err.hidden = false; }
      } }, 'Enregistrer'), h('button', { class: 'secondary', onclick: closeDialog }, 'Annuler')));
  }
  const lineCodes = ids => ids && ids.length ? ids.map(id => lists.lines.find(l => l.id === id)?.code || id).join(', ') : 'Toutes';
  body.replaceChildren(h('div', { class: 'card' },
    h('div', { class: 'row', style: 'align-items:center' }, h('h2', { style: 'margin:0' }, `Utilisateurs (${users.length})`), h('button', { style: 'flex:0 0 auto', onclick: () => editor(null) }, '+ Nouvel utilisateur')),
    h('p', { class: 'muted small' }, 'Un compte présent dans l\'historique ne peut pas être supprimé : désactivez-le.'),
    h('div', { class: 'table-wrap' }, h('table', {},
      h('tr', {}, ['Matricule', 'Nom', 'Prénom', 'Rôle', 'Lignes', 'Statut', ''].map(t => h('th', {}, t))),
      users.map(u => h('tr', {}, h('td', {}, u.matricule), h('td', {}, u.last_name), h('td', {}, u.first_name), h('td', {}, u.role_label),
        h('td', {}, lineCodes(u.line_ids)), h('td', {}, u.active ? 'Actif' : h('span', { class: 'badge st-ANNULE' }, 'Désactivé')),
        h('td', {}, h('button', { class: 'secondary', onclick: () => editor(u) }, 'Modifier'), ' ',
          h('button', { class: 'danger', onclick: async () => { if (await guard(() => api('DELETE', '/admin/users/' + u.id)) !== undefined) { toast('Supprimé.'); adminUsers(body); } } }, 'Supprimer'))))))));
}

async function adminSite(body) {
  const s = await api('GET', '/admin/site');
  const f = { code: h('input', { value: s.code }), name: h('input', { value: s.name }), timezone: h('input', { value: s.timezone }) };
  body.replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Société / site'),
    h('p', { class: 'muted small' }, 'Utilisez un nom fictif tant que l\'entreprise n\'a pas autorisé l\'usage de son nom et de son logo.'),
    h('div', { class: 'grid' }, field('Code', f.code), field('Nom', f.name), field('Fuseau horaire', f.timezone)),
    h('div', { class: 'actions' }, h('button', { onclick: async () => {
      if (await guard(() => api('PUT', '/admin/site', { code: f.code.value.trim(), name: f.name.value.trim(), timezone: f.timezone.value.trim() }))) { toast('Enregistré.'); refreshRefs(); }
    } }, 'Enregistrer'))));
}

async function adminSettings(body) {
  const { values: v } = await api('GET', '/admin/settings');
  const late = v.late_acceptance_minutes || {};
  const f = {
    company_display_name: h('input', { value: v.company_display_name }),
    session_idle_minutes: h('input', { type: 'number', min: 0, max: 720, value: v.session_idle_minutes }),
    require_manager_validation: h('input', { type: 'checkbox', checked: v.require_manager_validation }),
    recurrence_window_days: h('input', { type: 'number', min: 1, value: v.recurrence_window_days }),
    recurrence_threshold: h('input', { type: 'number', min: 2, value: v.recurrence_threshold }),
  };
  const lateEls = Object.fromEntries(['CRITIQUE', 'HAUTE', 'NORMALE', 'BASSE'].map(p => [p, h('input', { type: 'number', min: 1, value: late[p] ?? '' })]));
  body.replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Paramètres'),
    h('div', { class: 'grid' },
      field('Nom affiché sur les fiches et PDF', f.company_display_name),
      field('Déconnexion automatique après (minutes, 0 = jamais)', f.session_idle_minutes),
      field('Fenêtre de récidive (jours)', f.recurrence_window_days),
      field('Seuil de récidive (pannes)', f.recurrence_threshold),
      h('label', { class: 'check' }, f.require_manager_validation, 'Validation du Responsable maintenance avant clôture')),
    h('h3', {}, 'Délai de prise en charge avant alerte (minutes)'),
    h('div', { class: 'grid' }, Object.entries(lateEls).map(([p, el]) => field(label('priorities', p), el))),
    h('div', { class: 'actions' }, h('button', { onclick: async () => {
      const b = {
        company_display_name: f.company_display_name.value.trim(), session_idle_minutes: Number(f.session_idle_minutes.value),
        require_manager_validation: f.require_manager_validation.checked, recurrence_window_days: Number(f.recurrence_window_days.value),
        recurrence_threshold: Number(f.recurrence_threshold.value),
        late_acceptance_minutes: Object.fromEntries(Object.entries(lateEls).filter(([, el]) => el.value).map(([p, el]) => [p, Number(el.value)])),
      };
      if (await guard(() => api('PUT', '/admin/settings', b))) { toast('Paramètres enregistrés.'); S.idle = b.session_idle_minutes; }
    } }, 'Enregistrer'))));
}

async function adminAudit(body, q) {
  q = q || {};
  const qs = new URLSearchParams(Object.entries(q).filter(([, v]) => v)); qs.set('limit', '200');
  const rows = await api('GET', '/admin/audit?' + qs);
  const f = { action: h('input', { value: q.action || '', placeholder: 'ex. LOGIN_FAILED' }), username: h('input', { value: q.username || '' }), entity_id: h('input', { value: q.entity_id || '', placeholder: 'N° de panne' }) };
  body.replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Journal d\'audit (non modifiable)'),
    h('div', { class: 'row' }, field('Action', f.action), field('Matricule', f.username), field('Élément', f.entity_id),
      h('button', { onclick: () => adminAudit(body, { action: f.action.value.trim(), username: f.username.value.trim(), entity_id: f.entity_id.value.trim() }) }, 'Filtrer')),
    h('div', { class: 'table-wrap' }, h('table', {}, h('tr', {}, ['Date', 'Matricule', 'Action', 'Élément', 'Détail', 'IP'].map(t => h('th', {}, t))),
      rows.map(a => h('tr', {}, h('td', {}, fmtDate(a.at)), h('td', {}, a.username || ''), h('td', {}, a.action), h('td', {}, [a.entity, a.entity_id].filter(Boolean).join(' ')),
        h('td', { class: 'small' }, a.detail ? JSON.stringify(a.detail) : ''), h('td', { class: 'small' }, a.ip || '')))))));
}

function adminBackup(body) {
  body.replaceChildren(h('div', { class: 'card' }, h('h2', {}, 'Sauvegarde'),
    h('p', {}, 'Télécharge une copie complète (base de données, photos, PDF archivés) au format ZIP. Conservez-la hors du téléphone maître (PC, clé USB).'),
    h('p', { class: 'muted small' }, 'Restauration, serveur arrêté : sur le téléphone maître, écran « Téléphone maître » → « Restaurer une sauvegarde » ; sur PC, commande « maintiflow-server restore FICHIER.zip ». Une sauvegarde de l\'un se restaure sur l\'autre.'),
    h('div', { class: 'actions' }, h('button', { onclick: () => download('/admin/backup') }, 'Télécharger la sauvegarde'))));
}

// ------------------------------------------------------------------ démarrage
(async function boot() {
  const t = store('mf_token');
  if (!t) { route(); return; }
  S.token = t; S.idle = Number(store('mf_idle') || 30);
  try { S.user = await api('GET', '/auth/me'); await afterAuth(); } catch (_) { if (S.token) logout(); }
})();
