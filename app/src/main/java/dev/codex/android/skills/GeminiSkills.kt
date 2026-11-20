// Attribution: these four skills adapt the guidance of google-gemini/gemini-skills
// (Apache License 2.0) — gemini-api-dev, gemini-api-cli, gemini-live-api-dev and
// gemini-omni-flash-api. The instructions below are written fresh for this app;
// no source text was copied. The skills teach the agent to use the Gemini API
// through this app's own plumbing (OpenAI-compatible chat, shell_exec in the
// on-device Linux workspace, the gemini_live / gemini_video tools) with the
// user's own Gemini API key only (BYOK) — no key is ever baked in.
package dev.codex.android.skills

/**
 * The four Gemini skills, as plain [Skill] data.
 *
 * Kept in this standalone file so the coordinator can merge them into
 * [SkillStore.BUILTINS] in integration without touching SkillStore.kt.
 * Every tool named here exists in this build once the coordinator applies
 * the register lines in the handover report:
 * - `attachment_read` — registered per chat thread (ChatViewModel).
 * - `webfetch`, `shell_exec`, `workspace_list`, `workspace_read` —
 *   registered in the interactive registry (ToolRegistry.withBuiltins /
 *   registerDeveloperTools).
 * - `image-generate` — tools/ImageGenerateTool.kt, registered in
 *   ToolRegistry.withBuiltins.
 * - `gemini_live`, `gemini_video` — new tools built in this pass
 *   (tools/GeminiLiveTool.kt, tools/GeminiVideoTool.kt); registered
 *   per chat thread by the coordinator in integration.
 * Never name `read_image`, `read_pdf` or any other tool the Gemini
 * skills do not use — the model must never be told a tool it cannot
 * call for this skill. (Those tools do exist in this build; they
 * belong to the document/image skills.)
 */
object GeminiSkills {

    /** The Gemini API base for the OpenAI-compatible chat path. */
    const val GEMINI_OPENAI_BASE_URL: String = "https://generativelanguage.googleapis.com/v1beta/openai/"

    /** Default Gemini chat model for this app (matches the AI Studio provider preset). */
    const val GEMINI_DEFAULT_MODEL: String = "gemini-4-hydrogen"

    val GEMINI_SKILLS: List<Skill> = listOf(
        Skill(
            id = "gemini-api-dev",
            name = "Gemini API development",
            description = "Writes code that calls the Gemini API for text, chat, file and image understanding, image generation, structured output and function calling, using the app's OpenAI-compatible client and the user's own Gemini key.",
            instructions = "When the user asks for code that calls the Gemini API, or for the agent itself to analyse a file or picture with Gemini, work through the app's own OpenAI-compatible chat path, not through a Gemini SDK you install here. The Gemini endpoint is $GEMINI_OPENAI_BASE_URL with the user's own Gemini API key from Settings (BYOK) — never paste, print, or store any API key, and never invent one; if calls come back 401/403, stop and tell the user the Settings key is missing or is not a Gemini key. Default model is $GEMINI_DEFAULT_MODEL; use gemini-4-neon for the hardest reasoning, gemini-4-carbon for the hardest coding, and gemini-3.5-flash-lite for high-throughput work. Never use gemini-2.5-*, gemini-2.0-* or gemini-1.5-* models — they are retired; if the user asks for one, substitute $GEMINI_DEFAULT_MODEL and say so. For image generation use the image-generate tool with model gemini-nano-banana-2.1 (fast default), gemini-3-pro-image (highest quality) or gemini-3.1-flash-lite-image (fastest); pass the prompt and size, and report the saved file path it returns — if it reports the provider has no image endpoint, say so instead of faking an image. To analyse a file the user attached, read it first with attachment_read (paged, cite the page as [p. N]) and send the text you actually read; attached images already reach the model inline, so describe what is visible and read out any text in the image word for word. To check a Gemini API detail you are unsure about, fetch the official docs page with webfetch (aistudio.google.com/docs) before writing code — the docs are the source of truth, not memory. Keep replies grounded: structured output means asking for JSON with an explicit schema; multi-turn chat means reusing the previous interaction id the server returned; streaming means reporting text as it arrives and the total token count at the end. Never claim a call succeeded unless a tool returned its result.",
            toolNames = listOf("attachment_read", "webfetch", "image-generate"),
            version = 1,
            enabled = true,
        ),
        Skill(
            id = "gemini-api-cli",
            name = "Gemini API from the shell",
            description = "Runs the gemini-api command-line client inside the on-device Linux workspace for media generation, file and YouTube analysis, transcription, and Deep Research, using the user's own Gemini key.",
            instructions = "When the user asks for shell work against the Gemini API — generating an image, audio, music or video file, analysing a local PDF or recording, transcribing audio, or running Deep Research — use the gemini-api command-line client through shell_exec in the on-device Linux workspace. First check it is actually there: run `command -v gemini-api || gemini-api --help`. If it is missing, install it with the documented installer (`curl -fsSL https://raw.githubusercontent.com/google-gemini/gemini-api-cli/main/scripts/install.sh | bash`) and then re-check `gemini-api --help` — if the install fails or the help text does not appear, stop and report the failure plainly; never pretend the CLI ran. Authentication is the user's own Gemini API key only: run `gemini-api whoami` to see which credential won, and remember an exported GEMINI_API_KEY overrides anything stored in the keychain or config file. Never paste an API key into a shell command — the shell blocks credential-like text, and the key must never land in a workspace file either; if no credential is configured, tell the user to run `gemini-api configure` in the Terminal tab themselves (or export GEMINI_API_KEY there) and then try again. Useful commands: `gemini-api generate \"prompt\"` (default model $GEMINI_DEFAULT_MODEL, streams text), `gemini-api image \"prompt\" --out ./file.jpg`, `gemini-api tts \"text\" --out ./file.wav`, `gemini-api music \"prompt\" --out ./file.wav`, `gemini-api video \"prompt\" -m gemini-omni-1.2-flash --out ./file.mp4`, `gemini-api analyze -i file.pdf \"question\"` (also takes YouTube URLs), `gemini-api transcribe -i audio.mp3 --format srt --out ./file.srt`, `gemini-api files upload path --wait` (returns a files/<id> URI for big or reused files; attach with -i), `gemini-api agent run \"task\" --agent deep-research-preview-04-2026 --background` then `gemini-api agent status <run-id>`. Media and transcription commands write a file and print only its path — report that path. Add `--dry-run` to preview a redacted request without calling the API, and `-o json --stream=false` for one JSON object instead of streamed text. Exit code 0 means ok, 1 means a runtime or API error, 2 means bad usage, 3 means an auth problem — report the code and the tool's own error text, never a guessed result. This CLI is a stateless API client, not the interactive Gemini CLI coding agent; for code that lives in application source, use the gemini-api-dev skill instead.",
            toolNames = listOf("shell_exec", "workspace_list", "workspace_read"),
            version = 1,
            enabled = true,
        ),
        Skill(
            id = "gemini-live-api-dev",
            name = "Gemini Live API sessions",
            description = "Runs real-time voice and text conversations with the Gemini Live API over WebSocket, with live transcription, using the gemini_live tool and the user's own Gemini key.",
            instructions = "When the user asks for a live voice conversation, live transcription, or a real-time spoken reply from Gemini, use the gemini_live tool — it is the only live-session tool in this build. Start a session with action=start, naming the model (gemini-3.8-live for ordinary low-latency voice, gemini-3.8-live-extended-thinking when the reply needs background reasoning, gemini-3.5-transcribe-live for speech-to-text only), a voice name when the user picks one, and a short system instruction describing the role. Then send text with action=send_text and read the reply the tool returns; check action=status for the session state and the transcript so far; end with action=stop. Mic streaming is only available when the user has granted the microphone permission and the tool was started with mic=true — if the tool reports the mic is not available, say so and continue text-only; never claim to hear the user when no audio reached the session. Audio in is 16 kHz mono PCM and audio out is 24 kHz mono PCM; the tool returns live transcriptions of both sides, so report those words, not a paraphrase you invented. A live connection lasts about 10 minutes and can drop: if the tool reports a disconnect it reconnects a few times on its own, and if it reports the session failed, tell the user and offer to start a fresh session — never present a reconnected or failed session as an unbroken one. Sessions are billed to the user's own Gemini API key from Settings (BYOK); a 401/403 reply means the key is missing or is not a Gemini key — stop and say that. For one-shot questions, file analysis, or anything that does not need a live back-and-forth, use the gemini-api-dev skill instead of opening a live session.",
            toolNames = listOf("gemini_live"),
            version = 1,
            enabled = true,
        ),
        Skill(
            id = "gemini-omni-flash-api",
            name = "Gemini video generation and editing",
            description = "Generates and edits short videos with Gemini Omni Flash (text-to-video, first/last-frame, extensions, reference-guided) and prepares source clips with ffmpeg, using the gemini_video tool and the user's own Gemini key.",
            instructions = "When the user asks for a generated or edited video, use the gemini_video tool with model gemini-omni-1.2-flash. Text-to-video clips run 3 to 15 seconds (10 by default); a first-frame image starts a 3 to 15 second clip; a first-frame plus last-frame pair makes a transition of 3 to 10 seconds only (use the same image twice for a seamless loop); an extension adds 3 to 10 seconds per turn up to 30 seconds total. Resolutions are 360p, 720p (default), 1080p or 4k, in 16:9 or 9:16. Up to 6 reference images can guide a new video, an edit, or an extension; reference videos (up to 3 for clips of 3 to 10 seconds, at most 1 for 11 to 15 second clips) can only guide a new video, never an edit or extension. Describe each reference in plain words in the prompt (which image is the character, which is the setting) — never use angle-bracket tags for them. Source videos for editing or extension must be 10 seconds or shorter at 720p or below: the tool trims and scales them with ffmpeg first, and strips the audio first when the user wants all sound regenerated (otherwise the original audio is kept and adapted). Keep editing prompts short and end them with \"Keep everything else the same\"; for a single unbroken scene say so explicitly (\"in a single continuous shot, no scene cuts\"). Generation takes minutes — the tool polls until the video is ready or its timeout is reached; report the saved .mp4 path it returns. If it reports an empty result or a failure, say so plainly and pass on the error: uploading a video to edit is not available in some regions (EEA, Switzerland, the UK and some US states), which shows up as an empty output, and that is a service limit, not a bug to work around. Never invent a video file, a path, or a download link — only a path the tool actually returned exists. Video work is billed to the user's own Gemini API key from Settings (BYOK); a 401/403 reply means the key is missing or is not a Gemini key.",
            toolNames = listOf("gemini_video", "shell_exec"),
            version = 1,
            enabled = true,
        ),
    )
}
