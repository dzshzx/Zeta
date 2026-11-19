package com.dzshzx.zeta.phone.browser

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 注入页面的脚本。每段都是 `(function (A) { … })(参数)`，参数是 Kotlin 这边编码好的 JSON，不拼字符串；
 * 返回 `JSON.stringify(结果)`，出错时结果里带 `error`。扫描都有条数和时间上限，大页面也不会卡住界面线程太久。
 *
 * 元素编号放在页面自己的 window 上（WeakMap，不改 DOM）：同一个文档里同一个元素编号不变，
 * 换了文档从 Kotlin 给的起点接着编，旧页面的编号不会对上新页面的元素。
 */
internal object BrowserScripts {
    /** 页面概况：可交互元素（按离视口远近，最多 250 个）和视口里的文字。 */
    fun snapshot(start: Int, textMax: Int): String = run(SNAPSHOT) {
        put("start", start)
        put("textMax", textMax)
    }

    /** 全文（正文区，没有就是整页）。 */
    fun read(): String = run(READ) {}

    /** 点按编号为 [id] 的元素，或视口里比例位置 ([fx], [fy]) 上的元素。 */
    fun click(id: Int?, fx: Double?, fy: Double?): String = run(CLICK) {
        if (id != null) put("id", id)
        if (fx != null && fy != null) {
            put("fx", fx)
            put("fy", fy)
        }
    }

    /** 在元素 [id]（没给就是当前焦点）里输入 [text]，替换原有内容；下拉框按选项文字选。[submit]：再按回车提交。 */
    fun type(id: Int?, text: String, submit: Boolean): String = run(TYPE) {
        if (id != null) put("id", id)
        put("text", text)
        put("submit", submit)
    }

    /** 向上、向下滚大半屏；给了 [id] 就把那个元素滚到视口中间。 */
    fun scroll(id: Int?, down: Boolean): String = run(SCROLL) {
        if (id != null) put("id", id)
        put("down", down)
    }

    /** 页面上有没有这段文字。 */
    fun contains(text: String): String = run(CONTAINS) { put("text", text) }

    /** 一个便宜的页面指纹：加载状态、文字量、元素数。连续两次一样就当页面停下来了。 */
    fun signature(): String = run(SIGNATURE) {}

    private fun run(body: String, args: JsonObjectBuilder.() -> Unit): String {
        val json: JsonObject = buildJsonObject(args)
        return "(function (A) {\n$PRELUDE\ntry {\n$body\n} catch (e) { return JSON.stringify({ error: String((e && e.message) || e) }); }\n})($json);"
    }

    private const val PRELUDE = """
var SEL = 'a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=checkbox],[role=radio],[role=switch],[role=tab],[role=menuitem],[role=menuitemcheckbox],[role=menuitemradio],[role=option],[role=combobox],[role=textbox],[role=searchbox],[role=slider],[contenteditable=""],[contenteditable=true],[onclick],[tabindex]:not([tabindex="-1"]),label';
var OUTER = 'a[href],button,[role=button],[role=link]';
function clean(v, n) {
  var s = String(v == null ? '' : v).replace(/\s+/g, ' ').trim();
  return s.length > n ? s.slice(0, n) + '…' : s;
}
function vis(el) {
  try {
    if (el.checkVisibility) return el.checkVisibility({ opacityProperty: true, visibilityProperty: true, checkOpacity: true, checkVisibilityCSS: true });
  } catch (e) {}
  var s = getComputedStyle(el);
  return s.display !== 'none' && s.visibility !== 'hidden' && s.opacity !== '0';
}
function state() {
  var Z = window.__zetaBrowser;
  if (!Z || Z.doc !== document) Z = window.__zetaBrowser = { doc: document, next: 1, els: new Map(), ids: new WeakMap() };
  return Z;
}
function idOf(el) {
  var Z = state(), id = Z.ids.get(el);
  if (!id) { id = Z.next++; Z.ids.set(el, id); Z.els.set(id, new WeakRef(el)); }
  return id;
}
function find(id) {
  var Z = state(), ref = Z.els.get(id), el = ref && ref.deref();
  return el && el.isConnected ? el : null;
}
function textOf(el) { return el.innerText || el.textContent || ''; }
function nameOf(el) {
  var t = el.getAttribute('aria-label'), tag = el.tagName;
  if (!t) {
    var ids = el.getAttribute('aria-labelledby');
    if (ids) t = ids.split(/\s+/).map(function (i) { var e = document.getElementById(i); return e ? textOf(e) : ''; }).join(' ');
  }
  if (!t && el.labels && el.labels.length) t = textOf(el.labels[0]);
  if (!t && tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT') t = textOf(el);
  if (!t && tag === 'INPUT' && /^(submit|button|reset)$/i.test(el.type)) t = el.value;
  if (!t) t = el.getAttribute('title') || el.getAttribute('alt') || '';
  if (!t && el.querySelector) { var img = el.querySelector('img[alt]'); if (img) t = img.getAttribute('alt'); }
  return clean(t, 100);
}
function hiddenControl(label) {
  var c = label.control;
  if (!c || c.type === 'hidden') return null;
  var r = c.getBoundingClientRect();
  return (!vis(c) || r.width < 4 || r.height < 4) ? c : null;
}
function info(el, r) {
  var tag = el.tagName.toLowerCase(), type = (el.getAttribute('type') || '').toLowerCase();
  var o = { id: idOf(el), tag: tag, name: nameOf(el), top: Math.round(r.top), bottom: Math.round(r.bottom) };
  if (tag === 'label') {
    // 藏起来的复选框、单选项由它的 label 代替出现在列表里，点 label 就是点它
    var c = hiddenControl(el);
    if (c) {
      o.tag = c.tagName.toLowerCase();
      if (c.type) o.type = String(c.type).toLowerCase();
      if (c.type === 'checkbox' || c.type === 'radio') o.checked = !!c.checked;
      if (c.disabled) o.disabled = true;
    }
    return o;
  }
  var role = el.getAttribute('role');
  if (role) o.role = role;
  if (type) o.type = type;
  if (tag === 'a' && el.href) o.href = clean(el.href, 200);
  if (tag === 'input' || tag === 'textarea') {
    if (el.placeholder) o.placeholder = clean(el.placeholder, 80);
    if (type === 'checkbox' || type === 'radio') o.checked = !!el.checked;
    else if (type === 'password') o.value = el.value ? '••••' : '';
    else if (type !== 'submit' && type !== 'button' && type !== 'reset') o.value = clean(el.value, 80);
  }
  if (tag === 'select') {
    var opt = el.options[el.selectedIndex];
    o.value = opt ? clean(opt.text, 60) : '';
    o.options = el.options.length;
  }
  var aria = el.getAttribute('aria-checked');
  if (aria === 'true' || aria === 'false') o.checked = aria === 'true';
  var exp = el.getAttribute('aria-expanded');
  if (exp === 'true' || exp === 'false') o.expanded = exp === 'true';
  if (el.disabled || el.getAttribute('aria-disabled') === 'true') o.disabled = true;
  if (el.isContentEditable && tag !== 'input' && tag !== 'textarea') o.editable = true;
  return o;
}
function describe(el) {
  var r = el.getBoundingClientRect();
  return info(el, r);
}
"""

    private const val SNAPSHOT = """
var Z = state();
if (Z.next < A.start) Z.next = A.start;
var vh = window.innerHeight, vw = window.innerWidth;
var deadline = Date.now() + 400;
var all = document.querySelectorAll(SEL), list = [];
for (var i = 0; i < all.length && i < 3000; i++) {
  if ((i & 31) === 0 && Date.now() > deadline) break;
  var el = all[i], tag = el.tagName;
  if (tag === 'INPUT' && el.type === 'hidden') continue;
  if (tag === 'LABEL' && !hiddenControl(el)) continue;
  if (tag !== 'INPUT' && tag !== 'TEXTAREA' && tag !== 'SELECT' && el.parentElement && el.parentElement.closest(OUTER)) continue;
  var r = el.getBoundingClientRect();
  if (r.width < 1 || r.height < 1) continue;
  if (!vis(el)) continue;
  list.push({ el: el, r: r, d: r.bottom < 0 ? -r.bottom : (r.top > vh ? r.top - vh : 0) });
}
list.sort(function (a, b) { return a.d - b.d; });
var elements = list.slice(0, 250).map(function (x) { return info(x.el, x.r); });

var parts = [], size = 0, cut = false, last = null;
var root = document.body || document.documentElement;
var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
var range = document.createRange(), seen = new Map(), displays = new Map();
var textDeadline = Date.now() + 350, node;
function blockOf(el) {
  var e = el;
  while (e && e !== root) {
    var d = displays.get(e);
    if (d === undefined) { d = getComputedStyle(e).display; displays.set(e, d); }
    if (d && d.indexOf('inline') !== 0 && d !== 'contents') return e;
    e = e.parentElement;
  }
  return root;
}
while ((node = walker.nextNode())) {
  if (Date.now() > textDeadline) { cut = true; break; }
  var v = node.nodeValue;
  if (!v || !/\S/.test(v)) continue;
  var p = node.parentElement;
  if (!p) continue;
  var pt = p.tagName;
  if (pt === 'SCRIPT' || pt === 'STYLE' || pt === 'NOSCRIPT' || pt === 'TEMPLATE') continue;
  range.selectNodeContents(node);
  var b = range.getBoundingClientRect();
  if (b.bottom <= 0 || b.top >= vh || b.right <= 0 || b.left >= vw || (b.width === 0 && b.height === 0)) continue;
  var ok = seen.get(p);
  if (ok === undefined) { ok = vis(p); seen.set(p, ok); }
  if (!ok) continue;
  var block = blockOf(p), t = v.replace(/\s+/g, ' ').trim();
  parts.push(block === last ? ' ' : '\n');
  parts.push(t);
  last = block;
  size += t.length + 1;
  if (size > A.textMax) { cut = true; break; }
}
var de = document.documentElement;
return JSON.stringify({
  url: location.href, title: document.title,
  scrollY: Math.round(window.scrollY), vw: vw, vh: vh,
  height: Math.round(Math.max(de ? de.scrollHeight : 0, document.body ? document.body.scrollHeight : 0)),
  elements: elements, total: list.length, text: parts.join(''), textCut: cut, next: Z.next
});
"""

    private const val READ = """
var body = document.body || document.documentElement;
var total = (body.innerText || '').length, root = body, best = 0;
['article', 'main', '[role=main]'].forEach(function (sel) {
  var found = document.querySelectorAll(sel);
  for (var i = 0; i < found.length && i < 20; i++) {
    var n = (found[i].innerText || '').length;
    if (n > best) { best = n; root = found[i]; }
  }
});
if (best < total * 0.4) root = body;
return JSON.stringify({ url: location.href, title: document.title, text: (root.innerText || '').slice(0, 300000), part: root !== body });
"""

    private const val CLICK = """
var el, x, y;
if (A.id != null) {
  el = find(A.id);
  if (!el) return JSON.stringify({ error: 'gone' });
  el.scrollIntoView({ block: 'center', inline: 'center', behavior: 'instant' });
  var r = el.getBoundingClientRect();
  x = r.left + r.width / 2; y = r.top + r.height / 2;
} else {
  var vv = window.visualViewport;
  x = (vv ? vv.offsetLeft : 0) + A.fx * (vv ? vv.width : window.innerWidth);
  y = (vv ? vv.offsetTop : 0) + A.fy * (vv ? vv.height : window.innerHeight);
  el = document.elementFromPoint(x, y);
  if (!el) return JSON.stringify({ error: 'nothing' });
  var target = el.closest(SEL);
  if (target) el = target;
}
var hit = document.elementFromPoint(x, y);
var covered = !!(hit && hit !== el && !el.contains(hit) && !hit.contains(el));
var o = { bubbles: true, cancelable: true, composed: true, view: window, clientX: x, clientY: y, button: 0, buttons: 1, pointerId: 1, pointerType: 'touch', isPrimary: true };
el.dispatchEvent(new PointerEvent('pointerdown', o));
el.dispatchEvent(new MouseEvent('mousedown', o));
if (el.focus) el.focus({ preventScroll: true });
o.buttons = 0;
el.dispatchEvent(new PointerEvent('pointerup', o));
el.dispatchEvent(new MouseEvent('mouseup', o));
el.click();
return JSON.stringify({ ok: true, what: describe(el), covered: covered });
"""

    private const val TYPE = """
var el = A.id != null ? find(A.id) : document.activeElement;
if (!el || el === document.body || el === document.documentElement) return JSON.stringify({ error: A.id != null ? 'gone' : 'nofocus' });
var tag = el.tagName;
if (tag === 'SELECT') {
  var want = String(A.text).trim().toLowerCase(), pick = -1, i;
  for (i = 0; i < el.options.length; i++) {
    var op = el.options[i];
    if (op.text.trim().toLowerCase() === want || String(op.value).toLowerCase() === want) { pick = i; break; }
  }
  if (pick < 0) for (i = 0; i < el.options.length; i++) {
    if (el.options[i].text.toLowerCase().indexOf(want) >= 0) { pick = i; break; }
  }
  if (pick < 0) {
    var names = [];
    for (i = 0; i < el.options.length && i < 40; i++) names.push(clean(el.options[i].text, 40));
    return JSON.stringify({ error: 'option', options: names, what: describe(el) });
  }
  el.selectedIndex = pick;
  el.dispatchEvent(new Event('input', { bubbles: true }));
  el.dispatchEvent(new Event('change', { bubbles: true }));
  return JSON.stringify({ ok: true, what: describe(el), selected: clean(el.options[pick].text, 60) });
}
var editable = el.isContentEditable || tag === 'TEXTAREA' ||
  (tag === 'INPUT' && !/^(checkbox|radio|submit|button|reset|file|image|hidden|range|color)$/i.test(el.type || ''));
if (!editable) return JSON.stringify({ error: 'noteditable', what: describe(el) });
if (el.disabled || el.readOnly) return JSON.stringify({ error: 'readonly', what: describe(el) });
el.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
el.focus();
var done = false;
try {
  if (el.isContentEditable && tag !== 'INPUT' && tag !== 'TEXTAREA') {
    var sel = window.getSelection(), rg = document.createRange();
    rg.selectNodeContents(el); sel.removeAllRanges(); sel.addRange(rg);
  } else {
    el.select();
  }
  done = A.text === '' ? document.execCommand('delete', false) : document.execCommand('insertText', false, A.text);
} catch (e) { done = false; }
var now = (el.isContentEditable && tag !== 'INPUT' && tag !== 'TEXTAREA') ? textOf(el) : el.value;
if (!done || now !== A.text) {
  if (el.isContentEditable && tag !== 'INPUT' && tag !== 'TEXTAREA') {
    el.textContent = A.text;
  } else {
    var proto = tag === 'TEXTAREA' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var d = Object.getOwnPropertyDescriptor(proto, 'value');
    if (d && d.set) d.set.call(el, A.text); else el.value = A.text;
  }
  el.dispatchEvent(new InputEvent('input', { bubbles: true, inputType: 'insertText', data: A.text }));
}
el.dispatchEvent(new Event('change', { bubbles: true }));
var submitted = false;
if (A.submit) {
  var k = { key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true, cancelable: true };
  var go = el.dispatchEvent(new KeyboardEvent('keydown', k));
  go = el.dispatchEvent(new KeyboardEvent('keypress', k)) && go;
  el.dispatchEvent(new KeyboardEvent('keyup', k));
  var form = tag === 'TEXTAREA' ? null : (el.form || (el.closest && el.closest('form')));
  if (go && form) {
    if (form.requestSubmit) form.requestSubmit(); else form.submit();
    submitted = true;
  }
}
return JSON.stringify({ ok: true, what: describe(el), submitted: submitted });
"""

    private const val SCROLL = """
if (A.id != null) {
  var target = find(A.id);
  if (!target) return JSON.stringify({ error: 'gone' });
  target.scrollIntoView({ block: 'center', inline: 'nearest', behavior: 'instant' });
  return JSON.stringify({ ok: true, what: describe(target) });
}
var dy = Math.round(window.innerHeight * 0.8) * (A.down ? 1 : -1);
var before = window.scrollY;
window.scrollBy({ top: dy, behavior: 'instant' });
var moved = window.scrollY - before, inner = false;
if (moved === 0) {
  var c = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
  while (c && c !== document.body && c !== document.documentElement) {
    var s = getComputedStyle(c);
    if (/(auto|scroll|overlay)/.test(s.overflowY) && c.scrollHeight > c.clientHeight + 1) break;
    c = c.parentElement;
  }
  if (c && c !== document.body && c !== document.documentElement) {
    var b0 = c.scrollTop;
    c.scrollBy({ top: Math.round(c.clientHeight * 0.8) * (A.down ? 1 : -1), behavior: 'instant' });
    moved = c.scrollTop - b0;
    inner = true;
  }
}
return JSON.stringify({ ok: true, moved: Math.round(moved), inner: inner });
"""

    private const val CONTAINS = """
var body = document.body || document.documentElement;
return JSON.stringify({ found: (body.innerText || '').indexOf(A.text) >= 0 });
"""

    private const val SIGNATURE = """
var body = document.body;
return JSON.stringify({ sig: document.readyState + '|' + (body ? (body.innerText || '').length : 0) + '|' + document.getElementsByTagName('*').length });
"""
}
