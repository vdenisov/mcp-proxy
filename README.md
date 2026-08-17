# mcp-proxy

A local proxy that sits between an MCP client and an MCP server and replaces the client's identity.

Every MCP client announces itself in the `initialize` handshake (`clientInfo: {name, version}`), and
over HTTP it also sends a `User-Agent`. Public MCP servers therefore know which agentic tool you are
running. `mcp-proxy` rewrites that to whatever you configure, and strips the surrounding HTTP request
down to an allowlist.

Everything else is relayed **byte-for-byte**. The proxy parses only the JSON-RPC envelope; it never
looks inside a payload, so it cannot corrupt one and needs no changes when the protocol grows.

## Status

Working: hosted (Streamable HTTP) servers with static token auth or a fully proxy-owned OAuth flow,
local stdio servers spawned as child processes, a config home so a client entry names `linear`
rather than an absolute path, launcher scripts, and single-process server mode with a Docker image.
Token-storage hardening and the `--capture` blend-in helper are what is left.

## Build

```sh
./gradlew packageDistribution  # -> build/dist: mcp-proxy.jar plus the mcp-proxy / mcp-proxy.cmd launchers
./gradlew shadowJar            # -> build/libs/mcp-proxy-<version>.jar (the fat jar; -thin is not runnable)
./gradlew test
./gradlew test -PliveTests     # additionally runs tests that hit real servers
```

Put `build/dist` on your `PATH` (or copy its three files somewhere that already is) and `mcp-proxy`
works as a command. The launchers look for `mcp-proxy.jar` beside themselves, then in `../build/libs`,
so they also run straight from a checkout without installing anything; `MCP_PROXY_JAR` overrides the
search and `MCP_PROXY_JAVA_OPTS` passes JVM options. If `build/libs` has accumulated jars from several
builds the launchers refuse rather than guess which one you meant.

On Windows, quote any argument containing `&` or `|`: `mcp-proxy.cmd` forwards arguments through `%*`,
and cmd.exe re-parses those characters as command separators.

Tests come in four tiers: unit tests over the relay, config and framing; a transport matrix driven by
Ktor's `MockEngine` (status codes, inline SSE, session ids, header policy); an end-to-end tier that
runs the whole proxy over a real loopback socket and against a real child process, plus subprocess
tests that launch the packaged jar to prove nothing but protocol frames reaches stdout; and a live
tier that talks to real MCP servers. The end-to-end responses are payloads recorded from the live
Context7 server (`src/test/resources/fixtures/`), so they double as a real-world fidelity guard.

The live tier is opt-in and each test skips itself when what it needs is absent, so `-PliveTests` is
useful with whatever you happen to have configured:

| Test | Needs |
|---|---|
| Context7 over HTTP/SSE | network only |
| Hosted GitHub MCP | `MCP_PROXY_TEST_GITHUB_PAT` |
| Local github-mcp-server | `MCP_PROXY_TEST_GITHUB_PAT` and a working `docker` |
| OAuth against the TS SDK reference server | `MCP_PROXY_TEST_TS_SDK_DIR` (a typescript-sdk checkout with `npm install` done) and `npx` |
| OAuth against a real hosted server | `MCP_PROXY_TEST_OAUTH_URL` plus `MCP_PROXY_TEST_OAUTH_INTERACTIVE=1` - it opens a real browser |

Requires a JDK 21 toolchain (Gradle will locate one; the wrapper is checked in).

## Use

Write a config file:

```yaml
identity:
  name: mcp-proxy            # what the server is told
  version: 1.0.0
  userAgent: mcp-proxy/1.0

upstream:
  url: https://mcp.context7.com/mcp
  authToken: ${CONTEXT7_API_KEY}   # ${VAR} and ${VAR:-default} are expanded from the environment
```

Save it as `~/.mcp-proxy/context7.yaml`, then point the client at the proxy instead of the server:

```json
{ "context7": { "command": "java", "args": ["-jar", "/path/to/mcp-proxy.jar", "context7"] } }
```

A client config names `java` directly rather than the `mcp-proxy` launcher on purpose: the client
spawns this process itself, and on Windows going through `mcp-proxy.cmd` puts a `cmd.exe` between the
client and the JVM for no gain. The launcher is for the commands you type - `--login`, `--logout`,
`--check`.

Everything in the file can also be given on the command line, so the file is optional:

```sh
mcp-proxy --upstream-url https://mcp.context7.com/mcp --auth-token "$KEY"
```

### Where configs live

The `CONFIG` argument is either a **name** or a **path**, and which one it is depends on whether it
contains a path separator:

| Argument | Resolves to |
|---|---|
| `context7` | `~/.mcp-proxy/context7`, then `~/.mcp-proxy/context7.yaml`, then `~/.mcp-proxy/context7.yml` |
| `context7.yaml` | `~/.mcp-proxy/context7.yaml`, then `~/.mcp-proxy/context7.yaml.yaml`, then `~/.mcp-proxy/context7.yaml.yml` - a dot is not a separator, and nothing parses the extension |
| `./context7.yaml`, `configs/c.yaml`, `~/c.yaml`, absolute | a file path, relative to the working directory |

A name is looked up in the config home and **never** in the working directory, which is the one thing
worth knowing about this. MCP clients choose the working directory themselves - Claude Code uses the
project directory - so a name in a global client config would otherwise mean a different file in every
project, and a repository containing `linear.yaml` would shadow yours. Since a config can name any
`upstream.command` to spawn, that would make opening someone else's repository enough to run their
process. Use `./linear.yaml` when you do want the config from a checkout.

`MCP_PROXY_HOME` moves the home, and everything the proxy keeps there moves with it - configs, tokens,
registrations and log files:

```sh
MCP_PROXY_HOME=/srv/mcp-proxy mcp-proxy linear
```

It must be an **absolute** path; a relative one is refused rather than quietly resolved against the
working directory, which is the thing a home is meant to be independent of.

Set it in the client's `env` block as well as your shell if you set it at all. `--login` runs in your
shell and the proxy runs under the client's environment; if only one of them sees the variable, the
two use different token directories and the client keeps asking you to log in.

The resolved path is logged at INFO on startup, and a name that matches nothing lists every candidate
it tried.

The same rule applies to every path a config *file* names: `logging.file` and `oauth.tokenDir` are
taken as given when absolute (and `~` is expanded), but a **relative** one resolves against the config
home rather than the working directory. `logging.file: proxy.log` therefore means `<home>/proxy.log` on
every run, instead of one stray log file per project directory your client happens to start the proxy
from. A path typed on the command line is the exception and keeps normal shell semantics -
`mcp-proxy --check linear --log-file debug.log` writes `debug.log` in the directory you are standing
in.

### Local (stdio) servers

A server that runs locally is spawned as a child process and spoken to over its stdin and stdout.
There are no CLI equivalents for this - a command is a list and an environment is a map, neither of
which survives being squeezed into a flag - so stdio upstreams are configured in a file:

```yaml
identity:
  name: mcp-proxy
  version: 1.0.0

upstream:
  transport: stdio
  command: ["docker", "run", "-i", "--rm", "-e", "GITHUB_PERSONAL_ACCESS_TOKEN", "ghcr.io/github/github-mcp-server"]
  env:
    GITHUB_PERSONAL_ACCESS_TOKEN: ${GITHUB_PAT}
```

`env` is overlaid on the proxy's own environment rather than replacing it, so `PATH` and friends still
work. Passing the token through `env` and naming it bare in the command (`-e GITHUB_PERSONAL_ACCESS_TOKEN`,
no value) keeps it out of the process table.

`url`, `authToken` and `authHeader` are rejected with this transport rather than ignored: a config
naming a credential the proxy would never send is a surprise worth failing on.

The command is executed directly, without a shell. On Windows that means only real executables resolve
from `PATH` - a `.cmd` shim such as `npx` needs `["cmd", "/c", "npx", ...]`.

**The limitation worth understanding.** For a local server the proxy hides the *client* from the
server: the `initialize` handshake carries your configured identity, not `claude-code`. It cannot do
anything about the server's own traffic - when `github-mcp-server` calls the GitHub API it sets its own
`User-Agent`, and that connection never passes through the proxy. In this topology your client's
identity was never going to reach the API anyway; what the API learns is that some MCP server is in
use, not which tool is driving it.

### OAuth servers

For a hosted server that authorizes with OAuth (Linear, Notion, Sentry, ...) the proxy owns the whole
flow - discovery, client registration, the browser round, tokens, refresh - so the client name the
authorization server stores is also the one you configured, not the real client's:

```yaml
identity:
  name: mcp-proxy            # also becomes the registered OAuth client_name

upstream:
  url: https://mcp.linear.app/mcp
  oauth: {}                  # all defaults; note the braces - a bare `oauth:` parses as null (off)
```

Log in once, then configure the client as usual:

```sh
mcp-proxy --login linear     # opens the browser, stores the tokens
mcp-proxy --logout linear    # deletes them; add --forget-client to drop the registration too
```

A session started without a stored token still tries to authorize on the fly - the browser opens and
the URL is printed to stderr - but most MCP clients time out `initialize` after 30-60 s, so treat
that as a fallback: finish in the browser and the client's next attempt picks the token up.

What there is to configure, all optional:

```yaml
  oauth:
    scopes: []                  # override scope selection; default follows the server's challenge
    clientName: null            # registered client_name; default identity.name
    clientId: null              # pre-registered client (skips dynamic registration)
    clientSecret: null          # only with clientId
    callbackBindHost: 127.0.0.1
    callbackPort: 0             # 0 = random; fix it if your auth server pins redirect URIs
    callbackUrl: null           # advertised redirect URL when it differs from the bind address
    tokenDir: null              # default <config home>/tokens, i.e. ~/.mcp-proxy/tokens
    openBrowser: true           # false: only print the URL
    assumePkceS256: false       # for auth servers whose metadata omits PKCE support
```

Tokens and registrations live as plain JSON under `~/.mcp-proxy/tokens` (`$MCP_PROXY_HOME/tokens` when
that is set) - the same protection level every MCP client gives them today; at-rest hardening is stage
4.4 in `DESIGN.md`.

**Containerized / remote callback (stage 4.3 ready).** The browser must be able to reach the callback
listener. In a container, bind inside and advertise the URL the browser actually hits:

```yaml
  oauth:
    callbackBindHost: 0.0.0.0
    callbackPort: 8765
    callbackUrl: https://proxy.example.com/callback   # must be HTTPS when not loopback
```

A non-loopback redirect URI must be HTTPS, is matched exactly by the authorization server, and makes
the callback a network-reachable surface - leave the defaults alone unless you need this.

## Server mode: several upstreams, one process

One HTTP listener in front of several upstreams, so N servers cost one JVM instead of N. Write a
server config naming the upstreams to serve - each name resolves from the config home exactly like
the `CONFIG` argument, so your existing `linear.yaml` works unchanged:

```yaml
# ~/.mcp-proxy/server.yaml
server:
  port: 8090
  # bindHost: 0.0.0.0                       # inside a container; see the warning below
  # publicUrl: http://127.0.0.1:8090        # what a browser uses to reach this server
upstreams: [linear, github]
logging:
  file: server.log
```

```sh
mcp-proxy --serve server
```

> ### ⚠️ This listener has no authentication
>
> Anyone who can reach the port drives **every configured upstream with your stored credentials** -
> your Linear, your GitHub, your everything. There is no password, no token, no allowlist.
>
> Keep it on loopback. Publish the container port to `127.0.0.1:` only, never `0.0.0.0`, and never
> to the internet or a shared network. A cloud VM with this port open is a public API to your
> accounts. The proxy logs a warning at startup whenever it binds anything but loopback.

Each upstream gets three routes:

| Route | What it is |
|---|---|
| `/<name>/mcp` | the MCP endpoint - point your client here |
| `/<name>/login` | starts the OAuth flow in your browser |
| `/<name>/callback` | where the authorization server sends the browser back |

`GET /` lists the upstreams, their auth status and their endpoints. Names may contain letters,
digits, `.`, `_` and `-`; `api`, `ui`, `assets`, `static`, `admin`, `health`, `metrics` and anything
starting with `_` or `.` are reserved for the server's own future use.

**Logging in.** The server owns the callback port, so `--login` on the command line is for
per-process mode; here you open `http://127.0.0.1:8090/<name>/login` and the flow runs in the
browser. That is also the payoff: the redirect URI is now the same on every login, so the
authorization server registers the client **once** instead of once per login. A client request to an
upstream that is not authorized yet gets a JSON-RPC error naming the login URL.

Per-upstream `logging:` and `oauth.callback*` settings are ignored in server mode, with a warning -
the server logs centrally and serves the callback itself.

## Docker

```sh
./gradlew dockerBuild                    # -> ghcr.io/<owner>/mcp-proxy:<version> and :latest
docker run -d --name mcp-proxy \
  -p 127.0.0.1:8090:8090 \
  -v mcp-proxy-data:/data \
  ghcr.io/<owner>/mcp-proxy
```

`/data` is `$MCP_PROXY_HOME` inside the image: configs, tokens, registrations and logs all live
there, so **mount it** - a container restart otherwise loses the tokens, and the next login
registers a brand new OAuth client. The image expects `/data/server.yaml`; override the command
(`docker run ... --check linear`) to run anything else.

Inside the container bind `0.0.0.0` and publish to `127.0.0.1` on the host, and set `publicUrl` to
the host-side URL. The redirect URI is a string the authorization server hands to your *browser*,
which resolves it on the host - so `http://127.0.0.1:8090/...` keeps the RFC 8252 loopback exemption
even though the proxy runs in a container.

The same warning applies, more so: publishing this port beyond `127.0.0.1` exposes your credentials.

Pushing needs a GHCR login with a `write:packages` token, done outside the build:

```sh
docker login ghcr.io -u <owner>
./gradlew dockerPush
```

## Auditing what you disclose

```sh
mcp-proxy --check context7              # payload + headers we send, secrets redacted
mcp-proxy --check --loopback context7   # headers a real listener receives (ground truth)
```

`--loopback` is the one that catches headers added by the HTTP engine below our own code. Run it
after upgrading Ktor or the JDK. It **exits non-zero (5) when it finds something identifying**, so it
works as a CI gate rather than a report you have to read.

For a stdio upstream the audit is payload-only - a pipe has no headers - so it prints the handshake
the server would receive and nothing else. It does not spawn the child: running someone's `docker run`
as a side effect of an audit command is not a surprise worth having. `--loopback` is rejected there.

With OAuth, `--check` uses the cached token (refreshing it non-interactively if needed) and shows the
`Authorization` header redacted; without a usable token it reports that a login is required and exits
`6` - an audit command never opens a browser.

Exit codes: `0` clean, `2` config error, `3` upstream unreachable, `4` upstream failed
unrecoverably mid-session (for a local server, that includes the server process dying), `5` `--check`
found a leak, `6` OAuth login required, `7` an OAuth flow ran and failed, `1` CLI usage error.

## Notes

- Secrets belong in environment variables, referenced as `${VAR}`; the config file itself should not
  contain them.
- stdout carries protocol frames and nothing else. Logs go to stderr, or to `logging.file`. This is
  enforced at the file-descriptor level, not by convention.
- A spawned server is killed when the proxy exits, including on an abnormal exit. It is asked politely
  first (its stdin is closed, which is how a stdio server is told to stop), then terminated.
- `VENDORED.md` documents the one piece of third-party code and why it was forked.
