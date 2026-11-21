package dev.codex.android.tools

/**
 * Pure scaffold logic for the `mcp_scaffold` tool (skill: mcp-builder).
 *
 * Generates a minimal, working MCP server that speaks JSON-RPC over stdio
 * with NO third-party dependencies: Python uses only the standard library,
 * TypeScript uses only Node.js built-ins. The generated server answers
 * `initialize`, `ping`, `tools/list` (one `echo` tool) and `tools/call`,
 * so it can be smoke-tested the moment it is written.
 *
 * Pure Kotlin: no Android APIs, no I/O. The tool in [BuilderTools] owns
 * writing the files (via [WorkspaceFileTools]) and registering the server
 * (via the McpHub seam). Nothing here copies any reference skill text:
 * the scaffold is written fresh for this app.
 */
object McpScaffold {

    /** Languages the scaffold supports. */
    val SUPPORTED_LANGUAGES: Set<String> = setOf("python", "typescript")

    /** One file of the generated project, relative to the project directory. */
    data class ScaffoldFile(val relativePath: String, val content: String)

    /** The server entry file for [language] (`server.py` / `server.js`). */
    fun entryFile(language: String): String = when (language.lowercase()) {
        "python" -> "server.py"
        "typescript" -> "server.js"
        else -> throw IllegalArgumentException(
            "Unsupported language '$language'. Supported: ${SUPPORTED_LANGUAGES.sorted().joinToString(", ")}.",
        )
    }

    /**
     * Command + args that run the scaffolded server from [projectDir]
     * (the guest-side absolute path, e.g. `/workspace/mcp-servers/echo`).
     * Returned as a pair so the caller can register it with
     * `McpHub.addStdioServer(name, command, args)` verbatim.
     */
    fun serverCommand(language: String, projectDir: String): Pair<String, List<String>> =
        when (language.lowercase()) {
            "python" -> "python3" to listOf("$projectDir/server.py")
            "typescript" -> "node" to listOf("$projectDir/server.js")
            else -> throw IllegalArgumentException(
                "Unsupported language '$language'. Supported: ${SUPPORTED_LANGUAGES.sorted().joinToString(", ")}.",
            )
        }

    /** Error message for an invalid server [name] or [language], null when both are fine. */
    fun validate(name: String, language: String): String? {
        if (name.isBlank()) return "Server name must not be blank."
        if (!name.matches(Regex("[A-Za-z][A-Za-z0-9_-]{0,63}"))) {
            return "Invalid server name '$name': start with a letter, then letters, digits, '-' or '_' (max 64 chars)."
        }
        if (language.lowercase() !in SUPPORTED_LANGUAGES) {
            return "Unsupported language '$language'. Supported: ${SUPPORTED_LANGUAGES.sorted().joinToString(", ")}."
        }
        return null
    }

    /**
     * All files of the scaffold for a server called [name] in [language].
     * Throws [IllegalArgumentException] when the name or language is invalid.
     */
    fun scaffoldFiles(name: String, language: String): List<ScaffoldFile> {
        validate(name, language)?.let { throw IllegalArgumentException(it) }
        val lang = language.lowercase()
        val (command, args) = serverCommand(lang, "/workspace/mcp-servers/$name")
        val manifest = """
            |{
            |  "name": "$name",
            |  "language": "$lang",
            |  "transport": "stdio",
            |  "command": "$command",
            |  "args": [${args.joinToString(", ") { "\"$it\"" }}],
            |  "version": "0.1.0"
            |}
            |
        """.trimMargin()
        val readme = """
            |# $name
            |
            |Minimal MCP server scaffolded by Codex (`mcp_scaffold`), language: $lang.
            |Speaks JSON-RPC over stdio with no third-party dependencies.
            |
            |## Run it
            |
            |```
            |$command ${args.joinToString(" ")}
            |```
            |
            |Then send one JSON-RPC request per line on stdin, e.g.
            `{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}`.
            |It answers `initialize`, `ping`, `tools/list` and `tools/call`.
            |
            |## Add a tool
            |
            |1. Add its name, description and input schema to the tool list
            |   (`TOOLS` in server.py / `TOOLS` in server.js).
            |2. Add a branch for it in the `tools/call` handler.
            |3. Keep tool names short, prefixed and action-oriented
            |   (e.g. `notes_create`, not `create`), and return focused,
            |   human-readable results — never whole API dumps.
            |
            |## Register it in Codex
            |
            |`mcp_scaffold` registers the server with the app's MCP config
            |when a hub is wired; otherwise add it manually as a stdio server
            |with command `$command` and args `${args.joinToString(" ")}`.
            |Live stdio calls inside the app need the stdio transport, which
            |is not built yet — until then the server runs standalone from
            |the Linux workspace as above.
            |
        """.trimMargin()
        val server = if (lang == "python") pythonServer(name) else typescriptServer(name)
        return listOf(
            ScaffoldFile(entryFile(lang), server),
            ScaffoldFile("mcp-server.json", manifest),
            ScaffoldFile("README.md", readme),
        )
    }

    private fun pythonServer(name: String): String = """
        |#!/usr/bin/env python3
        |# Minimal MCP server (stdio JSON-RPC) - scaffolded by Codex mcp-builder.
        |#
        |# Standard library only: no third-party dependencies. Answers
        |# `initialize`, `ping`, `tools/list` and `tools/call` (one `echo` tool).
        |# Run: python3 server.py, then send one JSON object per line on stdin.
        |import json
        |import sys
        |
        |
        |TOOLS = [
        |    {
        |        "name": "echo",
        |        "description": "Echoes the given text back. Replace with real tools.",
        |        "inputSchema": {
        |            "type": "object",
        |            "properties": {"text": {"type": "string", "description": "Text to echo back"}},
        |            "required": ["text"],
        |        },
        |    }
        |]
        |
        |
        |def send(obj):
        |    sys.stdout.write(json.dumps(obj) + "\n")
        |    sys.stdout.flush()
        |
        |
        |def handle(request):
        |    method = request.get("method", "")
        |    req_id = request.get("id")
        |    if method == "initialize":
        |        return {"jsonrpc": "2.0", "id": req_id, "result": {
        |            "protocolVersion": "2024-11-05",
        |            "capabilities": {"tools": {}},
        |            "serverInfo": {"name": "$name", "version": "0.1.0"},
        |        }}
        |    if method == "notifications/initialized":
        |        return None
        |    if method == "ping":
        |        return {"jsonrpc": "2.0", "id": req_id, "result": {}}
        |    if method == "tools/list":
        |        return {"jsonrpc": "2.0", "id": req_id, "result": {"tools": TOOLS}}
        |    if method == "tools/call":
        |        params = request.get("params", {})
        |        tool = params.get("name", "")
        |        args = params.get("arguments", {}) or {}
        |        if tool == "echo":
        |            return {"jsonrpc": "2.0", "id": req_id, "result": {
        |                "content": [{"type": "text", "text": str(args.get("text", ""))}]}}
        |        return {"jsonrpc": "2.0", "id": req_id, "error": {
        |            "code": -32602, "message": "Unknown tool: " + tool}}
        |    return {"jsonrpc": "2.0", "id": req_id, "error": {
        |        "code": -32601, "message": "Method not found: " + method}}
        |
        |
        |def main():
        |    for line in sys.stdin:
        |        line = line.strip()
        |        if not line:
        |            continue
        |        try:
        |            request = json.loads(line)
        |        except json.JSONDecodeError:
        |            continue
        |        response = handle(request)
        |        if response is not None:
        |            send(response)
        |
        |
        |if __name__ == "__main__":
        |    main()
        |
    """.trimMargin()

    private fun typescriptServer(name: String): String = """
        |// Minimal MCP server (stdio JSON-RPC) — scaffolded by Codex mcp-builder.
        |// Node.js built-ins only: no dependencies. Answers `initialize`,
        |// `ping`, `tools/list` and `tools/call` (one `echo` tool).
        |// Run: node server.js, then send one JSON object per line on stdin.
        |const readline = require("node:readline");
        |
        |const TOOLS = [
        |  {
        |    name: "echo",
        |    description: "Echoes the given text back. Replace with real tools.",
        |    inputSchema: {
        |      type: "object",
        |      properties: { text: { type: "string", description: "Text to echo back" } },
        |      required: ["text"],
        |    },
        |  },
        |];
        |
        |function send(obj) {
        |  process.stdout.write(JSON.stringify(obj) + "\n");
        |}
        |
        |function handle(request) {
        |  const method = request.method || "";
        |  const id = request.id;
        |  if (method === "initialize") {
        |    return { jsonrpc: "2.0", id, result: {
        |      protocolVersion: "2024-11-05",
        |      capabilities: { tools: {} },
        |      serverInfo: { name: "$name", version: "0.1.0" },
        |    } };
        |  }
        |  if (method === "notifications/initialized") return null;
        |  if (method === "ping") return { jsonrpc: "2.0", id, result: {} };
        |  if (method === "tools/list") {
        |    return { jsonrpc: "2.0", id, result: { tools: TOOLS } };
        |  }
        |  if (method === "tools/call") {
        |    const params = request.params || {};
        |    const tool = params.name || "";
        |    const args = params.arguments || {};
        |    if (tool === "echo") {
        |      return { jsonrpc: "2.0", id, result: {
        |        content: [{ type: "text", text: String(args.text ?? "") }] } };
        |    }
        |    return { jsonrpc: "2.0", id, error: { code: -32602, message: "Unknown tool: " + tool } };
        |  }
        |  return { jsonrpc: "2.0", id, error: { code: -32601, message: "Method not found: " + method } };
        |}
        |
        |const rl = readline.createInterface({ input: process.stdin, terminal: false });
        |rl.on("line", (line) => {
        |  if (!line.trim()) return;
        |  let request;
        |  try { request = JSON.parse(line); } catch (e) { return; }
        |  const response = handle(request);
        |  if (response !== null) send(response);
        |});
        |
    """.trimMargin()
}
