package dev.codex.android.linux

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.XZOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream

/**
 * JVM unit tests for [ProotShellEngine], [RootfsManager]/[RootfsVerifier], and
 * [DebExtractor]: command building, PGID cleanup logic, SHA-256 verification
 * with fake downloads, tar path-escape safety, `.deb` (ar + tar.xz) extraction
 * with per-binary hash verification, and phantom-process diagnostics.
 *
 * No test downloads anything: the `.deb` fixtures are built here from bytes.
 */
class ProotShellEngineTest {

    private lateinit var tempDir: File
    private lateinit var rootfsDir: File
    private lateinit var nativeLibDir: File
    private lateinit var workspaceDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("codex-proot-test").toFile()
        rootfsDir = File(tempDir, "linux/rootfs").apply { mkdirs() }
        nativeLibDir = File(tempDir, "nativeLibs").apply { mkdirs() }
        workspaceDir = File(tempDir, "workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun buildProotCommand_includesRootfsBindsWorkdirAndShellCommand() {
        val engine = ProotShellEngine(
            rootfsDir = rootfsDir,
            nativeLibDir = nativeLibDir,
            workspaceBindDir = workspaceDir,
            extraBinds = listOf("/host/env" to "/etc/codex/daemon.env"),
        )
        val cmd = engine.buildProotCommand("echo hello")

        assertEquals(File(nativeLibDir, "libproot.so").absolutePath, cmd.first())
        assertTrue(cmd.contains("--kill-on-exit"))
        assertTrue(cmd.contains("-r"))
        assertTrue(cmd.contains(rootfsDir.absolutePath))
        assertTrue(cmd.contains("${workspaceDir.canonicalPath}:/workspace"))
        assertTrue(cmd.contains("/host/env:/etc/codex/daemon.env"))
        assertEquals(listOf("/bin/sh", "-c", "echo hello"), cmd.takeLast(3))

        val env = engine.buildProotEnvironment()
        assertEquals(File(nativeLibDir, "libproot-loader.so").absolutePath, env["PROOT_LOADER"])
        assertEquals("1", env["PROOT_NO_SECCOMP"])
    }

    @Test
    fun killSession_signalsNegativePgidThenRemovesSession() {
        val sentSignals = mutableListOf<Pair<Long, Int>>()
        val engine = ProotShellEngine(
            rootfsDir = rootfsDir,
            nativeLibDir = nativeLibDir,
            signalSender = { target, sig ->
                sentSignals += target to sig
                true
            },
        )
        val session = engine.startPtySession(cols = 80, rows = 24)
        session.pid = 4242L
        assertEquals(1, engine.listSessions().size)

        engine.killSession(session.id)

        assertTrue(engine.listSessions().isEmpty())
        assertFalse(session.isAlive)
        assertTrue("Expected SIGTERM to PGID -4242", (-4242L to ProotShellEngine.SIGTERM) in sentSignals)
        assertTrue("Expected SIGKILL to PGID -4242", (-4242L to ProotShellEngine.SIGKILL) in sentSignals)
    }

    @Test
    fun sha256Verification_acceptsValidFakeDownloadAndRejectsTamperedDownload() = runBlocking {
        val tarGzBytes = buildMinimalTarGz("bin/sh" to "#!/bin/sh\necho ok\n")
        val validHash = RootfsVerifier.sha256Hex(tarGzBytes)

        fun fakeHttpClient(payload: ByteArray): OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            })
            .build()

        // 1. Valid pinned hash -> downloads, verifies, extracts, marks installed.
        val goodFilesDir = File(tempDir, "good-app").apply { mkdirs() }
        val goodManager = RootfsManager(
            filesDir = goodFilesDir,
            nativeLibDir = nativeLibDir,
            httpClient = fakeHttpClient(tarGzBytes),
            artifacts = listOf(
                PinnedArtifact(
                    id = RootfsManager.ARTIFACT_ALPINE_ROOTFS,
                    url = "https://example.invalid/alpine.tar.gz",
                    expectedSha256 = validHash,
                    license = "MIT",
                    destinationRelativePath = "rootfs/",
                ),
            ),
        )
        val installedRoot = goodManager.ensureRootfs()
        assertTrue(goodManager.isInstalled())
        assertTrue(File(installedRoot, "bin/sh").isFile)

        // 2. Tampered payload -> throws SecurityException and refuses installation.
        val badFilesDir = File(tempDir, "bad-app").apply { mkdirs() }
        val badManager = RootfsManager(
            filesDir = badFilesDir,
            nativeLibDir = nativeLibDir,
            httpClient = fakeHttpClient("tampered-bytes".toByteArray()),
            artifacts = listOf(
                PinnedArtifact(
                    id = RootfsManager.ARTIFACT_ALPINE_ROOTFS,
                    url = "https://example.invalid/alpine.tar.gz",
                    expectedSha256 = validHash,
                    license = "MIT",
                    destinationRelativePath = "rootfs/",
                ),
            ),
        )
        try {
            badManager.ensureRootfs()
            fail("Expected SecurityException for mismatched SHA-256")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("SHA-256 verification failed"))
        }
        assertFalse(badManager.isInstalled())

        // 3. Fail-closed on a non-hash string: there is no placeholder constant
        // in main source anymore (the no-placeholders CI guard scans app/src/main
        // only, so the literal is allowed here in the test). "PLACEHOLDER_HASH_TO_PIN"
        // is 22 chars with non-hex letters, so the malformed-hash check rejects it.
        val placeholderFile = File(tempDir, "p.bin").apply { writeText("abc") }
        assertFalse(RootfsVerifier.verifySha256(placeholderFile, "PLACEHOLDER_HASH_TO_PIN"))
        assertFalse(RootfsVerifier.verifySha256(placeholderFile, ""))
        assertFalse(RootfsVerifier.verifySha256(placeholderFile, "zz".repeat(32)))
    }

    @Test
    fun ensureRootfs_repairsShellAndDiscardsPartialInstallWithoutFollowingSymlinks() = runBlocking {
        val payload = buildMinimalTarGz("bin/busybox" to "BUSYBOX")
        val manager = rootfsManagerFor(payload)
        val root = manager.rootfsDir()
        val staleBin = File(root, "bin").apply { mkdirs() }
        val staleFile = File(staleBin, "busybox").apply { writeText("stale busybox") }
        Files.createSymbolicLink(File(staleBin, "sh").toPath(), Paths.get("missing"))
        val outside = File(tempDir, "outside").apply { mkdir() }
        val outsideFile = File(outside, "keep").apply { writeText("keep me") }
        Files.createSymbolicLink(File(root, "outside-link").toPath(), outside.toPath())
        val marker = File(root, ".codex_rootfs_ready").apply { writeText("stale marker") }
        val engine = ProotShellEngine(root, nativeLibDir)
        assertFalse(manager.isInstalled())
        assertFalse(engine.hasRootfs())

        manager.ensureRootfs()

        assertTrue(manager.isInstalled())
        assertTrue(engine.hasRootfs())
        val sh = File(root, "bin/sh").toPath()
        assertEquals(Paths.get("busybox"), Files.readSymbolicLink(sh))
        assertEquals("BUSYBOX", staleFile.readText())
        assertFalse(Files.isSymbolicLink(File(root, "outside-link").toPath()))
        assertEquals("keep me", outsideFile.readText())
        assertTrue(File(root, "workspace").isDirectory)
        assertTrue(File(root, "etc/codex").isDirectory)
        marker.delete()
        assertFalse(engine.hasRootfs())
    }

    @Test
    fun ensureRootfs_doesNotMarkAnArchiveWithoutShellOrBusyboxReady() = runBlocking {
        val manager = rootfsManagerFor(buildMinimalTarGz("etc/os-release" to "Alpine"))
        try {
            manager.ensureRootfs()
            fail("Expected IOException for a rootfs without /bin/sh")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("incomplete"))
        }
        assertFalse(manager.isInstalled())
        assertFalse(File(manager.rootfsDir(), ".codex_rootfs_ready").exists())
    }

    @Test
    fun ensureRootfs_usesMirrorAfterHttpFailureAndStillRequiresPinnedHash() = runBlocking {
        val payload = buildMinimalTarGz("bin/busybox" to "BUSYBOX")
        val requested = mutableListOf<String>()
        val manager = rootfsManagerFor(payload, requestedHosts = requested)
        manager.ensureRootfs()
        manager.ensureRootfs()
        assertTrue(manager.isInstalled())
        assertEquals(listOf("primary.invalid", "mirror.invalid"), requested)

        requested.clear()
        val tampered = rootfsManagerFor("tampered".toByteArray(), RootfsVerifier.sha256Hex(payload), requested)
        try {
            tampered.ensureRootfs()
            fail("Expected a hash mismatch for the mirror payload")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("SHA-256 verification failed"))
        }
        assertEquals(listOf("primary.invalid", "mirror.invalid"), requested)
        assertFalse(tampered.rootfsDir().exists())
    }

    @Test
    fun extractTarStream_preservesGnuLongNameAndLongLinkForTheSameSymlink() {
        val target = "target-" + "a".repeat(110)
        val name = "link-" + "b".repeat(110)
        val tar = buildTar(
            TarEntry(target.take(100), "$target\u0000".toByteArray(), 'L'),
            TarEntry(target.take(100), "contents".toByteArray()),
            TarEntry("././@LongLink", "$target\u0000".toByteArray(), 'K'),
            TarEntry("././@LongLink", "$name\u0000".toByteArray(), 'L'),
            TarEntry(name.take(100), type = '2', link = target.take(100)),
            TarEntry("short-link", type = '2', link = "missing"),
        )
        assertEquals(3, RootfsVerifier.extractTarStreamSafely(ByteArrayInputStream(tar), rootfsDir))
        assertEquals(Paths.get(target), Files.readSymbolicLink(File(rootfsDir, name).toPath()))
        assertEquals("contents", File(rootfsDir, name).readText())
        assertEquals(Paths.get("missing"), Files.readSymbolicLink(File(rootfsDir, "short-link").toPath()))
    }

    private fun rootfsManagerFor(
        payload: ByteArray,
        hash: String = RootfsVerifier.sha256Hex(payload),
        requestedHosts: MutableList<String>? = null,
    ): RootfsManager {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val host = chain.request().url.host
                requestedHosts?.add(host)
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(if (host == "primary.invalid") 503 else 200)
                    .message("Fixture")
                    .body(payload.toResponseBody("application/octet-stream".toMediaType()))
                    .build()
            })
            .build()
        return RootfsManager(
            filesDir = Files.createTempDirectory(tempDir.toPath(), "app-").toFile(),
            nativeLibDir = nativeLibDir,
            httpClient = client,
            artifacts = listOf(
                PinnedArtifact(
                    id = RootfsManager.ARTIFACT_ALPINE_ROOTFS,
                    url = "https://primary.invalid/alpine.tar.gz",
                    expectedSha256 = hash,
                    license = "MIT",
                    destinationRelativePath = "rootfs/",
                    fallbackUrls = listOf("https://mirror.invalid/alpine.tar.gz"),
                ),
            ),
        )
    }

    @Test
    fun resolveSafeTarEntry_rejectsPathTraversal() {
        try {
            RootfsVerifier.resolveSafeTarEntry(rootfsDir, "../../etc/evil")
            fail("Expected SecurityException for tar traversal")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("escapes rootfs"))
        }
        val ok = RootfsVerifier.resolveSafeTarEntry(rootfsDir, "./usr/bin/env")
        assertTrue(ok.canonicalPath.startsWith(rootfsDir.canonicalPath))
    }

    @Test
    fun debExtraction_extractsAndVerifiesProotBinariesFromFixtureDeb() {
        val fakeProot = "FAKE-PROOOT-BINARY-v1".toByteArray(Charsets.UTF_8)
        val fakeLoader = "FAKE-LOADER-BINARY-v1".toByteArray(Charsets.UTF_8)
        // Tar entry names carry the leading "./" exactly as dpkg-deb writes them.
        val debBytes = buildMinimalDeb(
            "./data/data/com.termux/files/usr/bin/proot" to fakeProot,
            "./data/data/com.termux/files/usr/libexec/proot/loader" to fakeLoader,
        )
        val debFile = File(tempDir, "proot-test.deb").apply { writeBytes(debBytes) }
        val stageDir = File(tempDir, "stage").apply { mkdirs() }
        val artifact = PinnedArtifact(
            id = RootfsManager.ARTIFACT_PROOT_DEB,
            url = "https://example.invalid/proot.deb",
            expectedSha256 = RootfsVerifier.sha256Hex(debBytes),
            license = "GPL-2.0",
            destinationRelativePath = "jniLibs/arm64-v8a/",
            extractedBinaries = listOf(
                ExtractedBinary(
                    tarPath = "data/data/com.termux/files/usr/bin/proot",
                    stagedFileName = "libproot.so",
                    expectedSha256 = RootfsVerifier.sha256Hex(fakeProot),
                ),
                ExtractedBinary(
                    tarPath = "data/data/com.termux/files/usr/libexec/proot/loader",
                    stagedFileName = "libproot-loader.so",
                    expectedSha256 = RootfsVerifier.sha256Hex(fakeLoader),
                ),
            ),
        )

        val staged = DebExtractor.extractAndStageBinaries(debFile, stageDir, artifact)

        assertEquals(2, staged.size)
        val stagedProot = File(stageDir, "libproot.so")
        assertTrue(stagedProot.isFile)
        assertEquals("FAKE-PROOOT-BINARY-v1", stagedProot.readText(Charsets.UTF_8))
        assertTrue(stagedProot.canExecute())
        val stagedLoader = File(stageDir, "libproot-loader.so")
        assertTrue(stagedLoader.isFile)
        assertEquals("FAKE-LOADER-BINARY-v1", stagedLoader.readText(Charsets.UTF_8))
        assertTrue(stagedLoader.canExecute())
    }

    @Test
    fun debExtraction_rejectsTamperedExtractedBinary() {
        val fakeProot = "FAKE-PROOOT-BINARY-v1".toByteArray(Charsets.UTF_8)
        val fakeLoader = "FAKE-LOADER-BINARY-v1".toByteArray(Charsets.UTF_8)
        val debBytes = buildMinimalDeb(
            "./data/data/com.termux/files/usr/bin/proot" to fakeProot,
            "./data/data/com.termux/files/usr/libexec/proot/loader" to fakeLoader,
        )
        val debFile = File(tempDir, "proot-test.deb").apply { writeBytes(debBytes) }
        val stageDir = File(tempDir, "stage").apply { mkdirs() }
        // Loader pinned hash is for different bytes: extraction must fail closed.
        val artifact = PinnedArtifact(
            id = RootfsManager.ARTIFACT_PROOT_DEB,
            url = "https://example.invalid/proot.deb",
            expectedSha256 = RootfsVerifier.sha256Hex(debBytes),
            license = "GPL-2.0",
            destinationRelativePath = "jniLibs/arm64-v8a/",
            extractedBinaries = listOf(
                ExtractedBinary(
                    tarPath = "data/data/com.termux/files/usr/bin/proot",
                    stagedFileName = "libproot.so",
                    expectedSha256 = RootfsVerifier.sha256Hex(fakeProot),
                ),
                ExtractedBinary(
                    tarPath = "data/data/com.termux/files/usr/libexec/proot/loader",
                    stagedFileName = "libproot-loader.so",
                    expectedSha256 = RootfsVerifier.sha256Hex("something-else".toByteArray()),
                ),
            ),
        )

        try {
            DebExtractor.extractAndStageBinaries(debFile, stageDir, artifact)
            fail("Expected SecurityException for tampered extracted binary")
        } catch (e: SecurityException) {
            assertTrue(e.message!!.contains("SHA-256 verification failed"))
        }
        assertFalse(File(stageDir, "libproot-loader.so").exists())
    }

    @Test
    fun diagnosePhantomProcesses_flagsStrayProotProcesses() {
        val fakeProc = File(tempDir, "fake-proc").apply { mkdirs() }
        File(fakeProc, "1001").apply { mkdirs(); File(this, "cmdline").writeBytes("libproot.so\u0000-r\u0000/rootfs".toByteArray()) }
        File(fakeProc, "1002").apply { mkdirs(); File(this, "cmdline").writeBytes("libproot.so\u0000-r\u0000/stray".toByteArray()) }
        File(fakeProc, "1003").apply { mkdirs(); File(this, "cmdline").writeBytes("/system/bin/app_process64".toByteArray()) }

        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        val tracked = engine.startPtySession(80, 24)
        tracked.pid = 1001L

        val report = engine.diagnosePhantomProcessesFromProc(fakeProc)
        assertTrue(report.contains("Stray proot/shell processes found: 1"))
        assertTrue(report.contains("PID=1002"))
        assertFalse(report.contains("PID=1001"))
        assertFalse(report.contains("PID=1003"))
    }

    @Test
    fun stdioProcess_writeLineReadLineRoundTripViaCat() = runBlocking {
        // Test seam: plain host `cat` (no proot, no rootfs) echoes stdin to stdout.
        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        val proc = engine.startRawProcess(listOf("cat"))
        try {
            assertTrue(proc.isAlive)
            proc.writeLine("hello stdio")
            assertEquals("hello stdio", proc.readLine(5_000L))
            proc.writeLine("second line")
            assertEquals("second line", proc.readLine(5_000L))
        } finally {
            proc.kill()
        }
        assertFalse(proc.isAlive)
        // EOF after kill: readLine returns null without waiting out the timeout.
        assertEquals(null, proc.readLine(1_000L))
    }

    @Test
    fun stdioProcess_readLineReturnsNullOnTimeout() = runBlocking {
        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        val proc = engine.startRawProcess(listOf("cat"))
        try {
            assertTrue(proc.isAlive)
            // Nothing written: no line arrives within 200ms.
            assertEquals(null, proc.readLine(200L))
            assertTrue(proc.isAlive)
        } finally {
            proc.kill()
        }
    }

    @Test
    fun stdioProcess_killTerminatesChattyStderrProcess() = runBlocking {
        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        // Endless stderr spam: without the drain thread the process would block
        // on a full stderr pipe; with it, the process stays alive and killable.
        val proc = engine.startRawProcess(
            listOf("sh", "-c", "while true; do echo spam >&2; done"),
        )
        try {
            Thread.sleep(300L)
            assertTrue(proc.isAlive)
            assertTrue(proc.stderrTail.contains("spam"))
        } finally {
            proc.kill()
        }
        val deadline = System.currentTimeMillis() + 5_000L
        while (proc.exitCode == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L)
        }
        assertFalse(proc.isAlive)
        assertTrue(proc.exitCode != null)
    }

    @Test
    fun buildProotCommandForArgv_runsGuestArgvDirectly() {
        val engine = ProotShellEngine(
            rootfsDir = rootfsDir,
            nativeLibDir = nativeLibDir,
            workspaceBindDir = workspaceDir,
        )
        val cmd = engine.buildProotCommandForArgv(listOf("/usr/bin/node", "server.js"))

        assertEquals(File(nativeLibDir, "libproot.so").absolutePath, cmd.first())
        assertTrue(cmd.contains("-r"))
        assertTrue(cmd.contains(rootfsDir.absolutePath))
        assertEquals(listOf("/usr/bin/node", "server.js"), cmd.takeLast(2))
        // No /bin/sh -c wrapper: the guest argv runs directly under proot.
        assertFalse(cmd.contains("/bin/sh"))
    }

    @Test
    fun stdioProcess_startProcessFailsFastWithoutProotBinary() {
        // nativeLibDir is empty in this test: no libproot.so packaged.
        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        try {
            engine.startProcess(listOf("/usr/bin/node", "server.js"))
            fail("Expected IllegalStateException when libproot.so is missing")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("PRoot binary not found"))
        }
    }

    @Ignore("Requires Android ARM64/x86_64 emulator with packaged libproot.so — Nyx runs this on the emulator")
    @Test
    fun realProotExecution_runsInsideAlpineRootfs() = runBlocking {
        val engine = ProotShellEngine(rootfsDir, nativeLibDir)
        val res = engine.shellExec("uname -a")
        assertEquals(0, res.exitCode)
    }

    /** Builds a minimal Debian package (ar archive) from raw bytes, no downloads. */
    private fun buildMinimalDeb(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("!<arch>\n".toByteArray(Charsets.US_ASCII))
        // debian-binary member (dpkg format version marker).
        writeArMember(out, "debian-binary", "2.0\n".toByteArray(Charsets.US_ASCII))
        // data.tar.xz member: XZ-compressed tar of the given entries.
        val tarBytes = buildMinimalTar(*entries)
        val xzOut = ByteArrayOutputStream()
        XZOutputStream(xzOut, LZMA2Options()).use { it.write(tarBytes) }
        writeArMember(out, "data.tar.xz", xzOut.toByteArray())
        return out.toByteArray()
    }

    /** Writes one GNU ar member (name terminated with `/`, odd sizes padded). */
    private fun writeArMember(out: ByteArrayOutputStream, name: String, data: ByteArray) {
        val header = ByteArray(60) { ' '.code.toByte() }
        fun field(offset: Int, length: Int, text: String) {
            val bytes = text.toByteArray(Charsets.US_ASCII)
            System.arraycopy(bytes, 0, header, offset, minOf(bytes.size, length))
        }
        field(0, 16, "$name/")
        field(16, 12, "0")
        field(28, 6, "0")
        field(34, 6, "0")
        field(40, 8, "100644")
        field(48, 10, data.size.toString())
        header[58] = 0x60.toByte()
        header[59] = 0x0A.toByte()
        out.write(header)
        out.write(data)
        if (data.size % 2 == 1) out.write('\n'.code)
    }

    /** Creates a valid USTAR `.tar.gz` byte array in pure Kotlin for unit tests. */
    private fun buildMinimalTarGz(vararg entries: Pair<String, String>): ByteArray {
        val tarBytes = buildMinimalTar(
            *entries.map { (name, content) -> name to content.toByteArray(Charsets.UTF_8) }.toTypedArray(),
        )
        val gzOut = ByteArrayOutputStream()
        GZIPOutputStream(gzOut).use { it.write(tarBytes) }
        return gzOut.toByteArray()
    }

    private data class TarEntry(
        val name: String,
        val data: ByteArray = byteArrayOf(),
        val type: Char = '0',
        val link: String = "",
    )

    private fun buildMinimalTar(vararg entries: Pair<String, ByteArray>): ByteArray =
        buildTar(*entries.map { (name, data) -> TarEntry(name, data) }.toTypedArray())

    /** Creates a tar fixture with regular files or GNU extension headers. */
    private fun buildTar(vararg entries: TarEntry): ByteArray {
        val tarOut = ByteArrayOutputStream()
        for ((name, data, type, link) in entries) {
            val header = ByteArray(512)
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
            writeAscii(header, 100, "0000755\u0000")
            writeAscii(header, 108, "0000000\u0000")
            writeAscii(header, 116, "0000000\u0000")
            writeAscii(header, 124, "%011o\u0000".format(data.size))
            writeAscii(header, 136, "00000000000\u0000")
            for (i in 148 until 156) header[i] = ' '.code.toByte()
            header[156] = type.code.toByte()
            writeAscii(header, 157, link)
            writeAscii(header, 257, "ustar\u000000")
            val checksum = header.sumOf { it.toInt() and 0xFF }
            writeAscii(header, 148, "%06o\u0000 ".format(checksum))
            tarOut.write(header)
            tarOut.write(data)
            val pad = (512 - (data.size % 512)) % 512
            if (pad > 0) tarOut.write(ByteArray(pad))
        }
        tarOut.write(ByteArray(1024)) // EOF blocks
        return tarOut.toByteArray()
    }

    private fun writeAscii(buf: ByteArray, offset: Int, text: String) {
        val bytes = text.toByteArray(Charsets.US_ASCII)
        System.arraycopy(bytes, 0, buf, offset, bytes.size)
    }
}
