package dev.codex.android.skills

// Document skills: docx, pdf, pptx, xlsx, plus tailored-resume-generator
// and invoice-organizer.
//
// Attribution: the guidance in these four document skills is adapted
// from the corresponding skills in the anthropics/skills collection
// (docx, pdf, pptx, xlsx), which are distributed under the Apache
// License 2.0. The instructions below are written fresh for this app
// and its own tools — no text or scripts from those skills are copied.
// The tailored-resume-generator and invoice-organizer skills are
// adapted in the same way from the composio skills of those names.

/**
 * Document skills for the agent, as plain [Skill] data (see [Skill]).
 * The coordinator merges these into [SkillStore.BUILTINS] during
 * integration — this file deliberately does not touch SkillStore.kt.
 *
 * Every tool a skill names exists in this build after the document
 * work lands:
 * - `read_pdf`, `read_document`, `read_text_file`: tools/DocumentTools.kt
 * - `document_create`, `document_edit`: tools/DocumentWriteTools.kt
 * - `pdf_create`, `pdf_merge`, `pdf_split`, `pdf_rotate`, `pdf_forms`: tools/PdfTools.kt
 * - `ocr_image`: tools/OcrTools.kt (needs Tesseract in the Linux workspace)
 * - `attachment_read`: per-chat attachment reader (already in the build)
 *
 * No skill names a tool that does not exist. In particular nothing
 * here names image editing, charting, or tracked-changes support —
 * this build has no tools for those, and the instructions say so
 * instead of promising them.
 */
val DOCUMENT_SKILLS: List<Skill> = listOf(
    Skill(
        id = "docx",
        name = "Word documents",
        description = "Creates, reads, and edits Word documents (.docx): reports, letters, memos, and templates with headings and tables.",
        instructions = "Use this skill whenever the user wants a Word document (.docx) created, read, or changed. " +
            "To read one, use read_document and quote what it actually says; tables come back as [Table] blocks with cells joined by ' | '. " +
            "To create one, use document_create: one paragraph per line, '# ' for a main heading, '## ' for a subheading, and '| a | b |' lines for table rows. " +
            "To change an existing file, use document_edit: 'append' adds paragraphs, headings, or tables at the end, and 'replace' swaps one piece of text for another throughout the file. " +
            "Older .doc files cannot be read or edited here — ask the user for a .docx version instead. " +
            "Never invent content the source file does not contain, and never claim a document has a table of contents, page numbers, or tracked changes unless the user asked for them and the tool output shows them. " +
            "After creating or editing a file, read it back with read_document and check the text is what was asked for before telling the user it is done.",
        toolNames = listOf("read_document", "document_create", "document_edit", "read_text_file", "attachment_read"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "pdf",
        name = "PDF files",
        description = "Reads PDFs page by page, creates PDFs from text, merges, splits, rotates, fills form fields, and reads scanned pages with OCR.",
        instructions = "Use this skill whenever a PDF file is involved: reading one, making one, combining or splitting them, rotating pages, filling in a form, or reading a scanned page. " +
            "To read a PDF, use read_pdf and cite the page for every fact, as [p. N]; pass pages as \"all\", a single number, a range like \"1-3\", or a list like \"1,3,5\". " +
            "If read_pdf reports a page has no text, that page is a scanned image — do not guess what it says; use ocr_image on the page instead, and say the text is machine-read with its confidence. " +
            "To make a PDF from text, use pdf_create ('# ' lines become headings). " +
            "To combine PDFs, use pdf_merge in the order the user wants. To take pages out, use pdf_split (one file with the chosen pages, or one file per page with per_page). " +
            "To turn pages the right way up, use pdf_rotate with 90, 180, or 270. " +
            "For a fillable form, use pdf_forms: 'list' shows every field and its current value, 'get' reads one field, and 'set' writes the given text fields into a new PDF file — the original is never changed. " +
            "Encrypted PDFs cannot be read, merged, split, or rotated here; say so plainly if one is encountered. " +
            "After any create, merge, split, rotate, or fill, read the result back (read_pdf for text, pdf_forms list for a filled form) and check it before telling the user it is done.",
        toolNames = listOf("read_pdf", "pdf_create", "pdf_merge", "pdf_split", "pdf_rotate", "pdf_forms", "pdf_to_image", "ocr_image", "attachment_read"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "pptx",
        name = "Presentations",
        description = "Creates, reads, and edits PowerPoint decks (.pptx): one title and a few bullet points per slide, in a clean 16:9 layout.",
        instructions = "Use this skill whenever a presentation (.pptx) is involved: making a deck, reading one, or adding slides to one. " +
            "To read a deck, use read_document; the text comes back slide by slide under [Slide N] headings — report it in that order and never describe slides you have not read. " +
            "To create a deck, use document_create with format pptx: separate slides with a line containing only '---', put the slide title on the first line of each slide, and one bullet point per following line. Keep each slide to a short title and a handful of short bullets; long walls of text do not fit a slide. " +
            "To add slides to an existing deck, use document_edit with operation 'append' and the same slide syntax. " +
            "Older .ppt files cannot be read or edited here — ask the user for a .pptx version instead. " +
            "This build has no image, chart, or animation tools for decks: if the user asks for pictures or charts on slides, say that plainly and offer a text-only deck instead of pretending they were added. " +
            "After creating or editing a deck, read it back with read_document and check the slide order and wording before telling the user it is done.",
        toolNames = listOf("read_document", "document_create", "document_edit", "read_text_file", "attachment_read"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "xlsx",
        name = "Spreadsheets",
        description = "Creates, reads, and edits Excel workbooks (.xlsx): tables of data, totals, and formulas that recalculate when inputs change.",
        instructions = "Use this skill whenever a spreadsheet (.xlsx) is the main thing the user wants: reading one, making one, adding rows, or fixing data. " +
            "To read a workbook, use read_document; each sheet comes back under a [Sheet: name] heading with rows as ' | '-joined cells — use the exact sheet names and column headers the file has. " +
            "To create a workbook, use document_create with format xlsx and CSV text: the first row is the header row, one row per line, and quoted fields for values containing commas. " +
            "Write formulas, not pre-computed answers: a cell starting with '=' becomes a formula (for example '=SUM(B2:B9)') that Excel or LibreOffice computes when the file is opened — do not type the total in by hand, and say so if a value will only appear once the file is opened. " +
            "To add rows to an existing workbook, use document_edit with operation 'append' and CSV rows in the same column order as the sheet already has. " +
            "Older .xls files cannot be read or edited here — ask the user for a .xlsx version instead. " +
            "Never invent numbers, and never silently change a formula the user already had. After creating or editing a workbook, read it back with read_document and check the headers, rows, and formulas before telling the user it is done.",
        toolNames = listOf("read_document", "document_create", "document_edit", "read_text_file", "attachment_read"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "tailored-resume-generator",
        name = "Tailored resume generator",
        description = "Tailors a resume to a specific job description: pulls out the role's key requirements and rewrites the resume to match, as a .docx file.",
        instructions = "Use this skill when the user wants a resume tailored to a specific job. First get both parts: the job description and the user's background (an existing resume file, or pasted experience). " +
            "Read an existing resume with read_document (for .docx) or read_pdf (for a PDF resume); read the job description with read_text_file or read_pdf. " +
            "From the job description, list the requirements, skills, and keywords the employer actually asks for — only what is written there, never a generic list. " +
            "Then rewrite the resume around those points: lead with the most relevant experience, use the employer's own wording for matching skills, and keep every fact the user supplied (never invent jobs, dates, or qualifications). " +
            "Write the result with document_create as a .docx file ('# ' for the name and section headings, '| ... |' rows only if a compact table is genuinely needed), and keep it to the length the user asked for. " +
            "Tell the user plainly which requirements the tailored resume covers and which ones their background does not cover, so they can decide what to add. " +
            "Read the finished file back with read_document to check it before handing it over.",
        toolNames = listOf("read_document", "document_create", "document_edit", "read_text_file", "read_pdf"),
        version = 1,
        enabled = true,
    ),
    Skill(
        id = "invoice-organizer",
        name = "Invoice organizer",
        description = "Organizes invoices and receipts for tax time: reads each file, pulls out vendor, date, number and amount, and files them under consistent names with a summary spreadsheet.",
        instructions = "Use this skill when the user wants a messy folder of invoices and receipts tidied for tax preparation. Work through the files one at a time and never guess a value. " +
            "For each file, read it with the right tool: read_pdf for PDF invoices, read_document for Word or Excel invoices, read_text_file for plain-text ones, and ocr_image for scanned receipts or photos of paper invoices (say the values are machine-read and give the confidence). " +
            "From each file pull out only what is actually there: vendor name, invoice number, date, total amount, and what was bought. If a value is missing or unreadable, write 'not found' for it instead of inventing one. " +
            "Propose the organised layout to the user before changing anything: one folder per year (and per vendor inside it), files named 'YYYY-MM-DD Vendor - Invoice - Description', and a summary spreadsheet listing vendor, date, invoice number, amount, and file name for every invoice. " +
            "Write that summary with document_create as an .xlsx file. Do not delete or rename the user's originals yourself — this build has no rename tool — hand over the proposed names and the summary, and let the user confirm the filing. " +
            "Keep the originals untouched and say so; totals in the summary must add up to the amounts actually read.",
        toolNames = listOf("read_pdf", "read_document", "read_text_file", "ocr_image", "document_create", "attachment_read"),
        version = 1,
        enabled = true,
    ),
)
