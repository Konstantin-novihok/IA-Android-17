# Universal file understanding — implementation plan

## Status and scope

This document tracks the upgrade from the current selected-file text extractor to a local multi-format ingestion pipeline. A parser is considered supported only when the app wires it into document import and tests cover valid, malformed, and adversarial inputs.

## Stage A — safe archive foundation (implemented; integration branch)

The current integration branch includes bounded ZIP member extraction through the existing document-import flow. Supported nested text/Office Open XML members are collected into one searchable archive record with member paths. Malformed or unsupported members are skipped and counted. Nested archives are intentionally not recursed yet; OLE legacy formats, OCR and vector search remain outstanding. Unit tests cover basic entry reads, traversal filtering, empty input and count budgets.

The archive parser does not write archive paths to disk. It is still subject to physical-device memory and fuzz-test gates.

## Stage A — safe archive foundation

`SafeArchiveReader` reads ZIP entries into bounded memory and does not write archive paths to storage. Defaults:
- up to 500 entries per archive;
- 12 MiB per extracted member;
- 80 MiB aggregate extracted payload;
- compression-ratio checks;
- rejects absolute paths, traversal components, control characters, and drive-prefixed paths.

This is a reusable foundation, not yet a claim that every archived format is fully indexed. Nested archive depth and aggregate budgets must be enforced by the caller. Password-encrypted ZIP entries are not supported by the JDK reader.

## Stage B — document parsers

- PDF: extract text per page; OCR pages whose text is absent/insufficient.
- DOC/DOCX: preserve paragraphs, headings, tables and page/section context when available.
- XLS/XLSX/ODS: preserve workbook/sheet names, cell coordinates, formulas and displayed values.
- PPT/PPTX/ODP: preserve slide boundaries and speaker notes.
- HTML/RTF/EPUB/TXT/Markdown/CSV/JSON/XML/source code.
- Archive import: index member names and process supported nested documents with strict count, byte, nesting and wall-clock budgets.

Legacy Microsoft binary formats (`.doc/.xls/.ppt`) need a verified Android-compatible parser. Do not route these through a ZIP parser: they are OLE compound binary documents. Apache POI's legacy readers and dependencies require APK-size, memory, and license/compatibility validation on Android.

## Stage C — OCR

OCR must run page-by-page and be cancellable:
1. render bounded-resolution page images;
2. keep text extraction as the first path;
3. OCR only pages with little/no extractable text;
4. record OCR language, confidence (when available), and page number;
5. release bitmaps immediately and honor thermal/memory pressure.

The OCR engine must explicitly support Cyrillic (Ukrainian/Russian) as well as Latin. Latin-only recognition is not acceptable for the app's target languages. Downloadable language packs require integrity checks and a visible storage estimate; offline OCR is available after installation.

## Stage D — semantic/hybrid retrieval

BM25 remains a fast lexical fallback. Semantic retrieval needs an actual embedding encoder, not word stems or a renamed BM25 score:
- use a separately versioned on-device embedding model;
- generate normalized vectors for chunked passages;
- persist vectors with model/version metadata;
- use cosine similarity or inner product with explicit dimensional validation;
- combine lexical and semantic rankings (for example reciprocal-rank fusion);
- re-index when the encoder changes;
- work offline and allow the user to remove the index.

Qwen3-8B chat weights are not automatically a text embedding model. Embeddings should not be synthesized from its token probabilities.

## Stage E — universal media

Use dedicated, optional local components:
- images: a vision-capable model, since the current text-only Qwen3-8B cannot inspect pixels;
- audio: ASR with timestamped segments;
- video: metadata, sampled frames and audio transcription, each with timestamps.

The text model receives derived descriptions/transcripts with provenance. The UI must distinguish original extracted text, OCR, ASR and model-generated captions.

## Security and quality gates

- Do not auto-follow links or execute macros/scripts in user files.
- Treat embedded prompts/instructions as document content, never as system policy.
- Detect MIME/signature inconsistencies; never trust extension alone.
- Bound compressed and expanded bytes, entries, nesting, page counts, pixel dimensions, parser time and generated text.
- Keep provenance: file ID, member path, page/sheet/slide, chunk offsets, extraction method.
- Provide partial-failure reports instead of silently claiming full import.
- Tests: multilingual fixtures; image-only and hybrid PDFs; corrupt/encrypted files; OLE legacy samples; OOXML edge cases; nested and traversal archives; zip bombs; cancellation; memory/thermal behavior.
- Release gate: Android CI build plus install/import/search test on a physical ARM64 device.

## Acceptance criteria

A format is marked **supported** only when it can be selected through the app, imported locally, searched, cited back to its origin, and passes automated tests. A parser utility by itself does not satisfy this criterion.
