package dev.codex.android.tools

import dev.codex.android.api.FunctionCall
import dev.codex.android.api.ToolCall
import dev.codex.android.core.CodexError
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * JVM unit tests for [parseToolCall].
 *
 * The real built-ins are deliberately NOT used: OpenAppTool (and others)
 * need an Android [android.content.Context]. Instead two small stub
 * [Tool] implementations exercise the same parser paths — unknown tool,
 * invalid / non-object JSON, missing required parameter, wrong argument
 * type, and a valid call — with schemas in exactly the shape the real
 * tools use (see BuiltinTools.EchoTool).
 */
class ToolCallParserTest {

    /** Stub echo tool: one required string parameter, like EchoTool. */
    private class FakeEchoTool : Tool {
        override val name = "fake_echo"
        override val description = "Echoes back the given text"
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("text") {
                    put("type", "string")
                    put("description", "Text to echo")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("text")) }
        }
        override val sensitive = false
        override suspend fun execute(args: JsonObject): ToolResult =
            ToolResult(ok = true, output = args["text"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    /** Stub counter tool: one required integer, one optional string. */
    private class FakeCountTool : Tool {
        override val name = "fake_count"
        override val description = "Counts to the given number"
        override val parametersSchema: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("count") {
                    put("type", "integer")
                    put("description", "Number to count to")
                }
                putJsonObject("label") {
                    put("type", "string")
                    put("description", "Optional label")
                }
            }
            putJsonArray("required") { add(JsonPrimitive("count")) }
        }
        override val sensitive = false
        override suspend fun execute(args: JsonObject): ToolResult =
            ToolResult(ok = true, output = args["count"]?.jsonPrimitive?.contentOrNull ?: "")
    }

    private fun registry(): ToolRegistry = ToolRegistry().apply {
        register(FakeEchoTool())
        register(FakeCountTool())
    }

    private fun call(name: String, arguments: String): ToolCall =
        ToolCall(id = "call-1", function = FunctionCall(name = name, arguments = arguments))

    /** Runs [block] and fails unless it throws MalformedToolCall. */
    private fun assertMalformed(block: () -> Unit) {
        try {
            block()
            fail("Expected CodexError.MalformedToolCall")
        } catch (e: CodexError.MalformedToolCall) {
            // Expected.
        }
    }

    @Test
    fun validCall_parsesToRightToolAndArgs() {
        val parsed = parseToolCall(call("fake_echo", """{"text":"hello"}"""), registry())
        assertEquals("fake_echo", parsed.tool.name)
        assertEquals("hello", parsed.args["text"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun validIntegerCall_parses() {
        val parsed = parseToolCall(
            call("fake_count", """{"count":3,"label":"items"}"""),
            registry(),
        )
        assertEquals("fake_count", parsed.tool.name)
        assertEquals(3, parsed.args["count"]?.jsonPrimitive?.intOrNull)
        assertEquals("items", parsed.args["label"]?.jsonPrimitive?.contentOrNull)
    }

    @Test
    fun unknownToolName_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("no_such_tool", """{"text":"hello"}"""), registry())
        }
    }

    @Test
    fun argumentsNotValidJson_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("fake_echo", "{not json"), registry())
        }
    }

    @Test
    fun argumentsNotJsonObject_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("fake_echo", """[1, 2, 3]"""), registry())
        }
        assertMalformed {
            // A bare JSON number is valid JSON but not a JSON object.
            parseToolCall(call("fake_echo", "42"), registry())
        }
    }

    @Test
    fun missingRequiredParameter_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("fake_echo", "{}"), registry())
        }
        assertMalformed {
            parseToolCall(call("fake_count", """{"label":"only the optional one"}"""), registry())
        }
    }

    @Test
    fun wrongArgumentType_stringWhereIntegerExpected_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("fake_count", """{"count":"three"}"""), registry())
        }
    }

    @Test
    fun wrongArgumentType_integerWhereStringExpected_throwsMalformed() {
        assertMalformed {
            parseToolCall(call("fake_echo", """{"text":42}"""), registry())
        }
    }
}
