package com.lightningkite.services.human

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * A panel displayed on the human services dashboard.
 * One panel per channel (email, sms); inbound and outbound services for the same
 * channel share a single panel so the dashboard shows a combined transcript.
 */
public interface HumanServicePanel {
    /** Unique identifier per channel, e.g. "email", "sms". Same id on same port => same panel. */
    public val id: String
    /** Display name shown in the dashboard tab */
    public val displayName: String
    /** Returns an HTML form fragment for simulating an inbound message */
    public fun formHtml(): String
    /** Handles form submission, returns a status message */
    public suspend fun handleSubmit(formData: Map<String, String>): String
    /**
     * JSON array of conversation entries. Each entry MUST include a `direction`
     * field (`"inbound"` or `"outbound"`) and a `timestamp` field so the dashboard
     * can render a chronological transcript.
     */
    public fun messagesJson(): String
    /** Clears conversation history */
    public fun clear()
}

/**
 * Singleton registry that manages shared [HttpServer] instances keyed by port,
 * and shared panels keyed by (port, panelId). Services holding a ref to a panel
 * are tracked so the panel is removed when the last service disconnects.
 */
internal object HumanServiceRegistry {

    private data class PanelSlot(val panel: HumanServicePanel, var refCount: Int)

    private val servers = ConcurrentHashMap<Int, HttpServer>()
    private val panels = ConcurrentHashMap<Int, MutableMap<String, PanelSlot>>()

    /**
     * Acquires (or creates via [factory]) the shared panel identified by [panelId] on [port].
     * Returns the bound port and the panel instance. Call [release] with the returned port
     * and id when done.
     */
    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <P : HumanServicePanel> acquire(port: Int, panelId: String, factory: () -> P): Pair<Int, P> {
        val actualPort = ensureServer(port)
        val slots = panels.getOrPut(actualPort) { mutableMapOf() }
        val slot = slots[panelId]
        return if (slot != null) {
            slot.refCount++
            actualPort to (slot.panel as P)
        } else {
            val created = factory()
            slots[panelId] = PanelSlot(created, 1)
            actualPort to created
        }
    }

    @Synchronized
    fun release(port: Int, panelId: String) {
        val slots = panels[port] ?: return
        val slot = slots[panelId] ?: return
        slot.refCount--
        if (slot.refCount <= 0) slots.remove(panelId)
        if (slots.isEmpty()) {
            panels.remove(port)
            servers.remove(port)?.stop(1)
        }
    }

    private fun ensureServer(port: Int): Int {
        val existing = servers[port]
        if (existing != null) return existing.address.port
        val server = createServer(port)
        val actualPort = server.address.port
        servers[actualPort] = server
        return actualPort
    }

    private fun createServer(port: Int): HttpServer {
        val server = HttpServer.create(InetSocketAddress(port), 0)
        val actualPort = server.address.port
        server.executor = Executors.newCachedThreadPool { r ->
            Thread(r, "human-services-$actualPort").apply { isDaemon = true }
        }
        server.createContext("/") { exchange ->
            try {
                when {
                    exchange.requestURI.path == "/" && exchange.requestMethod == "GET" ->
                        serveDashboard(exchange, actualPort)
                    exchange.requestURI.path == "/api/submit" && exchange.requestMethod == "POST" ->
                        handleSubmit(exchange, actualPort)
                    exchange.requestURI.path == "/api/messages" && exchange.requestMethod == "GET" ->
                        serveMessages(exchange, actualPort)
                    exchange.requestURI.path == "/api/clear" && exchange.requestMethod == "POST" ->
                        handleClear(exchange, actualPort)
                    else ->
                        exchange.sendResponseHeaders(404, -1)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                try { exchange.sendResponseHeaders(500, -1) } catch (_: Exception) {}
            } finally {
                exchange.close()
            }
        }
        server.start()
        println("Human Services dashboard available at http://localhost:$actualPort/")
        return server
    }

    private fun panelsOn(port: Int): List<HumanServicePanel> =
        panels[port]?.values?.map { it.panel } ?: emptyList()

    private fun findPanel(port: Int, id: String?): HumanServicePanel? =
        panels[port]?.get(id)?.panel

    private fun serveDashboard(exchange: HttpExchange, port: Int) {
        val html = dashboardHtml(panelsOn(port))
        val bytes = html.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun handleSubmit(exchange: HttpExchange, port: Int) {
        val body = exchange.requestBody.bufferedReader().readText()
        val formData = parseFormUrlEncoded(body)
        val serviceId = formData["_service"] ?: ""
        val panel = findPanel(port, serviceId)
        val result = if (panel != null) {
            kotlinx.coroutines.runBlocking { panel.handleSubmit(formData) }
        } else {
            "Unknown service: $serviceId"
        }
        val bytes = jsonString(result).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun serveMessages(exchange: HttpExchange, port: Int) {
        val params = parseQuery(exchange.requestURI.query)
        val panel = findPanel(port, params["service"])
        val json = panel?.messagesJson() ?: "[]"
        val bytes = json.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun handleClear(exchange: HttpExchange, port: Int) {
        val params = parseQuery(exchange.requestURI.query)
        findPanel(port, params["service"])?.clear()
        val bytes = """{"ok":true}""".toByteArray(Charsets.UTF_8)
        exchange.responseHeaders["Content-Type"] = listOf("application/json; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun parseQuery(query: String?): Map<String, String> {
        if (query.isNullOrBlank()) return emptyMap()
        return query.split("&").associate {
            val (k, v) = it.split("=", limit = 2)
            k to java.net.URLDecoder.decode(v, "UTF-8")
        }
    }

    private fun parseFormUrlEncoded(body: String): Map<String, String> {
        if (body.isBlank()) return emptyMap()
        return body.split("&").associate {
            val parts = it.split("=", limit = 2)
            val k = java.net.URLDecoder.decode(parts[0], "UTF-8")
            val v = if (parts.size > 1) java.net.URLDecoder.decode(parts[1], "UTF-8") else ""
            k to v
        }
    }
}

private fun dashboardHtml(panels: List<HumanServicePanel>): String = buildString {
    append("""<!DOCTYPE html>
<html><head>
<meta charset="utf-8">
<title>Human Services Dashboard</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,sans-serif;font-size:14px;background:#fff;color:#111;padding:12px}
h1{font-size:18px;margin-bottom:8px}
.tabs{display:flex;gap:4px;border-bottom:2px solid #ccc;margin-bottom:8px}
.tab{padding:6px 14px;cursor:pointer;border:1px solid #ccc;border-bottom:none;border-radius:4px 4px 0 0;background:#f5f5f5}
.tab.active{background:#fff;font-weight:bold;border-bottom:1px solid #fff;margin-bottom:-2px}
.panel{display:none}
.panel.active{display:block}
.form-section{border:1px solid #ddd;padding:10px;margin-bottom:10px;border-radius:3px;background:#fafafa}
.form-section label{display:block;margin-bottom:6px;font-weight:bold;font-size:13px}
.form-section input,.form-section textarea{width:100%;padding:4px 6px;border:1px solid #ccc;border-radius:2px;font-family:inherit;font-size:13px;margin-bottom:8px}
.form-section textarea{height:80px;font-family:monospace}
.form-section button{padding:6px 16px;cursor:pointer;background:#2563eb;color:#fff;border:none;border-radius:3px}
.form-section button:hover{background:#1d4ed8}
.status{margin-top:6px;font-size:13px;color:#666}
.status.ok{color:#16a34a}
.status.err{color:#dc2626}
.toolbar{margin-bottom:8px;display:flex;gap:8px;align-items:center}
.toolbar button{padding:4px 10px;cursor:pointer}
.count{color:#666;font-size:13px}
h3{font-size:14px;margin:10px 0 6px}
.msg{border:1px solid #ddd;padding:8px;margin-bottom:6px;border-radius:3px;max-width:85%}
.msg.inbound{background:#fff;border-color:#d4d4d4;margin-right:auto}
.msg.outbound{background:#dbeafe;border-color:#93c5fd;margin-left:auto}
.msg-badge{display:inline-block;font-size:11px;font-weight:bold;padding:1px 6px;border-radius:3px;margin-right:6px}
.msg-badge.inbound{background:#e5e7eb;color:#374151}
.msg-badge.outbound{background:#2563eb;color:#fff}
.msg-header{font-weight:bold;margin-bottom:4px}
.msg-meta{color:#666;font-size:12px;margin-bottom:4px}
.msg-body{white-space:pre-wrap;font-family:monospace;font-size:13px}
iframe.email-preview{width:100%;height:200px;border:1px solid #ddd;margin-top:4px;background:#fff}
</style>
</head><body>
<h1>Human Services Dashboard</h1>
""")

    // Tabs
    append("""<div class="tabs">""")
    for ((i, p) in panels.withIndex()) {
        val active = if (i == 0) " active" else ""
        append("""<div class="tab$active" onclick="switchTab('${p.id}')" id="tab-${p.id}">${p.displayName}</div>""")
    }
    append("</div>\n")

    // Panels
    for ((i, p) in panels.withIndex()) {
        val active = if (i == 0) " active" else ""
        append("""<div class="panel$active" id="panel-${p.id}">""")
        append(p.formHtml())
        append("""<h3>Conversation (<span id="count-${p.id}">0</span>)</h3>""")
        append("""<div class="toolbar"><button onclick="clearMessages('${p.id}')">Clear</button></div>""")
        append("""<div id="messages-${p.id}"></div>""")
        append("</div>\n")
    }

    append("""
<script>
const serviceIds = [${panels.joinToString(",") { "'${it.id}'" }}];
let activeTab = serviceIds[0] || '';

function switchTab(id) {
  activeTab = id;
  document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.panel').forEach(p => p.classList.remove('active'));
  document.getElementById('tab-' + id)?.classList.add('active');
  document.getElementById('panel-' + id)?.classList.add('active');
}

function escapeHtml(s) {
  const d = document.createElement('div');
  d.textContent = s;
  return d.innerHTML;
}

function submitForm(serviceId) {
  const form = document.getElementById('form-' + serviceId);
  const data = new URLSearchParams(new FormData(form));
  data.set('_service', serviceId);
  const status = document.getElementById('status-' + serviceId);
  status.textContent = 'Sending...';
  status.className = 'status';
  fetch('/api/submit', { method: 'POST', body: data.toString(), headers: {'Content-Type':'application/x-www-form-urlencoded'} })
    .then(r => r.json())
    .then(msg => { status.textContent = msg; status.className = 'status ok'; poll(); })
    .catch(e => { status.textContent = 'Error: ' + e; status.className = 'status err'; });
  return false;
}

function directionBadge(msg) {
  const d = msg.direction || 'inbound';
  const label = d === 'outbound' ? 'OUT →' : '← IN';
  return '<span class="msg-badge ' + d + '">' + label + '</span>';
}

function renderEmail(msg) {
  const d = msg.direction || 'inbound';
  let html = '<div class="msg ' + d + '">';
  html += '<div class="msg-header">' + directionBadge(msg) + escapeHtml(msg.subject || '(no subject)') + '</div>';
  html += '<div class="msg-meta">';
  html += '<b>Time:</b> ' + escapeHtml(msg.timestamp || '') + ' &nbsp; ';
  html += '<b>From:</b> ' + escapeHtml(msg.from || '') + ' &nbsp; ';
  html += '<b>To:</b> ' + escapeHtml(msg.to || '');
  html += '</div>';
  if (msg.htmlBody) {
    html += '<iframe class="email-preview" sandbox srcdoc="' + escapeHtml(msg.htmlBody) + '"></iframe>';
  } else {
    html += '<div class="msg-body">' + escapeHtml(msg.plainText || '') + '</div>';
  }
  html += '</div>';
  return html;
}

function renderSms(msg) {
  const d = msg.direction || 'inbound';
  return '<div class="msg ' + d + '">' +
    '<div class="msg-meta">' + directionBadge(msg) +
    '<b>Time:</b> ' + escapeHtml(msg.timestamp || '') +
    ' &nbsp; <b>From:</b> ' + escapeHtml(msg.from || '') +
    ' &nbsp; <b>To:</b> ' + escapeHtml(msg.to || '') + '</div>' +
    '<div class="msg-body">' + escapeHtml(msg.body || '') + '</div></div>';
}

const renderers = { 'email': renderEmail, 'sms': renderSms };

function byTimestamp(a, b) {
  return (a.timestamp || '').localeCompare(b.timestamp || '');
}

function poll() {
  serviceIds.forEach(id => {
    fetch('/api/messages?service=' + id)
      .then(r => r.json())
      .then(msgs => {
        document.getElementById('count-' + id).textContent = msgs.length;
        const container = document.getElementById('messages-' + id);
        const render = renderers[id] || renderSms;
        container.innerHTML = msgs.slice().sort(byTimestamp).map(render).join('');
      })
      .catch(() => {});
  });
}

function clearMessages(id) {
  fetch('/api/clear?service=' + id, { method: 'POST' }).then(() => poll());
}

poll();
setInterval(poll, 2000);
</script>
</body></html>""")
}
