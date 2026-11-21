# HTTP debugger

Per-VM switch: VM settings › **HTTP debugger** (applies at the next full start). Then the VM menu has **HTTP debugger** with three
tabs: **Log** (every request, tap for headers and bodies, *Mock this*, *Copy curl*, *Save HAR*), **Rules** and **Setup**.

## What it does

* Logs HTTP and HTTPS requests of the guest: URL, query parameters, headers, bodies (gzip/deflate shown decoded), status, timing,
  which rule answered. Connections that are not HTTP are relayed and logged as TCP with byte counts and the first bytes.
* Reads HTTPS by ending the guest's TLS with its own CA (MITM). The CA is created once per install and written into the
  firmware's trust store at each start (`system/etc/security/cacerts/<hash>.0`, the Conscrypt APEX copy on 14+, and
  `cacerts.bks` for Android 1.x–3.x; the original BKS is kept as `cacerts.bks.aemu-orig` and put back when the debugger is off).
* Answers requests with guaranteed responses, blocks, redirects to another server, or rewrites request/response; overrides DNS.
* TLS 1.0/1.1 clients (Android 1.x–2.x) are served by a built-in TLS server (RSA key exchange; AES-CBC, 3DES, RC4), because the
  phone's own TLS stack no longer speaks that. TLS 1.2+/1.3 clients go through the phone's JSSE.

## How the guest is pointed at it

| engine | TCP | DNS |
|---|---|---|
| native arm64 (n64) | the runner (`run64`) sends **every** outgoing TCP connection to the debugger, any port, with the real destination in a 28-byte header (`native/common/aemu_nethook.h`); loopback and unix sockets are left alone | UDP to port 53 goes to the debugger's DNS answerer; `dnsproxyd` (5.0–7.x) is answered by the host as before, through the debugger |
| qemu (kk) | qemu's built-in redirect of ports **80 and 443** (and 53), plus listeners on `extraPorts` (config.json, default 5222 5223 5228 8080 8443) | names resolve to loopback stand-ins `127.77.x.y` so the debugger knows which host a connection was for |

## Rules (`Rules` tab; stored in `<vm>/netdebug/rules.json`)

```json
{
  "tlsPassthrough": ["*.pinned.example.com"],
  "dns": [{"host": "api.dead.example", "answer": "203.0.113.5"}, {"host": "*.gone.example", "answer": "NXDOMAIN"}],
  "rules": [{
    "id": "login", "name": "Accept any sign-in", "enabled": true,
    "match": {"scheme": "https", "host": "www.google.com", "path": "/accounts/ClientLogin", "method": "POST",
              "query": {"service": "*"}, "headers": {"User-Agent": "*Android*"}, "body": "re:Email=.*"},
    "action": {"type": "mock", "status": 200, "template": true, "delayMs": 0,
               "headers": {"Content-Type": "text/plain"}, "body": "SID=${random.hex:40}\nLSID=${random.hex:40}\nAuth=${random.hex:60}\n"}
  }]
}
```

First matching rule wins. `host`, `path`, header and query values are globs (`*`, `?`); `re:<regex>` makes a full-match regex.
Actions: `mock` (`status`, `reason`, `headers`, `body` | `bodyBase64` | `bodyFile`, `template`), `block` (drop the connection),
`redirect` (`toHost`, `toPort`, `tls`, `rewriteHost`), `rewrite` (`request`: `setHeaders`, `removeHeaders`, `setPath`, `setMethod`,
`bodyReplace`; `response`: `status`, `setHeaders`, `removeHeaders`, `bodyReplace`, `body`), `passthrough` (stop here, change nothing).
Templates: `${host} ${path} ${method} ${url} ${query.X} ${form.X} ${header.X} ${now.unix} ${now.iso} ${uuid} ${random.hex:N}`.

## Limits

* HTTP/1.x only (no ALPN, clients fall back from h2). Bodies are buffered up to 16 MB per message.
* An app that pins its server's certificate or ships its own trust list refuses the CA: the log says "TLS handshake with the guest
  failed"; add the host to `tlsPassthrough`.
* qemu engine: only ports 80/443 and the extra ports are seen; hard-coded IP addresses (no DNS) are seen without a host name.
* SSL 3.0-only clients are not served. Android 1.x `cacerts.bks` is only rewritten when it is a plain certificate store.
