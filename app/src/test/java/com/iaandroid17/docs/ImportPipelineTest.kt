package com.iaandroid17.docs
import org.junit.Assert.*
import org.junit.Test
class ImportPipelineTest {
 @Test fun routesCaseInsensitively(){ assertEquals(DocumentKind.PDF,FormatRouter.kind("folder/Report.PDF")); assertEquals(DocumentKind.DOCX,FormatRouter.kind("Archive/contract.docx")); assertEquals(DocumentKind.IMAGE,FormatRouter.kind("photo.JPEG")); assertEquals(DocumentKind.VIDEO,FormatRouter.kind("recording.mkv")); assertEquals(DocumentKind.UNKNOWN,FormatRouter.kind("unknown.payload")) }
 @Test fun distinguishesLegacyOffice(){ assertEquals(DocumentKind.LEGACY_OFFICE,FormatRouter.kind("book.xls")); assertEquals(DocumentKind.UNKNOWN,FormatRouter.kind("payload.bin")) }
 @Test fun provenanceCarriesCoordinates(){ val s=DocumentSource("bundle.zip","docs/a.pdf",page=4); val v=Provenance.wrap(s,"visible text"); assertTrue(v.contains("bundle.zip")); assertTrue(v.contains("docs/a.pdf")); assertTrue(v.contains("страница: 4")); assertTrue(v.endsWith("visible text")) }
 @Test fun budgetValidatesLimits(){ try { ImportBudget(maxDepth=-1); fail("expected validation failure") } catch(_:IllegalArgumentException){} }
}
