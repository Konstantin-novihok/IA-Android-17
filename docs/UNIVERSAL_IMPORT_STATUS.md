# Universal document ingestion — implementation status

## Shared groundwork
A deterministic extension router and source-coordinate model are available in `docs/ImportPipeline.kt`. The compatibility importer still returns a single `Extracted(name, text, note)`, so per-chunk structured source metadata is not yet persisted.

## Security boundaries and limitations
- ZIP members are read in memory; archive paths are not extracted to disk.
- Existing reader enforces member count, per-entry/aggregate expanded bytes and compression-ratio limits.
- Outer import size and extracted characters are bounded.
- Nested ZIP ingestion is not recursively implemented.
- Office XML extraction is best-effort. Relationships, headers/footers, comments, notes, embedded media, formulas, cell coordinates and full layout are not guaranteed.
- Macros, formulas, scripts and external links are never executed.
- Scanned/mixed PDF OCR is not connected to a runtime OCR engine.
- DOC/XLS/PPT remain unsupported.
- Vector embeddings, ANN indexing, reranking, vision, ASR and video understanding are not implemented.
- CI and physical device verification remain mandatory.

Do not label the plan complete until features have user-visible workflows, failure handling, automated tests, successful CI and device validation. Offline model readiness must be explicit for optional downloaded models.
