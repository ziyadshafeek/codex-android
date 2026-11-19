package dev.codex.android.linux

import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Result of executing one shell command inside the PRoot Linux rootfs. */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
)

/**
 * Interactive PTY session tracked by [ProotShellEngine].
 * [terminalSession] holds the Apache-2.0 Termux `TerminalSession` when attached
 * to [dev.codex.android.terminal.TerminalScreen] on Android; remains null in pure JVM tests.
 */
data class PtySession(
    val id: String,
    val title: String,
    val cols: Int,
    val rows: Int,
    val command: List<String>,
    val workingDir: File,
    val createdAtMillis: Long = System.currentTimeMillis(),
    @Volatile var pid: Long = -1L,
    @Volatile var isAlive: Boolean = true,
    val terminalSession: TerminalSession? = null,
)

/**
 * Long-lived piped-stdio process running inside the PRoot rootfs (or, via the
 * [ProotShellEngine.startRawProcess] test seam, directly on the host).
 *
 * This is the programmatic counterpart to the interactive PTY sessions from
 * [ProotShellEngine.startPtySession]: PTY is for humans (the terminal UI),
 * piped stdio is for machines — JSON-RPC over stdin/stdout, e.g. local stdio
 * MCP servers (M8 `ProotStdioJsonRpcTransport`).
 *
 * - stdin/stdout are piped (no PTY, no `TerminalSession`). Lines are written
 *   with [writeLine] and read with [readLine].
 * - stderr is drained on a dedicated daemon thread so a chatty server can
 *   never block on a full stderr pipe; only the last
 *   [ProotShellEngine.STDIO_STDERR_TAIL_CHARS] chars are kept ([stderrTail]).
 * - stdout is read on a dedicated daemon thread into a queue, so [readLine]
 *   can time out instead of blocking forever.
 * - [kill] reuses the engine's PGID cleanup ([ProotShellEngine.killProcessGroup]).
 *
 * Created only by [ProotShellEngine.startProcess] /
 * [ProotShellEngine.startRawProcess], which register the process for
 * cleanup. Call [kill] (or [close]) when done; the reader/drain threads are
 * daemons and exit when the process dies.
 */
open class StdioProcess internal constructor(
    val id: String,
    /** The host argv actually spawned (proot argv for [ProotShellEngine.startProcess]). */
    val command: List<String>,
    private val process: Process,
    private val engine: ProotShellEngine,
) {
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
    // Queue of String lines, plus EOF_SENTINEL on end-of-stream.
    // (LinkedBlockingQueue forbids null elements, so EOF needs a sentinel object.)
    private val lineQueue = LinkedBlockingQueue<Any>()
    private val EOF_SENTINEL = Any()
    @Volatile private var eofSeen = false
    private val closed = AtomicBoolean(false)
    private val stderrLock = Any()
    private val stderrBuf = StringBuilder()

    /** PID of the child process, or -1 when it could not be determined. */
    val pid: Long = engine.extractProcessPid(process)

    /** True while the process has not exited and [kill] has not been called. */
    val isAlive: Boolean
        get() = !closed.get() && process.isAlive

    /** Exit code once the process has finished, or null while it is running. */
    val exitCode: Int?
        get() = runCatching { if (process.isAlive) null else process.exitValue() }.getOrNull()

    /** Last up-to-[ProotShellEngine.STDIO_STDERR_TAIL_CHARS] chars of stderr (diagnostics). */
    val stderrTail: String
        get() = synchronized(stderrLock) { stderrBuf.toString() }

    private val readerThread = Thread {
        try {
            while (true) {
                val line = reader.readLine() ?: break
                lineQueue.put(line)
            }
        } catch (_: Exception) {
            // Stream closed by kill() — fall through to EOF handling.
        } finally {
            eofSeen = true
            runCatching { reader.close() }
            if (!process.isAlive) engine.untrackProcess(process)
            lineQueue.put(EOF_SENTINEL)
        }
    }.apply { isDaemon = true; name = "stdio-reader-$id"; start() }

    private val stderrThread = Thread {
        try {
            val errReader = process.errorStream.bufferedReader(Charsets.UTF_8)
            val buf = CharArray(4096)
            while (true) {
                val n = errReader.read(buf)
                if (n == -1) break
                synchronized(stderrLock) {
                    stderrBuf.append(buf, 0, n)
                    if (stderrBuf.length > ProotShellEngine.STDIO_STDERR_TAIL_CHARS) {
                        stderrBuf.delete(0, stderrBuf.length - ProotShellEngine.STDIO_STDERR_TAIL_CHARS)
                    }
                }
            }
        } catch (_: Exception) {
            // Stream closed by kill() — drain thread just exits.
        }
    }.apply { isDaemon = true; name = "stdio-stderr-$id"; start() }

    /**
     * Writes one line (plus `\n`) to the process stdin and flushes.
     * Throws [java.io.IOException] if the process has exited / stdin is closed.
     */
    open suspend fun writeLine(line: String) = withContext(Dispatchers.IO) {
        synchronized(writer) {
            writer.write(line)
            writer.newLine()
            writer.flush()
        }
    }

    /**
     * Reads one stdout line, or null on timeout ([timeoutMs]) or EOF.
     * Queued lines ahead of EOF are drained first: EOF is reported only
     * once the queue is empty AND the reader thread has finished, so a
     * line that arrived before the stream ended is never dropped.
     * After EOF, returns null immediately without waiting. The timeout is a
     * floor of 0 (no waiting); negative values behave as 0.
     */
    open suspend fun readLine(timeoutMs: Long): String? = withContext(Dispatchers.IO) {
        // Drain anything already queued: the reader thread sets eofSeen
        // before enqueueing EOF_SENTINEL, so lines can sit ahead of it.
        lineQueue.poll()?.let { return@withContext if (it === EOF_SENTINEL) null else it as String }
        if (eofSeen) return@withContext null
        val item = lineQueue.poll(timeoutMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
        if (item === EOF_SENTINEL) null else item as String?
    }

    /**
     * Terminates the process and its whole process group (SIGTERM then
     * SIGKILL via [ProotShellEngine.killProcessGroup], plus
     * `destroyForcibly()`), closes stdin/stdout, and unregisters it.
     * Idempotent.
     */
    fun kill() {
        if (!closed.compareAndSet(false, true)) return
        engine.killProcessGroup(pid, process)
        runCatching { writer.close() }
        runCatching { reader.close() }
        engine.untrackProcess(process)
    }

    /** Alias for [kill], for transport-style callers. */
    fun close() = kill()
}

/**
 * Process execution engine running commands inside the rootless Alpine Linux rootfs
 * via `libproot.so` located in [nativeLibDir] (execve from `applicationInfo.nativeLibraryDir`,
 * never JNI, never root).
 *
 * Includes PGID process-group cleanup on kill/timeout and a Phantom-Process diagnostic helper.
 *
 * Two process APIs coexist: [startPtySession] (interactive PTY via the Termux
 * `TerminalSession`, for the terminal UI) and [startProcess] (long-lived piped
 * stdio, for programmatic JSON-RPC such as local stdio MCP servers).
 *
 * NOTE: there is no host-side BusyBox binary in this milestone. The Alpine
 * minirootfs itself ships `./bin/busybox`, so in-rootfs shells already have
 * it; no `busyboxBinary()` helper exists on purpose.
 */
open class ProotShellEngine(
    val rootfsDir: File,
    val nativeLibDir: File,
    private val workspaceBindDir: File? = null,
    private val extraBinds: List<Pair<String, String>> = emptyList(),
    private val signalSender: ((pgidOrPid: Long, signal: Int) -> Boolean)? = null,
) {
    private val sessions = ConcurrentHashMap<String, PtySession>()
    private val activeProcesses = ConcurrentHashMap<String, Process>()

    /** Path to the packaged `libproot.so` executable inside `nativeLibraryDir`. */
    fun prootBinary(): File = File(nativeLibDir, "libproot.so")

    /** Path to the packaged `libproot-loader.so` helper inside `nativeLibraryDir`. */
    fun prootLoaderBinary(): File = File(nativeLibDir, "libproot-loader.so")

    /**
     * Builds the exact `proot` argv list (`-r <rootfs>` + `-b` binds + `-w /workspace` + `/bin/sh -c <cmd>`).
     * Pure and JVM-testable.
     */
    fun buildProotCommand(
        shellCommand: String? = null,
        workingDirInGuest: String = DEFAULT_GUEST_WORKDIR,
    ): List<String> {
        val argv = buildProotArgvPrefix(workingDirInGuest).toMutableList()
        argv += "/bin/sh"
        if (shellCommand != null) {
            argv += listOf("-c", shellCommand)
        } else {
            argv += "-l"
        }
        return argv
    }

    /**
     * Builds the exact `proot` argv list for a guest argv run directly (no
     * `/bin/sh -c` wrapper): `proot ... -w <workdir> <guest argv>`.
     * Used by [startProcess] for long-lived stdio servers (e.g. a local MCP
     * server binary + args). Pure and JVM-testable.
     */
    fun buildProotCommandForArgv(
        guestArgv: List<String>,
        workingDirInGuest: String = DEFAULT_GUEST_WORKDIR,
    ): List<String> {
        require(guestArgv.isNotEmpty()) { "guest argv must not be empty" }
        return buildProotArgvPrefix(workingDirInGuest) + guestArgv
    }

    /** Shared `proot` argv prefix: binary + `--kill-on-exit -0 -r <rootfs>` + binds + `-w <workdir>`. */
    private fun buildProotArgvPrefix(workingDirInGuest: String): List<String> {
        val argv = mutableListOf(
            prootBinary().absolutePath,
            "--kill-on-exit",
            "-0",
            "-r",
            rootfsDir.absolutePath,
            "-b",
            "/dev",
            "-b",
            "/proc",
            "-b",
            "/sys",
        )
        val tmpHost = File(rootfsDir, "tmp").apply { mkdirs() }
        argv += listOf("-b", "${tmpHost.absolutePath}:/tmp")

        if (workspaceBindDir != null) {
            argv += listOf("-b", "${workspaceBindDir.canonicalPath}:/workspace")
        }
        for ((hostPath, guestPath) in extraBinds) {
            if (hostPath.isNotBlank() && guestPath.isNotBlank()) {
                argv += listOf("-b", "$hostPath:$guestPath")
            }
        }
        // The guest working directory must exist inside the rootfs:
        // /workspace only exists when a workspace is bound. Without a
        // bind (e.g. ensureToolchain), the default -w /workspace points
        // at a directory that does not exist in the Alpine minirootfs,
        // so proot fails to start. Fall back to /root, which always exists.
        val effectiveWorkdir =
            if (workspaceBindDir == null && workingDirInGuest == DEFAULT_GUEST_WORKDIR) "/root"
            else workingDirInGuest
        argv += listOf("-w", effectiveWorkdir)
        return argv
    }

    /** Environment variables passed to the PRoot child process (includes loader + tmpdir). */
    fun buildProotEnvironment(extraEnv: Map<String, String> = emptyMap()): Map<String, String> {
        val env = LinkedHashMap<String, String>()
        env["HOME"] = "/root"
        env["TERM"] = "xterm-256color"
        env["LANG"] = "C.UTF-8"
        env["PATH"] = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
        env["PROOT_TMP_DIR"] = File(rootfsDir, "tmp").apply { mkdirs() }.absolutePath
        env["PROOT_LOADER"] = prootLoaderBinary().absolutePath
        env["PROOT_NO_SECCOMP"] = "1"
        // libproot.so dynamically links libtalloc.so.2 + libandroid-shmem.so,
        // which ship in the same nativeLibraryDir. The child linker only
        // finds them if that directory heads LD_LIBRARY_PATH (the system
        // paths stay after it, so system libs still resolve). The
        // native-stage dir follows: on dev builds without packaged jniLibs,
        // RootfsManager stages verified copies there.
        val stageDir = rootfsDir.parentFile?.let { File(it, "native-stage") }
        val ldPaths = listOfNotNull(nativeLibDir.absolutePath, stageDir?.absolutePath) +
            System.getenv("LD_LIBRARY_PATH").orEmpty().split(":").filter { it.isNotBlank() }
        env["LD_LIBRARY_PATH"] = ldPaths.distinct().joinToString(":")
        env.putAll(extraEnv)
        return env
    }

    /** True when the Alpine rootfs has been extracted (bin/sh or bin/busybox present). */
    fun hasRootfs(): Boolean {
        val root = rootfsDir
        return root.isDirectory &&
            (File(root, "bin/sh").isFile || File(root, "bin/busybox").isFile)
    }

    /**
     * Runs [command] inside the PRoot rootfs in an isolated child process with
     * timeout enforcement and full process-group (PGID) teardown on timeout.
     */
    open suspend fun shellExec(
        command: String,
        timeoutMs: Long = 60_000L,
    ): ShellResult = withContext(Dispatchers.IO) {
        shellExecWithEnv(command = command, extraEnv = emptyMap(), timeoutMs = timeoutMs)
    }

    /** Overload allowing callers (e.g., GitWorktreeEngine, HookEngine) to pass ephemeral env vars. */
    open suspend fun shellExecWithEnv(
        command: String,
        extraEnv: Map<String, String>,
        timeoutMs: Long = 60_000L,
        workingDirInGuest: String = DEFAULT_GUEST_WORKDIR,
    ): ShellResult = withContext(Dispatchers.IO) {
        val proot = prootBinary()
        if (!proot.isFile) {
            return@withContext ShellResult(
                exitCode = 127,
                stdout = "",
                stderr = "PRoot binary not found at ${proot.absolutePath}. Ensure jniLibs packaging is enabled.",
            )
        }
        if (!hasRootfs()) {
            return@withContext ShellResult(
                exitCode = 127,
                stdout = "",
                stderr = "Linux rootfs is not installed at ${rootfsDir.absolutePath}. " +
                    "Open the Terminal tab and start the shell first (it downloads and verifies the Alpine rootfs).",
            )
        }
        val argv = buildProotCommand(shellCommand = command, workingDirInGuest = workingDirInGuest)
        val pb = ProcessBuilder(argv)
            .directory(workspaceBindDir ?: rootfsDir)
        pb.environment().putAll(buildProotEnvironment(extraEnv))

        val execId = "exec_${UUID.randomUUID().toString().take(8)}"
        val process = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext ShellResult(
                exitCode = 126,
                stdout = "",
                stderr = "Failed to spawn PRoot process: ${e.message}",
            )
        }
        activeProcesses[execId] = process
        val pid = extractProcessPid(process)

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        // Drain to EOF, not to the cap: stopping at MAX_OUTPUT_CHARS lets
        // the child block forever on a full pipe (it keeps writing while
        // nobody reads). Only the first MAX_OUTPUT_CHARS are kept.
        val outThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { r ->
                    val chunk = CharArray(4096)
                    var n: Int
                    while (r.read(chunk).also { n = it } != -1) {
                        if (stdoutBuf.length < MAX_OUTPUT_CHARS) {
                            stdoutBuf.append(chunk, 0, minOf(n, MAX_OUTPUT_CHARS - stdoutBuf.length))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().use { r ->
                    val chunk = CharArray(4096)
                    var n: Int
                    while (r.read(chunk).also { n = it } != -1) {
                        if (stderrBuf.length < MAX_OUTPUT_CHARS) {
                            stderrBuf.append(chunk, 0, minOf(n, MAX_OUTPUT_CHARS - stderrBuf.length))
                        }
                    }
                }
            }
        }.apply { isDaemon = true; start() }

        try {
            // runInterruptible: a coroutine cancellation interrupts the
            // blocking waitFor instead of orphaning a live proot process.
            val finished = try {
                runInterruptible {
                    process.waitFor(timeoutMs.coerceAtLeast(100L), TimeUnit.MILLISECONDS)
                }
            } catch (e: CancellationException) {
                killProcessGroup(pid, process)
                outThread.join(500)
                errThread.join(500)
                throw e
            }
            if (!finished) {
                killProcessGroup(pid, process)
                outThread.join(500)
                errThread.join(500)
                return@withContext ShellResult(
                    exitCode = 124,
                    stdout = stdoutBuf.toString(),
                    stderr = (stderrBuf.toString() + "\nCommand timed out after ${timeoutMs}ms").trim(),
                    timedOut = true,
                )
            }
            outThread.join(1_000)
            errThread.join(1_000)
            ShellResult(
                exitCode = process.exitValue(),
                stdout = stdoutBuf.toString(),
                stderr = stderrBuf.toString(),
                timedOut = false,
            )
        } finally {
            activeProcesses.remove(execId)
        }
    }

    /**
     * Starts a long-lived process inside the PRoot rootfs with piped
     * stdin/stdout, for programmatic JSON-RPC (e.g. a local stdio MCP server).
     *
     * [command] is the GUEST argv (binary + args, e.g.
     * `listOf("/usr/bin/node", "/workspace/server.js")`); it is run directly
     * under proot, NOT wrapped in `/bin/sh -c`. The guest working directory
     * is always [DEFAULT_GUEST_WORKDIR] (`/workspace` bind); [workingDir] is
     * the HOST working directory for the spawned process (default [rootfsDir]).
     * [env] is merged over the standard proot environment
     * ([buildProotEnvironment]).
     *
     * stderr is drained on a background thread (see [StdioProcess]) so a
     * chatty server cannot block on a full stderr pipe. The process is
     * registered for cleanup; call [StdioProcess.kill] when done.
     *
     * Throws [IllegalStateException] immediately when `libproot.so` is not
     * packaged (fail fast on connect — a stdio MCP server cannot run without
     * the runtime). Use [startRawProcess] in JVM tests to spawn a plain
     * host command without proot.
     */
    open fun startProcess(
        command: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: File = rootfsDir,
    ): StdioProcess {
        require(command.isNotEmpty()) { "startProcess requires a non-empty guest command" }
        val proot = prootBinary()
        if (!proot.isFile) {
            throw IllegalStateException(
                "PRoot binary not found at ${proot.absolutePath}. Ensure jniLibs packaging is enabled.",
            )
        }
        val argv = buildProotCommandForArgv(command)
        return startRawProcess(argv, buildProotEnvironment(env), workingDir)
    }

    /**
     * TEST SEAM + low-level spawn: starts [hostCommand] directly on the host
     * with piped stdin/stdout and no proot wrapping. JVM tests use this to
     * exercise [StdioProcess] against a trivial local process (e.g. `cat`)
     * without a rootfs or Termux. Production callers use [startProcess].
     * `redirectErrorStream` is false: stderr gets its own drain thread.
     */
    internal fun startRawProcess(
        hostCommand: List<String>,
        env: Map<String, String> = emptyMap(),
        workingDir: File = rootfsDir,
    ): StdioProcess {
        require(hostCommand.isNotEmpty()) { "startRawProcess requires a non-empty command" }
        val pb = ProcessBuilder(hostCommand).directory(workingDir)
        pb.environment().putAll(env)
        pb.redirectErrorStream(false)
        val process = pb.start()
        val id = "stdio_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        activeProcesses[id] = process
        return StdioProcess(id, hostCommand, process, this)
    }

    /** Removes [process] from the tracked-processes map (called on kill / natural exit). */
    internal fun untrackProcess(process: Process) {
        activeProcesses.entries.removeIf { it.value === process }
    }

    /**
     * Starts an interactive PTY session inside the PRoot rootfs.
     * Pass [sessionClient] on Android to create a live Apache-2.0 Termux [TerminalSession];
     * when null (e.g., JVM tests or headless callers), tracks the session descriptor directly.
     *
     * NOTE: the `TerminalSession(...)` constructor call and the `.pid` /
     * `finishIfRunning()` accesses below could NOT be verified against the
     * published `terminal-emulator` AAR from this VM — see NOTES.md §5 for the
     * exact unverified signatures. The staged call is kept as-is; the
     * integrator must confirm it against the resolved AAR at first Gradle sync.
     */
    fun startPtySession(
        cols: Int,
        rows: Int,
    ): PtySession = startPtySession(cols = cols, rows = rows, sessionClient = null)

    fun startPtySession(
        cols: Int,
        rows: Int,
        sessionClient: TerminalSessionClient?,
        title: String = "Shell ${sessions.size + 1}",
    ): PtySession {
        val safeCols = cols.coerceIn(20, 400)
        val safeRows = rows.coerceIn(5, 200)
        val id = "pty_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        val argv = buildProotCommand(shellCommand = null)
        val envPairs = buildProotEnvironment().entries.map { (k, v) -> "$k=$v" }.toTypedArray()
        val workDir = workspaceBindDir ?: rootfsDir

        val termuxSession: TerminalSession? = if (sessionClient != null && prootBinary().isFile) {
            runCatching {
                // Full argv: JNI_createSubprocess builds the child argv
                // ONLY from this array and execvp()s it — nothing prepends
                // the program name. Passing argv.drop(1) swallowed
                // --kill-on-exit (proot skips argv[0] as the program name)
                // and hid libproot.so from /proc cmdline.
                TerminalSession(
                    argv.first(),
                    workDir.absolutePath,
                    argv.toTypedArray(),
                    envPairs,
                    DEFAULT_TRANSCRIPT_ROWS,
                    sessionClient,
                )
            }.getOrNull()
        } else {
            null
        }

        val session = PtySession(
            id = id,
            title = title,
            cols = safeCols,
            rows = safeRows,
            command = argv,
            workingDir = workDir,
            pid = termuxSession?.pid?.toLong() ?: -1L,
            isAlive = true,
            terminalSession = termuxSession,
        )
        sessions[id] = session
        return session
    }

    /** Lists all tracked PTY sessions in creation order. */
    fun listSessions(): List<PtySession> =
        sessions.values.sortedBy { it.createdAtMillis }

    /**
     * Terminates a PTY session and cleans up its entire process group (PGID) so
     * child shells/compilers spawned inside PRoot cannot linger as phantom processes.
     */
    fun killSession(id: String) {
        val session = sessions.remove(id) ?: return
        session.isAlive = false
        val pid = session.terminalSession?.pid?.toLong()?.takeIf { it > 0L } ?: session.pid
        if (pid > 0L) {
            killProcessGroup(pid, process = null)
        }
        runCatching { session.terminalSession?.finishIfRunning() }
    }

    /**
     * Kills the entire process group (`-pgid`) with SIGTERM followed by SIGKILL,
     * falling back to Java `Process.destroyForcibly()`. Purely verifiable via [signalSender].
     */
    internal fun killProcessGroup(pid: Long, process: Process?): Boolean {
        var signaled = false
        if (pid > 0L) {
            val pgidTarget = -pid
            signaled = sendPosixSignal(pgidTarget, SIGTERM) || sendPosixSignal(pid, SIGTERM)
            sendPosixSignal(pgidTarget, SIGKILL)
            sendPosixSignal(pid, SIGKILL)
        }
        runCatching { process?.destroy() }
        runCatching { process?.destroyForcibly() }
        return signaled
    }

    private fun sendPosixSignal(pidOrNegativePgid: Long, signal: Int): Boolean {
        signalSender?.let { return it(pidOrNegativePgid, signal) }
        return runCatching {
            val osClass = Class.forName("android.system.Os")
            val killMethod = osClass.getMethod("kill", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            killMethod.invoke(null, pidOrNegativePgid.toInt(), signal)
            true
        }.getOrElse {
            runCatching {
                val p = ProcessBuilder("/system/bin/kill", "-$signal", pidOrNegativePgid.toString())
                    .redirectErrorStream(true)
                    .start()
                p.waitFor(500, TimeUnit.MILLISECONDS)
                p.exitValue() == 0
            }.getOrDefault(false)
        }
    }

    /**
     * Scans `/proc` for stray `proot` / `busybox` / rootfs shell processes from previous
     * sessions (Android 12+ Phantom Process Killer diagnostic).
     */
    fun diagnosePhantomProcesses(): String = diagnosePhantomProcessesFromProc(File("/proc"))

    internal fun diagnosePhantomProcessesFromProc(procDir: File): String {
        val activePids = buildSet {
            sessions.values.forEach {
                // Read the LIVE pid: PtySession.pid is captured at
                // construction (often -1 — the subprocess may not have a
                // pid yet), while TerminalSession.pid tracks the running
                // process. killSession already reads it this way.
                val livePid = it.terminalSession?.pid?.toLong()?.takeIf { pid -> pid > 0L } ?: it.pid
                if (livePid > 0L) add(livePid)
            }
            activeProcesses.values.forEach {
                val p = extractProcessPid(it)
                if (p > 0L) add(p)
            }
        }
        val entries = scanProcEntries(procDir)
        val tracked = entries.filter { it.pid in activePids }
        val stray = entries.filter { it.pid !in activePids }

        return buildString {
            appendLine("Phantom-Process Diagnostic (Android 12+ 32-process limit check)")
            appendLine("Tracked active sessions: ${sessions.size} (active PIDs: ${tracked.size})")
            appendLine("Stray proot/shell processes found: ${stray.size}")
            if (stray.isEmpty()) {
                appendLine("Status: OK — no stray processes detected.")
            } else {
                appendLine("Status: WARNING — stray processes detected:")
                for (proc in stray) {
                    appendLine("  PID=${proc.pid} CMD=${proc.cmdline}")
                }
                appendLine("Tip: Use killSession() or restart CodexAgentService to reclaim slots.")
            }
        }.trimEnd()
    }

    internal data class ProcEntry(val pid: Long, val cmdline: String)

    internal fun scanProcEntries(procDir: File): List<ProcEntry> {
        if (!procDir.isDirectory) return emptyList()
        val results = mutableListOf<ProcEntry>()
        val children = procDir.listFiles() ?: return emptyList()
        for (dir in children) {
            val pid = dir.name.toLongOrNull() ?: continue
            val cmdFile = File(dir, "cmdline")
            if (!cmdFile.isFile) continue
            val cmdline = runCatching {
                cmdFile.readBytes().toString(Charsets.UTF_8).replace('\u0000', ' ').trim()
            }.getOrDefault("")
            if (cmdline.isBlank()) continue
            val lower = cmdline.lowercase()
            if (lower.contains("libproot") || lower.contains("proot") || lower.contains("libbusybox")) {
                results += ProcEntry(pid, cmdline)
            }
        }
        return results.sortedBy { it.pid }
    }

    internal fun extractProcessPid(process: Process): Long {
        // Java 9+ Process.pid() via reflection so minSdk 26 compiles cleanly.
        runCatching {
            val m = Process::class.java.getMethod("pid")
            val result = m.invoke(process) as? Long
            if (result != null && result > 0L) return result
        }
        // Fallback for Android UNIXProcess private `pid` field.
        runCatching {
            val f = process.javaClass.getDeclaredField("pid")
            f.isAccessible = true
            val result = (f.get(process) as? Number)?.toLong()
            if (result != null && result > 0L) return result
        }
        return -1L
    }

    companion object {
        const val DEFAULT_GUEST_WORKDIR = "/workspace"
        const val DEFAULT_TRANSCRIPT_ROWS = 2000
        const val MAX_OUTPUT_CHARS = 128_000
        /** Chars of stderr kept per [StdioProcess] ([StdioProcess.stderrTail]). */
        const val STDIO_STDERR_TAIL_CHARS = 4096
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
