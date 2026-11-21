package dev.codex.android.skills

/**
 * Pure-instruction skills: guidance only, no new code. Each entry was
 * written fresh for this app from the behaviour of a published source
 * skill — no source text is copied here.
 *
 * Attribution:
 * - discernment-nudge, doc-coauthoring, frontend-design,
 *   internal-comms: adapted from the Anthropic skills of the same
 *   names (research copies carry their LICENSE.txt terms).
 * - twitter-algorithm-optimizer: adapted from the Composio skill of
 *   the same name (its file notes AGPL-3.0, referencing Twitter's
 *   algorithm source).
 * - content-research-writer, changelog-generator,
 *   lead-research-assistant, raffle-winner-picker, file-organizer:
 *   adapted from the Composio skills of the same names.
 *
 * toolNames name ONLY tools that exist in this build (verified from
 * tools/Tool.kt withBuiltins, the developer tools in
 * tools/DeveloperTools.kt, the per-chat attachment_read /
 * memory_search registrations in ui/ChatViewModel.kt, and the new
 * read_image / image_enhance tools plus the image-generate tool file,
 * whose registrations the coordinator applies). Where no real tool
 * fits a skill, its toolNames is empty and the instructions say to
 * reason from the conversation — never name a tool that does not
 * exist.
 */
object InstructionSkills {

    /**
     * After a substantive answer the user may act on, append 2-3 short
     * follow-up questions that help them check key facts, probe the
     * reasoning, and notice missing context. At most once per
     * conversation; never for trivial lookups, pure explanations,
     * creative writing, or when the user already asked to verify.
     */
    val DISCERNMENT_NUDGE: Skill = Skill(
        id = "discernment-nudge",
        name = "Discernment nudge",
        description = "After a substantive answer the user may act on, adds two or three short follow-up questions that help them check the facts, probe the reasoning, and notice missing context — at most once per conversation.",
        instructions = "Use this skill only after giving a substantive answer the user is likely to act on: advice or a recommendation, a drafted artifact (goal, plan, pitch, proposal, email), an estimate or projection, an analysis or interpretation of data, a factual claim they may rely on, or a multi-step argument. " +
            "First answer the question completely. Then, after a blank line, append the nudge as plain text starting with the exact line \"A few things worth a second look:\" followed by 2-3 short bullets — never more. " +
            "Each question must point at something specific in the answer just given: a number or figure and how to check it against the user's own data; a reasoning step or assumption and why it was chosen; or context the answer had to guess at because the user did not say. Phrase each as something the user could ask back verbatim, in the first person, under about 120 characters. No heading, no blockquote, no emoji, and nothing after the nudge. " +
            "Offer the nudge at most once per conversation: if it was already offered on an earlier turn, stay silent on later turns. " +
            "Skip it entirely when the user asked a trivial how-to or simple lookup, wants a purely educational explanation (including \"what is X\" and comparison questions, unless the answer ends in a recommendation), asked only to format, convert, or assemble a file from content they provided, is writing code they will run, is doing creative writing or casual chat, asked for their own opinion or take, asked you to double-check / cite / review something, asked for the quick version, or said they will do their own checking. " +
            "This skill needs no tools — it shapes how the reply is written.",
        toolNames = emptyList(),
        version = 1,
        enabled = true,
    )

    /**
     * Structured co-authoring for docs, proposals, specs, and decision
     * docs: gather context, refine section by section, then test the
     * doc against a fresh reader.
     */
    val DOC_COAUTHORING: Skill = Skill(
        id = "doc-coauthoring",
        name = "Co-author a document",
        description = "Guides the user through writing documentation, proposals, technical specs, or decision docs in three stages: gather context, refine section by section, then test the doc on a fresh reader.",
        instructions = "When the user wants to write a document — a doc, proposal, spec, PRD, design doc, decision doc, or RFC — offer this structured workflow before diving in: (1) Context Gathering, (2) Refinement and Structure, (3) Reader Testing. If they decline, work freeform. If they accept, run the stages in order and let them skip or shorten any stage on request. " +
            "Stage 1 — Context Gathering: ask what type of document this is, who the primary audience is, what impact it should have when read, whether there is a template or format to follow, and any other constraints. Then invite them to dump all context in any order — background, related discussions, why alternatives were rejected, timelines, stakeholders — and ask 5-10 numbered clarifying questions about the gaps. Move on when the answers show understanding of edge cases and trade-offs, not just basics. If they mention an attached file, read it first with attachment_read; if they mention a workspace file, read it with workspace_read. " +
            "Stage 2 — Refinement and Structure: agree 3-5 sections (start with the section that has the most unknowns; summaries last), create the scaffold with workspace_write using the document name and placeholder text per section, then work section by section: ask 5-10 clarifying questions, brainstorm 5-20 candidate points, let them keep / remove / combine by number, ask what is missing, draft the section by editing the file, and iterate on their feedback with small edits rather than reprinting the whole doc. After 3 iterations with no substantial change, ask what can be cut. Near the end, re-read the whole document for flow, redundancy, contradictions, and filler. " +
            "Stage 3 — Reader Testing: predict 5-10 questions a fresh reader would ask, then test the doc against them — here, by re-reading the finished file as if seeing it for the first time and answering each question only from what the doc itself says. Report what a fresh reader would get wrong or find ambiguous, fix those gaps by refining the weak sections, and repeat until the questions are answered cleanly. " +
            "Finish by asking the user to do a final read-through themselves: they own the document. Keep tone direct and procedural, and never pad the doc with generic filler.",
        toolNames = listOf("workspace_write", "workspace_read", "workspace_list", "attachment_read"),
        version = 1,
        enabled = true,
    )

    /**
     * Distinctive, intentional visual design for new UI or reshaping an
     * existing one: ground choices in the subject matter, plan before
     * building, and avoid the generic generated-page defaults.
     */
    val FRONTEND_DESIGN: Skill = Skill(
        id = "frontend-design",
        name = "Frontend design",
        description = "Guidance for distinctive, intentional visual design when building new UI or reshaping an existing one: aesthetic direction, typography, and choices that do not read as templated defaults.",
        instructions = "Act as the design lead of a studio known for giving every client a distinct visual identity. The client has rejected proposals that felt cliche or templated, so make deliberate, opinionated choices about palette, typography, and layout that are specific to this brief, and take aesthetic risk when it is justified. " +
            "First ground the design in the subject matter: if the brief does not identify the product, its audience, and its primary job, work those out as a proposal and confirm them before designing — the subject's industry, materials, and vernacular are where distinctive choices come from. " +
            "Work in two passes. Pass one: a short design plan with a compact token system — colour (4-6 named hex values), type (one or two clearly distinct families and their roles, with a clear type scale), layout (a one-sentence concept plus alignment guidance), and the principles that make this page unique. Then review that plan against the brief: if any part reads like the generic default you would produce for any similar page, revise it and say what changed and why. Only then start building, following the revised plan. " +
            "Avoid the commonest tells of a generated page unless the brief explicitly asks for them: a warm cream background with a serif display and terracotta accent; a near-black background with one acid-green or vermilion accent; the broadsheet hairline-rule layout; the SaaS-card kit of identical rounded cards with the same soft shadow; tracked-out all-caps eyebrow labels above every heading; meta strings joined with middle dots; single-word accenting in headlines; numbered 01/02/03 markers on content that is not actually a sequence. " +
            "Typography carries the personality: use the type treatment itself as an active part of the design, keep body line lengths under about 80 characters, and give serif body text slightly more line-height than sans-serif. Use non-triggered motion sparingly — one orchestrated moment, not a fade-and-slide on every section. Spend boldness in one place and keep everything around it quiet. " +
            "Write copy from the end user's perspective, in plain verbs and sentence case: name things by what users understand, say exactly what an action does (\"Save changes\", not \"Submit\"), keep one name for an action through the whole flow, and treat errors and empty states as directions, not apologies. " +
            "Build to a quality floor without announcing it: responsive down to mobile, visible keyboard focus, reduced motion respected, accessible contrast. Critique your own work as you build and remove one decorative element before finishing. " +
            "When building, write the page into the workspace with workspace_write (and read it back with workspace_read to check it); use image-generate only when the design genuinely needs an image asset.",
        toolNames = listOf("workspace_write", "workspace_read", "workspace_list", "image-generate"),
        version = 1,
        enabled = true,
    )

    /**
     * Internal communications in the formats a team actually uses:
     * 3P updates, newsletters, FAQs, status and leadership updates,
     * project updates, incident reports.
     */
    val INTERNAL_COMMS: Skill = Skill(
        id = "internal-comms",
        name = "Internal communications",
        description = "Writes internal communications — status reports, leadership updates, 3P updates, company newsletters, FAQs, incident reports, project updates — in the format the team already uses.",
        instructions = "When the user asks for an internal communication, first identify its type from the request: 3P update (Progress, Plans, Problems), company newsletter, FAQ answer, status report, leadership update, project update, or incident report. If the type does not match any of those, ask what format they want or what it should contain before drafting. " +
            "Match the format the team already uses: a 3P update is three short sections (what moved, what is next, what is blocked — with an owner and a date on every problem); a status or project update leads with the current state and what changed since last time, then next steps; an incident report leads with impact and timeline, then cause, then what is being done and what changes afterwards; an FAQ answers the question directly in the first sentence and then gives the detail; a newsletter leads with the most important item and keeps every item to a few lines. " +
            "Write for the actual readers: no jargon they would not use, no invented dates, numbers, or names — if a fact is missing, ask for it or mark it clearly as to be confirmed rather than filling it in. Keep it short enough to read in one pass. " +
            "Save the finished draft into the workspace with workspace_write (a sensibly named file, e.g. status-update-YYYY-MM-DD.md) and tell the user the filename, so they can review and send it themselves.",
        toolNames = listOf("workspace_write"),
        version = 1,
        enabled = true,
    )

    /**
     * Optimises a tweet draft for reach using how the recommendation
     * system ranks content: follower engagement, community resonance,
     * topical fit, and credibility — and the signals to avoid.
     */
    val TWITTER_ALGORITHM_OPTIMIZER: Skill = Skill(
        id = "twitter-algorithm-optimizer",
        name = "Twitter algorithm optimizer",
        description = "Analyses and optimises tweet drafts for maximum reach using how the recommendation system ranks content: follower engagement, community resonance, topical fit, and credibility.",
        instructions = "When the user asks to optimise a tweet, improve its reach, or understand why one underperformed, work in four steps and show the reasoning briefly. " +
            "Step 1 — Core message: what is the single most important thing this tweet communicates, who should care, and what engagement is wanted (reply, retweet, bookmark, click)? " +
            "Step 2 — Map it to how ranking works: follower engagement (will this author's followers actually like, reply, or retweet it?), community resonance (does it speak to one tight community in that community's own language, or is it vague enough to fit nobody?), topical fit (does it match the author's established niche, or clearly signal a new direction?), and credibility (does it build authority, or read as engagement bait?). " +
            "Step 3 — Optimise for signals: replies come from direct questions, debate, and incomplete thoughts that invite completion; retweets from usefulness, representational value, and entertainment; likes from novel phrasing, validation, and actionable information; bookmarks from tutorials, data, and things worth returning to. Give one rewritten tweet that keeps the author's voice and makes one clear point, plus a one-line note on why each change helps. " +
            "Step 4 — Check the negatives: remove anything likely to be reported or muted, misleading claims, off-brand pivots that confuse the author's identity, and engagement bait (\"like if you agree\", \"retweet if...\") — those damage credibility over time. " +
            "Ground rules: one clear topic per tweet; consistency in the author's lane beats occasional viral attempts; the first hour matters, so suggest posting when their followers are active and replying to replies quickly; never invent performance numbers or claim a rewrite guarantees reach. " +
            "If the user wants current information about how the platform ranks content, search the web with websearch first and say what the search returned. This skill is for X/Twitter posts only — do not apply its tactics to other platforms.",
        toolNames = listOf("websearch"),
        version = 1,
        enabled = true,
    )

    /**
     * Writing partner: research, outline, draft, and refine content
     * while preserving the user's voice, with citations and
     * section-by-section feedback.
     */
    val CONTENT_RESEARCH_WRITER: Skill = Skill(
        id = "content-research-writer",
        name = "Content research writer",
        description = "Helps write high-quality content by researching the topic, adding citations, strengthening the hook, iterating on an outline, and giving feedback on each section while preserving the user's voice.",
        instructions = "When the user wants help writing — a blog post, article, newsletter, tutorial, case study, or thought-leadership piece — work as a writing partner in this order, and let them skip any step. " +
            "1. Understand the piece: ask the topic and main argument, the target audience, the desired length and format, the goal (educate, persuade, entertain, explain), any sources to include, and the writing style they want (formal, conversational, technical). " +
            "2. Outline together: draft a short outline (hook, introduction, main sections with key points, conclusion), marking exactly where research or a citation is still needed, and get their agreement before drafting. " +
            "3. Research: use websearch to find current, relevant sources for the marked gaps and webfetch to read the best ones; add citations next to the claims they support, with a source list at the end. Never invent a citation, a quote, or a statistic — if a claim cannot be sourced, say so or cut it. " +
            "4. Draft section by section: write one section at a time in the user's voice (match their sentences and vocabulary from what they have already written, do not flatten it into generic prose), save progress into the workspace with workspace_write, and invite feedback on each section before moving on. " +
            "5. Strengthen the hook: the opening should give the reader a reason to keep going — a concrete detail, a surprising fact, or a question the piece answers — in the first two sentences. " +
            "6. Refine: review the full draft for flow, clarity, and consistency; cut repetition and filler; check every citation still supports its claim; and finish by listing anything still unverified. " +
            "Keep the user's voice throughout: their phrasing wins over a smoother generic rewrite.",
        toolNames = listOf("websearch", "webfetch", "workspace_write", "workspace_read"),
        version = 1,
        enabled = true,
    )

    /**
     * Turns git history into a user-facing changelog: categorised,
     * translated out of developer language, noise filtered.
     */
    val CHANGELOG_GENERATOR: Skill = Skill(
        id = "changelog-generator",
        name = "Changelog generator",
        description = "Creates user-facing changelogs from git commits: scans the history for a period or version range, categorises changes, and rewrites technical commits into clear customer-friendly release notes.",
        instructions = "When the user asks for a changelog or release notes, first pin down the scope: which version or date range (e.g. since the last release tag, or the past week), and whether they have a changelog style guide in the workspace to follow. " +
            "Get the commits with shell_exec — for example `git log <range> --pretty=format:'%h %s'` from the repository root — and read the workspace with workspace_read if a CHANGELOG.md or style guide already exists. " +
            "Categorise every commit into: New Features, Improvements, Fixes, and Breaking Changes (plus Security where it applies). Translate each one out of developer language into what a user actually gains: \"Fixed a crash when opening large images\" instead of \"fix null deref in image loader\"; name the user-visible behaviour, not the code. " +
            "Filter the noise: leave out pure refactors, test-only changes, build and CI churn, and dependency bumps unless they change something the user can feel. Keep each entry to one line, keep the tone consistent with the project's existing changelog, and flag breaking changes prominently with what the user must do differently. " +
            "If the range is unclear, ask before writing; if the history is empty for the range, say so instead of inventing entries. " +
            "Save the result with workspace_write (to CHANGELOG.md or the file the user names) and show the finished changelog in the reply so they can review it before publishing.",
        toolNames = listOf("shell_exec", "workspace_read", "workspace_write"),
        version = 1,
        enabled = true,
    )

    /**
     * Finds and qualifies sales leads: understand the product and the
     * ideal customer, research matching companies, score them, and
     * hand over actionable outreach for each.
     */
    val LEAD_RESEARCH_ASSISTANT: Skill = Skill(
        id = "lead-research-assistant",
        name = "Lead research assistant",
        description = "Identifies high-quality leads for a product or service by analysing the business, searching for target companies, scoring fit, and providing actionable contact strategies.",
        instructions = "When the user asks for leads, work in this order and do not skip the first two steps — a lead list without a clear product and customer profile is guesswork. " +
            "1. Understand the product: what it does, who it helps, and what problem it solves. If the user is in a code directory, read the workspace with workspace_read to understand what is being built; otherwise ask them to describe it in two or three sentences. " +
            "2. Define the ideal customer profile with them: industry and sector, company size, location, the pain points the product solves, and any technology the customer must already use. Ask for anything missing rather than assuming it. " +
            "3. Research: use websearch and webfetch to find companies matching the profile, looking for signals of need — relevant job postings, their tech stack, recent funding or expansion, hiring, or public complaints about the problem. Prefer evidence over directories: a company that shows the pain is a better lead than one that merely fits the category. " +
            "4. Score and prioritise: give each lead a fit score from 1 to 10 with a one-line reason, weighing profile alignment, signals of immediate need, likely budget, and timing. Sort highest first and say how many are high priority (8-10). " +
            "5. Report each lead as: company name and website; why it is a good fit (2-3 specific reasons tied to its business); priority score; the decision-maker role to target; a personalised outreach approach mentioning a specific pain point or recent company news; and one or two conversation starters. " +
            "Never invent a company, a person, a LinkedIn URL, or a fact about a company — if a detail cannot be found, leave it out and say so. Offer to save the list with workspace_write (CSV for CRM import) and to draft outreach messages for the top leads.",
        toolNames = listOf("websearch", "webfetch", "workspace_write"),
        version = 1,
        enabled = true,
    )

    /**
     * Picks fair, transparent raffle / giveaway winners from a list or
     * file, with no duplicates and runner-ups on request.
     */
    val RAFFLE_WINNER_PICKER: Skill = Skill(
        id = "raffle-winner-picker",
        name = "Raffle winner picker",
        description = "Picks random winners from lists or entry files for giveaways, raffles, and contests, fairly and transparently: no duplicates, exclusions honoured, runner-ups on request.",
        instructions = "When the user asks to pick a raffle or giveaway winner, first establish the entries and the rules: how many winners, whether runner-ups are needed, any exclusions (previous winners, ineligible entries), and whether selection is weighted (e.g. one ticket per entry row). " +
            "Get the entries from where they live: read an entries file in the workspace with workspace_read (CSV or a plain list — one entry per line), or use the list the user pastes in the chat. Count the entries and state the total before drawing, so the draw is auditable. " +
            "Draw with a real random source, never by picking a favourite: run a random draw in the Linux workspace with shell_exec (for example `shuf -n <count> <file>` for an unweighted draw from a file, or `shuf` with the entry list), and exclude previous winners and ineligible entries before drawing. For weighted draws, repeat each entry once per ticket and then draw. " +
            "Report transparently: the winner's name and details exactly as they appear in the entries, the row or line number, the total number of entries, the draw method, and the time. Offer to pick runner-ups, re-draw excluding the winner, and save the result with workspace_write. " +
            "Never pick by judgement or by order in the list, and never announce a winner before the draw has actually run.",
        toolNames = listOf("workspace_read", "shell_exec", "workspace_write"),
        version = 1,
        enabled = true,
    )

    /**
     * Organises files and folders: survey first, propose a structure,
     * then move things only with the user's approval, and never touch
     * what they said to avoid.
     */
    val FILE_ORGANIZER: Skill = Skill(
        id = "file-organizer",
        name = "File organizer",
        description = "Organises files and folders in the workspace by understanding what is there, finding duplicates, proposing a better structure, and cleaning up with the user's approval.",
        instructions = "When the user asks to organise files, work in this order and never move or delete anything before they approve the plan. " +
            "1. Scope: ask which folder needs organising, what the main problem is (cannot find things, duplicates, no structure, old clutter), which files or folders to avoid, and how aggressive to be (conservative tidy-up vs. a full restructure). " +
            "2. Survey: list the folder with workspace_list, read anything unclear with workspace_read, and use shell_exec to summarise what is there (file counts by type, the largest files, the oldest untouched files). Report the findings briefly: total files and folders, the type breakdown, size distribution, and the obvious problems. " +
            "3. Propose: suggest logical groupings — by type (documents, images, video, archives, code, spreadsheets, presentations), by purpose (work vs. personal, active vs. archive), or by project — and show the target structure before touching anything. Ask the user to confirm, adjust, or veto parts of it. " +
            "4. Execute only what was approved: move files with shell_exec (`mv`) or by rewriting them with workspace_write where that is safer, one group at a time, and report what moved where. " +
            "5. Duplicates and clutter: flag likely duplicates (same name and size) and files untouched for 6+ months and ask which to keep before removing anything. Never delete: move unwanted files to a clearly named archive folder inside the workspace instead, so nothing is lost. " +
            "Finish with a short summary of the new structure and anything still needing a decision from the user.",
        toolNames = listOf("workspace_list", "workspace_read", "shell_exec", "workspace_write"),
        version = 1,
        enabled = true,
    )

    /** All pure-instruction skills in this file, in id order. */
    val ALL: List<Skill> = listOf(
        CHANGELOG_GENERATOR,
        CONTENT_RESEARCH_WRITER,
        DISCERNMENT_NUDGE,
        DOC_COAUTHORING,
        FILE_ORGANIZER,
        FRONTEND_DESIGN,
        INTERNAL_COMMS,
        LEAD_RESEARCH_ASSISTANT,
        RAFFLE_WINNER_PICKER,
        TWITTER_ALGORITHM_OPTIMIZER,
    )
}
