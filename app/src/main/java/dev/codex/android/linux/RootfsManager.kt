package dev.codex.android.linux

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * One binary extracted from a `.deb` artifact's `data.tar.xz`.
 *
 * [tarPath] is the path inside the tar as stored by `dpkg-deb` (leading `./`
 * stripped — see [DebExtractor]); [stagedFileName] is the name the binary is
 * staged under in `native-stage/` (and packaged under in `jniLibs/`).
 * [expectedSha256] pins the EXTRACTED binary, verified after extraction as
 * defense in depth on top of the `.deb`-level hash in [PinnedArtifact].
 */
data class ExtractedBinary(
    val tarPath: String,
    val stagedFileName: String,
    val expectedSha256: String,
)

/**
 * Metadata for one downloadable runtime artifact (Alpine minirootfs or the
 * Termux PRoot `.deb`). Every download MUST pass SHA-256 verification before
 * extraction or execution, and every binary extracted from a `.deb` MUST pass
 * its own SHA-256 verification before staging. There is no placeholder hash
 * and no escape hatch: verification fails closed on blank, malformed, or
 * non-matching hashes.
 *
 * [extractedBinaries] is non-empty only for `.deb` artifacts, where it lists
 * the binaries to pull out of `data.tar.xz` and stage. Empty for the rootfs
 * tarball, which is extracted whole.
 */
data class PinnedArtifact(
    val id: String,
    val url: String,
    val expectedSha256: String,
    val license: String,
    val destinationRelativePath: String,
    val extractedBinaries: List<ExtractedBinary> = emptyList(),
)

/**
 * Pure, JVM-testable helper functions for SHA-256 verification and safe POSIX
 * tar.gz extraction (no external tar library required; protects against path
 * traversal and symlink escape).
 */
object RootfsVerifier {

    /** Computes lowercase hex SHA-256 of [file]. */
    fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(16 * 1024)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Computes lowercase hex SHA-256 of raw [bytes]. */
    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    /**
     * Verifies [file] against [expectedSha256].
     * Fails closed whenever [expectedSha256] is blank, malformed (not exactly
     * 64 lowercase/uppercase hex chars), or does not match the file's digest.
     * There is no placeholder value and no bypass parameter.
     */
    fun verifySha256(
        file: File,
        expectedSha256: String,
    ): Boolean {
        if (!file.isFile) return false
        val expected = expectedSha256.trim()
        if (expected.length != 64 || !expected.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            return false
        }
        return sha256Hex(file).equals(expected, ignoreCase = true)
    }

    /**
     * Resolves [entryName] safely inside [destDir]. Rejects absolute paths,
     * `..` escapes, and NUL bytes with [SecurityException].
     */
    fun resolveSafeTarEntry(destDir: File, entryName: String): File {
        val clean = entryName.trim().removePrefix("./").trimStart('/')
        if (clean.contains('\u0000')) {
            throw SecurityException("Tar entry contains NUL byte")
        }
        val destCanonical = destDir.canonicalFile
        val target = File(destCanonical, clean).canonicalFile
        val prefix = destCanonical.path + File.separator
        if (target != destCanonical && !target.path.startsWith(prefix)) {
            throw SecurityException("Tar entry escapes rootfs: $entryName")
        }
        return target
    }

    /**
     * Pure-Kotlin USTAR/PAX `.tar.gz` extractor for Alpine minirootfs tarballs.
     * Supports regular files, directories, GNU LongName (`L`), and safe relative
     * symlinks (symlinks escaping [destDir] are skipped). Never runs as root.
     */
    fun extractTarGzSafely(tarGzFile: File, destDir: File): Int {
        destDir.mkdirs()
        var extractedCount = 0
        GZIPInputStream(BufferedInputStream(FileInputStream(tarGzFile))).use { gz ->
            extractedCount = extractTarStreamSafely(gz, destDir)
        }
        return extractedCount
    }

    internal fun extractTarStreamSafely(input: InputStream, destDir: File): Int {
        destDir.mkdirs()
        val destCanonical = destDir.canonicalFile
        val header = ByteArray(512)
        var pendingLongName: String? = null
        var count = 0

        while (true) {
            val read = readFully(input, header)
            if (read < 512 || header.all { it == 0.toByte() }) break

            val rawName = pendingLongName ?: parseTarName(header)
            pendingLongName = null
            val typeFlag = header[156].toInt().toChar()
            val size = parseOctal(header, 124, 12)
            val mode = parseOctal(header, 100, 8).toInt()
            val padding = ((512L - (size % 512L)) % 512L)

            if (typeFlag == 'L') {
                // GNU LongName extension: payload holds the next entry's full path.
                val nameBytes = ByteArray(size.toInt())
                readFully(input, nameBytes)
                skipExact(input, padding)
                pendingLongName = String(nameBytes, Charsets.UTF_8).trimEnd('\u0000', '\n')
                continue
            }

            if (typeFlag == 'x' || typeFlag == 'g') {
                // PAX extended header: skip metadata payload cleanly.
                skipExact(input, size + padding)
                continue
            }

            val normalizedName = rawName.trim().removePrefix("./")
            if (normalizedName.isEmpty() || normalizedName == ".") {
                skipExact(input, size + padding)
                continue
            }

            val target = resolveSafeTarEntry(destCanonical, normalizedName)
            when (typeFlag) {
                '5' -> {
                    target.mkdirs()
                    skipExact(input, size + padding)
                    count++
                }
                '0', '\u0000' -> {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out ->
                        copyExact(input, out, size)
                    }
                    skipExact(input, padding)
                    // Preserve owner execute bit for binaries inside rootfs/bin, /usr/bin.
                    if ((mode and 0x49) != 0) {
                        target.setExecutable(true, false)
                    }
                    count++
                }
                '2' -> {
                    // Symlink inside rootfs: skip payload and ignore links escaping rootfs.
                    skipExact(input, size + padding)
                    val linkTarget = parseCString(header, 157, 100)
                    if (linkTarget.isNotBlank() && !linkTarget.contains("..")) {
                        runCatching {
                            target.parentFile?.mkdirs()
                            java.nio.file.Files.deleteIfExists(target.toPath())
                            java.nio.file.Files.createSymbolicLink(
                                target.toPath(),
                                java.nio.file.Paths.get(linkTarget),
                            )
                        }
                        count++
                    }
                }
                else -> {
                    // Hardlinks, FIFOs, device nodes: skip safely in rootless mode.
                    skipExact(input, size + padding)
                }
            }
        }
        return count
    }

    private fun parseTarName(header: ByteArray): String {
        val name = parseCString(header, 0, 100)
        val prefix = parseCString(header, 345, 155)
        return if (prefix.isNotEmpty()) "$prefix/$name" else name
    }

    private fun parseCString(buf: ByteArray, offset: Int, length: Int): String {
        var end = offset
        val max = offset + length
        while (end < max && buf[end] != 0.toByte()) end++
        return String(buf, offset, end - offset, Charsets.UTF_8)
    }

    private fun parseOctal(buf: ByteArray, offset: Int, length: Int): Long {
        val raw = parseCString(buf, offset, length).trim()
        if (raw.isEmpty()) return 0L
        return raw.toLongOrNull(8) ?: 0L
    }

    internal fun readFully(input: InputStream, buf: ByteArray): Int {
        var total = 0
        while (total < buf.size) {
            val r = input.read(buf, total, buf.size - total)
            if (r == -1) break
            total += r
        }
        return total
    }

    private fun copyExact(input: InputStream, out: FileOutputStream, size: Long) {
        val buf = ByteArray(16 * 1024)
        var remaining = size
        while (remaining > 0L) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val r = input.read(buf, 0, toRead)
            if (r == -1) throw IOException("Unexpected EOF inside tar archive")
            out.write(buf, 0, r)
            remaining -= r
        }
    }

    internal fun skipExact(input: InputStream, bytes: Long) {
        var remaining = bytes
        val sink = ByteArray(4096)
        while (remaining > 0L) {
            val r = input.read(sink, 0, minOf(sink.size.toLong(), remaining).toInt())
            if (r == -1) break
            remaining -= r
        }
    }
}

/**
 * Minimal `ar` + XZ extractor for Debian packages (Termux `.deb` files).
 * Pure and JVM-testable: parses the `ar` archive, takes the `data.tar.xz`
 * member, decompresses it with XZ for Java (`org.tukaani:xz`), and feeds the
 * tar stream to [RootfsVerifier.extractTarStreamSafely] (same path-traversal
 * and symlink protections as the rootfs extractor).
 *
 * Only `data.tar.xz` is supported — that is what Termux `.deb` files ship.
 * GNU `ar` long-name string tables (`//` member / `/offset` references) are
 * rejected loudly rather than mis-parsed; `dpkg-deb` never emits them for
 * these members.
 */
object DebExtractor {

    private val AR_GLOBAL_MAGIC = "!<arch>\n".toByteArray(Charsets.US_ASCII)
    private const val AR_HEADER_LEN = 60
    private const val MAX_DATA_TAR_XZ_BYTES = 64L * 1024 * 1024

    /**
     * Extracts the `data.tar.xz` member of [debFile] into [destDir].
     * Returns the number of tar entries extracted. Throws [IOException] on a
     * malformed `ar` archive, a missing/oversized `data.tar.xz` member, or a
     * corrupt XZ stream.
     */
    fun extractDataTarXz(debFile: File, destDir: File): Int {
        destDir.mkdirs()
        FileInputStream(debFile).buffered().use { input ->
            val magic = ByteArray(8)
            if (RootfsVerifier.readFully(input, magic) != 8 || !magic.contentEquals(AR_GLOBAL_MAGIC)) {
                throw IOException("Not a valid ar archive (bad global magic): ${debFile.name}")
            }
            while (true) {
                val header = ByteArray(AR_HEADER_LEN)
                val read = RootfsVerifier.readFully(input, header)
                if (read == 0) break // Clean end of archive without data.tar.xz.
                if (read < AR_HEADER_LEN) {
                    throw IOException("Truncated ar member header in ${debFile.name}")
                }
                if (header[58] != 0x60.toByte() || header[59] != 0x0A.toByte()) {
                    throw IOException("Invalid ar member header magic in ${debFile.name}")
                }
                val rawName = String(header, 0, 16, Charsets.US_ASCII).trim()
                val name = rawName.removeSuffix("/")
                if (name == "//" || (name.startsWith("/") && name.drop(1).all { it.isDigit() })) {
                    throw IOException("ar long member names (string table) unsupported in ${debFile.name}")
                }
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLongOrNull()
                    ?: throw IOException("Invalid ar member size in ${debFile.name}")
                if (size < 0L) throw IOException("Negative ar member size in ${debFile.name}")
                val padding = if (size % 2L == 1L) 1L else 0L

                if (name == "data.tar.xz") {
                    if (size > MAX_DATA_TAR_XZ_BYTES) {
                        throw IOException("data.tar.xz member too large (${size} bytes) in ${debFile.name}")
                    }
                    val compressed = ByteArray(size.toInt())
                    if (RootfsVerifier.readFully(input, compressed) != size.toInt()) {
                        throw IOException("Truncated data.tar.xz member in ${debFile.name}")
                    }
                    if (padding == 1L) input.read() // ar pads odd-sized members with one byte.
                    XZInputStream(ByteArrayInputStream(compressed)).use { xz ->
                        return RootfsVerifier.extractTarStreamSafely(xz, destDir)
                    }
                } else {
                    RootfsVerifier.skipExact(input, size + padding)
                }
            }
        }
        throw IOException("No data.tar.xz member found in ${debFile.name}")
    }

    /**
     * Extracts [artifact]'s binaries from [debFile] and stages them under
     * [stageDir] using their [ExtractedBinary.stagedFileName] names.
     * Each extracted binary is SHA-256 verified against its pinned hash
     * before staging; a missing entry or a hash mismatch throws
     * [SecurityException] / [IOException] and stages nothing further.
     * Returns the staged files in [PinnedArtifact.extractedBinaries] order.
     */
    fun extractAndStageBinaries(debFile: File, stageDir: File, artifact: PinnedArtifact): List<File> {
        require(artifact.extractedBinaries.isNotEmpty()) {
            "Artifact ${artifact.id} declares no extracted binaries"
        }
        stageDir.mkdirs()
        val extractDir = Files.createTempDirectory(stageDir.toPath(), "deb-extract-").toFile()
        try {
            extractDataTarXz(debFile, extractDir)
            val staged = mutableListOf<File>()
            for (binary in artifact.extractedBinaries) {
                val extracted = File(extractDir, binary.tarPath)
                if (!extracted.isFile) {
                    throw IOException(
                        "Expected ${binary.tarPath} inside ${debFile.name} " +
                            "(artifact ${artifact.id}) but it was not extracted",
                    )
                }
                if (!RootfsVerifier.verifySha256(extracted, binary.expectedSha256)) {
                    throw SecurityException(
                        "SHA-256 verification failed for extracted ${binary.stagedFileName} " +
                            "from ${artifact.id} (expected=${binary.expectedSha256}). " +
                            "Staging aborted.",
                    )
                }
                val target = File(stageDir, binary.stagedFileName)
                extracted.copyTo(target, overwrite = true)
                target.setExecutable(true, false)
                staged += target
            }
            return staged
        } finally {
            extractDir.deleteRecursively()
        }
    }
}

/**
 * On-demand rootless ARM64 Alpine Linux rootfs & toolchain bootstrapper (Milestone M4).
 *
 * Security & Android W^X Packaging Contract:
 * - PRoot (`libproot.so`, `libproot-loader.so`) is GPL-2.0 and is NOT committed
 *   as blobs in the repository. The phone downloads the official Termux `.deb`
 *   over HTTPS, verifies the `.deb` SHA-256, extracts `data.tar.xz` in-app,
 *   and verifies each extracted binary's SHA-256 before staging.
 * - There is NO host-side BusyBox artifact: `ProotShellEngine.busyboxBinary()`
 *   was never called anywhere, and the Alpine minirootfs itself ships
 *   `./bin/busybox`, so in-rootfs shells already have it.
 * - On targetSdk >= 29 (Android 10+), `execve()` from `filesDir` is blocked by SELinux
 *   (W^X enforcement). At runtime, executable PIE `.so` binaries MUST live in
 *   `context.applicationInfo.nativeLibraryDir` (packaged into `app/src/main/jniLibs/arm64-v8a/`
 *   by the CI build with `useLegacyPackaging = true` and `keepDebugSymbols`).
 * - When `nativeLibraryDir` does not yet contain pre-packaged `.so` files (e.g., dev builds
 *   or first-run verification), [ensureRootfs] downloads the official release artifacts
 *   over HTTPS, verifies every download against [PINNED_ARTIFACTS], stages verified
 *   copies under `filesDir/linux/native-stage/` (and checks `nativeLibraryDir` first
 *   for execution), and extracts the ARM64 Alpine minirootfs into `filesDir/linux/rootfs`.
 *   Staged copies under `filesDir` cannot be executed on Android 10+ (W^X); the
 *   supported production path is CI-packaged `jniLibs` — see BUILD_DIFF.md.
 * - Never runs as root and never requests root. All network and disk I/O runs on [Dispatchers.IO].
 */
class RootfsManager internal constructor(
    private val filesDir: File,
    private val nativeLibDir: File,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val artifacts: List<PinnedArtifact> = PINNED_ARTIFACTS,
    private val shellRunnerFactory: ((File, File) -> ProotShellEngine)? = null,
) {
    /** Standard Android constructor required by the M4 public contract. */
    constructor(context: Context) : this(
        filesDir = context.applicationContext.filesDir,
        nativeLibDir = File(context.applicationContext.applicationInfo.nativeLibraryDir),
    )

    private val bootstrapMutex = Mutex()

    /** Rootfs directory: `<filesDir>/linux/rootfs`. */
    fun rootfsDir(): File = File(filesDir, "linux/rootfs")

    /** Directory where verified downloaded `.so` staging binaries are stored. */
    fun nativeStageDir(): File = File(filesDir, "linux/native-stage")

    /**
     * Exact native library directory where Android extracts `jniLibs` PIE binaries
     * (`libproot.so`, `libproot-loader.so`) for SELinux-compliant `execve()`.
     */
    fun nativeExecutableDir(): File = nativeLibDir

    /** True when the Alpine rootfs has been extracted and marked ready. */
    fun isInstalled(): Boolean {
        val root = rootfsDir()
        return File(root, INSTALLED_MARKER).isFile &&
            (File(root, "bin/sh").exists() || File(root, "bin/busybox").exists())
    }

    /**
     * Downloads, SHA-256 verifies, and extracts the ARM64 Alpine minirootfs plus
     * verifies/stages the PRoot binaries from the Termux `.deb`.
     * Idempotent: returns immediately if already installed.
     */
    suspend fun ensureRootfs(): File = withContext(Dispatchers.IO) {
        bootstrapMutex.withLock {
            val root = rootfsDir()
            if (isInstalled()) return@withLock root

            val downloadCache = File(filesDir, "linux/downloads").apply { mkdirs() }
            val stageDir = nativeStageDir().apply { mkdirs() }

            for (artifact in artifacts) {
                when {
                    artifact.id == ARTIFACT_ALPINE_ROOTFS -> ensureAlpineArtifact(artifact, downloadCache, root)
                    artifact.extractedBinaries.isNotEmpty() -> ensureDebArtifact(artifact, downloadCache, stageDir)
                    else -> throw IllegalStateException(
                        "Unsupported artifact kind for ${artifact.id}: no extraction rule. " +
                            "Refusing to silently skip a pinned artifact.",
                    )
                }
            }

            File(root, INSTALLED_MARKER).writeText("alpine-minirootfs-aarch64\n")
            root
        }
    }

    /** Downloads, verifies, and extracts the Alpine minirootfs tarball. */
    private fun ensureAlpineArtifact(artifact: PinnedArtifact, downloadCache: File, root: File) {
        val downloaded = File(downloadCache, "${artifact.id}.download")
        downloadHttps(artifact.url, downloaded)
        try {
            if (!RootfsVerifier.verifySha256(downloaded, artifact.expectedSha256)) {
                throw SecurityException(
                    "SHA-256 verification failed for ${artifact.id} (expected=${artifact.expectedSha256}). " +
                        "Download aborted and deleted.",
                )
            }
            root.mkdirs()
            RootfsVerifier.extractTarGzSafely(downloaded, root)
            writeDefaultResolvConf(root)
        } finally {
            downloaded.delete()
        }
    }

    /**
     * Downloads, verifies, and extracts a `.deb` artifact's binaries.
     * Skips the download when every binary is already packaged in
     * `nativeLibraryDir` (the CI `jniLibs` path). The `.deb` hash is verified
     * before extraction, and each extracted binary is verified against its
     * own pinned hash before staging.
     */
    private fun ensureDebArtifact(artifact: PinnedArtifact, downloadCache: File, stageDir: File) {
        if (artifact.extractedBinaries.all { File(nativeLibDir, it.stagedFileName).isFile }) {
            return
        }
        val downloaded = File(downloadCache, "${artifact.id}.download")
        downloadHttps(artifact.url, downloaded)
        try {
            if (!RootfsVerifier.verifySha256(downloaded, artifact.expectedSha256)) {
                throw SecurityException(
                    "SHA-256 verification failed for ${artifact.id} (expected=${artifact.expectedSha256}). " +
                        "Download aborted and deleted.",
                )
            }
            DebExtractor.extractAndStageBinaries(downloaded, stageDir, artifact)
        } finally {
            downloaded.delete()
        }
    }

    /**
     * Installs a supported developer toolchain inside the rootfs via `apk add --no-cache`.
     * Supported names: `git`, `rg`, `python`, `node`, `go`, `rust`, `clang`.
     */
    suspend fun ensureToolchain(name: String): Boolean = withContext(Dispatchers.IO) {
        val packages = TOOLCHAIN_PACKAGES[name.trim().lowercase()] ?: return@withContext false
        val root = ensureRootfs()
        val engine = shellRunnerFactory?.invoke(root, nativeLibDir)
            ?: ProotShellEngine(root, nativeLibDir)
        val cmd = "apk update && apk add --no-cache ${packages.joinToString(" ")}"
        val result = engine.shellExec(cmd, timeoutMs = 180_000L)
        result.exitCode == 0
    }

    /** Downloads [url] over HTTPS only into [destination]. */
    internal fun downloadHttps(url: String, destination: File) {
        require(url.startsWith("https://")) {
            "Refusing non-HTTPS download URL for rootfs/binary: $url"
        }
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Download failed for $url: HTTP ${response.code}")
            }
            val body = response.body ?: throw IOException("Empty download body for $url")
            destination.parentFile?.mkdirs()
            body.byteStream().use { input ->
                FileOutputStream(destination).use { out -> input.copyTo(out) }
            }
        }
    }

    private fun writeDefaultResolvConf(root: File) {
        val etcDir = File(root, "etc").apply { mkdirs() }
        val resolv = File(etcDir, "resolv.conf")
        if (!resolv.exists()) {
            resolv.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        }
    }

    companion object {
        const val ARTIFACT_ALPINE_ROOTFS = "alpine-minirootfs-aarch64"
        const val ARTIFACT_PROOT_DEB = "proot-deb"

        private const val INSTALLED_MARKER = ".codex_rootfs_ready"

        /**
         * Official HTTPS release URLs + pinned SHA-256 table.
         *
         * Hashes verified 2026-11-20 against the upstream-published values:
         * Alpine's official `.sha256` file, and the Termux Packages index
         * (`binary-aarch64/Packages`) plus locally-extracted binary hashes
         * (see `hidden_files/rootfs-hashes.md`). No placeholder hashes remain
         * anywhere in main source; verification fails closed.
         *
         * The two dead staged URLs were removed, not fixed in place:
         * `github.com/termux/proot/releases/latest/download/<asset>` 404s because
         * `termux/proot` publishes ZERO GitHub releases, and
         * `busybox.net/downloads/binaries/1.35.0-aarch64-linux-musl/busybox`
         * 404s because busybox.net publishes no aarch64 binary at any version.
         * The Termux `.deb` is the real upstream distribution for PRoot, and
         * the Alpine minirootfs already ships `./bin/busybox`, so no host-side
         * BusyBox artifact is needed at all.
         */
        val PINNED_ARTIFACTS: List<PinnedArtifact> = listOf(
            PinnedArtifact(
                id = ARTIFACT_ALPINE_ROOTFS,
                url = "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz",
                expectedSha256 = "041fa34a81788242df9e78fa69b97ab45b8ec47ddbf88864755610414a7bf3de",
                license = "MIT / Alpine",
                destinationRelativePath = "rootfs/",
            ),
            PinnedArtifact(
                id = ARTIFACT_PROOT_DEB,
                url = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.1.107.102_aarch64.deb",
                expectedSha256 = "10a394b585c2fc2b2c8502544f4e5f1be581dc8bfff79e8ba62f16207423e030",
                license = "GPL-2.0",
                destinationRelativePath = "jniLibs/arm64-v8a/",
                extractedBinaries = listOf(
                    ExtractedBinary(
                        // Inside data.tar.xz the entry is stored as
                        // "./data/data/com.termux/files/usr/bin/proot"; the
                        // tar extractor strips the leading "./".
                        tarPath = "data/data/com.termux/files/usr/bin/proot",
                        stagedFileName = "libproot.so",
                        expectedSha256 = "3621937c1c5a98d0d39ca599af573a6bc15128690606d535e8b05da00cec4f41",
                    ),
                    ExtractedBinary(
                        tarPath = "data/data/com.termux/files/usr/libexec/proot/loader",
                        stagedFileName = "libproot-loader.so",
                        expectedSha256 = "1da54355333cca3fb6ee10e3b82a24df24fb230c99241ce83809bf9e240609b5",
                    ),
                ),
            ),
        )

        /** Supported toolchain identifiers mapped to Alpine `apk` packages. */
        val TOOLCHAIN_PACKAGES: Map<String, List<String>> = mapOf(
            "git" to listOf("git", "openssh-client", "ca-certificates"),
            "rg" to listOf("ripgrep"),
            "python" to listOf("python3", "py3-pip"),
            "node" to listOf("nodejs", "npm"),
            "go" to listOf("go"),
            "rust" to listOf("rust", "cargo"),
            "clang" to listOf("clang", "lld", "musl-dev", "make"),
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
