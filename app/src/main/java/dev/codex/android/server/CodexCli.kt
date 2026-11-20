package dev.codex.android.server

import java.io.File
import java.nio.file.attribute.PosixFilePermission

/**
 * On-phone `codex` CLI script (Milestone M8) — a POSIX shell script
 * dropped into the phone's PRoot Linux home (`~/bin/codex`, i.e.
 * `<rootfs>/root/bin/codex`) so the user can drive the agent from the
 * in-app terminal:
 *
 *   codex run "Build me a counter app"
 *   codex threads
 *   codex messages <thread-id>
 *   codex stop <thread-id>
 *   codex status
 *
 * The script talks ONLY to the loopback daemon. It NEVER embeds a
 * token and NEVER contacts any remote host — and it NEVER hardcodes
 * a port either: both the port and the per-boot Bearer token come
 * from `daemon.env`, which [writeDaemonEnv] writes to
 * `filesDir/linux/daemon.env` with 0600. The integrator must bind
 * that host file into the rootfs at `/etc/codex/daemon.env` via
 * `ProotShellEngine`'s `extraBinds` (see [installIntoRootfs] KDoc):
 * the script reads `/etc/codex/daemon.env` first and falls back to a
 * `daemon.env` next to itself.
 *
 * Adaptations vs the staged H5 code:
 * - The staged script hardcoded `127.0.0.1:8787` and read only a
 *   script-directory `daemon.env` containing just the token.
 *   `generateDaemonEnv` now emits `CODEX_DAEMON_PORT` +
 *   `CODEX_DAEMON_TOKEN`, and the script fails (exit 1) when either
 *   is missing instead of silently using a baked-in port.
 * - New entry points [buildCliScript] (canonical script builder),
 *   [writeDaemonEnv] (host-side `filesDir/linux/daemon.env`, 0600)
 *   and [installIntoRootfs] (script into the rootfs + env file).
 *   The staged `install(binDir, token)` is kept as a legacy helper
 *   for the DI-note call shape; the M8 path is [installIntoRootfs].
 * - `status` now hits `/v1/health` (the daemon's canonical route;
 *   the staged script used the legacy `/health` alias).
 * - JSON escaping now escapes backslashes as well as quotes.
 *   Limitation, unchanged: values with control characters still
 *   need `jq` (not bundled in the Alpine minirootfs) to be exact.
 */
object CodexCli {

    const val DAEMON_HOST = "127.0.0.1"
    const val DAEMON_ENV_FILENAME = "daemon.env"
    const val CLI_SCRIPT_FILENAME = "codex"

    /** Guest path the script reads `daemon.env` from (bound in by the integrator). */
    const val GUEST_DAEMON_ENV_PATH = "/etc/codex/daemon.env"

    /** Guest path of the installed script (`~/bin/codex` inside the rootfs). */
    const val GUEST_CLI_PATH = "/root/bin/codex"

    /**
     * Generates the full `codex` shell script text (pure, JVM-testable).
     * Port and token come from `daemon.env` at run time; nothing
     * secret and no port is baked into the script.
     */
    fun buildCliScript(): String = """
        #!/bin/sh
        # Codex CLI — talks ONLY to the on-phone loopback daemon (127.0.0.1).
        # Port + token are read from daemon.env (0600) at /etc/codex/daemon.env
        # (bound in from the host filesDir/linux/daemon.env), falling back to
        # a daemon.env next to this script. Neither is ever hardcoded here.
        set -eu
        SCRIPT_DIR="$(cd "$(dirname "${'$'}0")" && pwd)"
        ENV_FILE="/etc/codex/daemon.env"
        case "${'$'}{1:-}" in
          -h|--help)
            echo "Usage: codex <run|threads|messages|stop|status> [args]"
            exit 0
            ;;
        esac
        if [ ! -f "${'$'}ENV_FILE" ]; then
          ENV_FILE="${'$'}SCRIPT_DIR/daemon.env"
        fi
        if [ ! -f "${'$'}ENV_FILE" ]; then
          echo "codex: daemon.env not found (looked in /etc/codex/daemon.env and ${'$'}SCRIPT_DIR/daemon.env; is the Codex daemon running?)" >&2
          exit 1
        fi
        # shellcheck disable=SC1090
        . "${'$'}ENV_FILE"
        if [ -z "${'$'}{CODEX_DAEMON_TOKEN:-}" ]; then
          echo "codex: CODEX_DAEMON_TOKEN missing from daemon.env" >&2
          exit 1
        fi
        if [ -z "${'$'}{CODEX_DAEMON_PORT:-}" ]; then
          echo "codex: CODEX_DAEMON_PORT missing from daemon.env" >&2
          exit 1
        fi
        BASE="http://127.0.0.1:${'$'}CODEX_DAEMON_PORT"
        AUTH="Authorization: Bearer ${'$'}CODEX_DAEMON_TOKEN"

        usage() {
          echo "Usage: codex <run|threads|messages|stop|status> [args]"
          echo "  codex run \"<goal>\" [thread-id]"
          echo "  codex threads"
          echo "  codex messages <thread-id>"
          echo "  codex stop <thread-id>"
          echo "  codex status"
        }

        daemon_get() {
          if command -v curl >/dev/null 2>&1; then
            curl --noproxy '*' -fsS "${'$'}BASE${'$'}1" -H "${'$'}AUTH"
          else
            /bin/busybox wget -Y off -qO- --header "${'$'}AUTH" "${'$'}BASE${'$'}1"
          fi
        }

        daemon_post() {
          if command -v curl >/dev/null 2>&1; then
            curl --noproxy '*' -fsS -X POST "${'$'}BASE${'$'}1" -H "${'$'}AUTH" -H 'Content-Type: application/json' -d "${'$'}2"
          else
            /bin/busybox wget -Y off -qO- --header "${'$'}AUTH" --header 'Content-Type: application/json' --post-data "${'$'}2" "${'$'}BASE${'$'}1"
          fi
        }

        json_escape() {
          printf '%s' "${'$'}1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'
        }

        cmd="${'$'}{1:-}"
        case "${'$'}cmd" in
          run)
            goal="${'$'}{2:-}"
            thread="${'$'}{3:-}"
            if [ -z "${'$'}goal" ]; then usage; exit 2; fi
            if [ -n "${'$'}thread" ]; then
              payload="$(printf '{"threadId": "%s", "goal": "%s"}' "${'$'}thread" "$(json_escape "${'$'}goal")")"
            else
              payload="$(printf '{"goal": "%s"}' "$(json_escape "${'$'}goal")")"
            fi
            daemon_post /v1/turn "${'$'}payload"
            echo
            ;;
          threads)
            daemon_get /v1/threads
            echo
            ;;
          messages)
            thread="${'$'}{2:-}"
            if [ -z "${'$'}thread" ]; then usage; exit 2; fi
            daemon_get "/v1/threads/${'$'}thread/messages"
            echo
            ;;
          stop)
            thread="${'$'}{2:-}"
            if [ -z "${'$'}thread" ]; then usage; exit 2; fi
            daemon_post /v1/stop "{\"threadId\": \"$(json_escape "${'$'}thread")\"}"
            echo
            ;;
          status)
            daemon_get /v1/health
            echo
            ;;
          *)
            usage
            exit 2
            ;;
        esac
    """.trimIndent() + "\n"

    /** Backwards-compatible alias for [buildCliScript]. */
    fun generateScript(): String = buildCliScript()

    /** Generates the `daemon.env` content: port + token, nothing else (0600, never committed). */
    fun generateDaemonEnv(bearerToken: String, port: Int = LocalDaemon.DEFAULT_PORT): String =
        "CODEX_DAEMON_PORT=$port\nCODEX_DAEMON_TOKEN=$bearerToken\n"

    /**
     * Writes `daemon.env` into [linuxDir] — the M8 location is
     * `filesDir/linux/daemon.env` — with 0600 permissions, and
     * returns the file. Call on every boot: the daemon rotates its
     * token per boot, so a stale file authenticates nothing.
     */
    fun writeDaemonEnv(
        linuxDir: File,
        bearerToken: String,
        port: Int = LocalDaemon.DEFAULT_PORT,
    ): File {
        linuxDir.mkdirs()
        val envFile = File(linuxDir, DAEMON_ENV_FILENAME)
        envFile.writeText(generateDaemonEnv(bearerToken, port))
        restrictToOwner(envFile)
        return envFile
    }

    /**
     * M8 install: writes the CLI script to `<rootfsDir>/root/bin/codex`
     * (guest `~/bin/codex`) and the env file to
     * `filesDir/linux/daemon.env` via [writeDaemonEnv], and returns
     * the script file.
     *
     * INTEGRATOR REQUIREMENT — the script reads its env from
     * `/etc/codex/daemon.env` inside the guest, so the `ProotShellEngine`
     * that runs the terminal MUST be constructed with the extra bind:
     * `extraBinds = listOf(File(context.filesDir, "linux/daemon.env").absolutePath to "/etc/codex/daemon.env")`.
     * Without that bind the script falls back to a `daemon.env` next
     * to itself, which [installIntoRootfs] deliberately does NOT
     * write (the token must live in exactly one 0600 host file).
     */
    fun installIntoRootfs(
        rootfsDir: File,
        linuxDir: File,
        bearerToken: String,
        port: Int = LocalDaemon.DEFAULT_PORT,
    ): File {
        writeDaemonEnv(linuxDir, bearerToken, port)
        val guestBinDir = File(File(rootfsDir, "root"), "bin").apply { mkdirs() }
        val scriptFile = File(guestBinDir, CLI_SCRIPT_FILENAME)
        scriptFile.writeText(buildCliScript())
        scriptFile.setExecutable(false, false)
        scriptFile.setExecutable(true, true)
        scriptFile.setReadable(false, false)
        scriptFile.setReadable(true, true)
        scriptFile.setWritable(false, false)
        scriptFile.setWritable(true, true)
        return scriptFile
    }

    /**
     * Legacy install: writes the CLI script + `daemon.env` into
     * [binDir] (the DI-note `filesDir/bin` shape) and returns the
     * script file. Kept for callers that run the CLI outside PRoot;
     * the M8 path is [installIntoRootfs].
     */
    fun install(binDir: File, bearerToken: String, port: Int = LocalDaemon.DEFAULT_PORT): File {
        binDir.mkdirs()
        val scriptFile = File(binDir, CLI_SCRIPT_FILENAME)
        scriptFile.writeText(buildCliScript())
        scriptFile.setExecutable(true, true)
        scriptFile.setReadable(true, true)
        scriptFile.setWritable(true, true)

        val envFile = File(binDir, DAEMON_ENV_FILENAME)
        envFile.writeText(generateDaemonEnv(bearerToken, port))
        restrictToOwner(envFile)
        return scriptFile
    }

    /** Best-effort owner-only (0600) permissions: POSIX chmod, then the `File` setters. */
    private fun restrictToOwner(file: File) {
        runCatching {
            java.nio.file.Files.setPosixFilePermissions(
                file.toPath(),
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
        }
        file.setExecutable(false, false)
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}
