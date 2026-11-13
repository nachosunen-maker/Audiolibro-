package com.example.epub

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EpubParserTest {

  @get:Rule val tempFolder = TemporaryFolder()

  private val parser = EpubParser()

  @Test
  fun `parseFile extracts EPUB 2 metadata cover NCX chapters and clean Spanish text`() {
    val coverBytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())
    val epubFile =
      createTestEpub(
        "libro_prueba.epub",
        mapOf(
          "mimetype" to "application/epub+zip".toByteArray(),
          "META-INF/container.xml" to
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """
              .trimIndent()
              .toByteArray(),
          "OEBPS/content.opf" to
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="BookId">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>El Viaje del Alba</dc:title>
                <dc:creator>Elena Márquez</dc:creator>
                <dc:language>es-ES</dc:language>
                <dc:publisher>Editorial Sur</dc:publisher>
                <dc:description>&lt;p&gt;Una novela sobre &lt;b&gt;navegantes&lt;/b&gt;.&lt;/p&gt;</dc:description>
                <meta name="cover" content="cover-img"/>
              </metadata>
              <manifest>
                <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
                <item id="cover-img" href="Images/portada.jpg" media-type="image/jpeg"/>
                <item id="ch1" href="Text/cap1.xhtml" media-type="application/xhtml+xml"/>
                <item id="ch2" href="Text/cap2.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine toc="ncx">
                <itemref idref="ch1"/>
                <itemref idref="ch2"/>
              </spine>
            </package>
            """
              .trimIndent()
              .toByteArray(),
          "OEBPS/toc.ncx" to
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
              <navMap>
                <navPoint id="np-1" playOrder="1">
                  <navLabel><text>Prólogo en el puerto</text></navLabel>
                  <content src="Text/cap1.xhtml#inicio"/>
                </navPoint>
                <navPoint id="np-2" playOrder="2">
                  <navLabel><text>La travesía nocturna</text></navLabel>
                  <content src="Text/cap2.xhtml"/>
                </navPoint>
              </navMap>
            </ncx>
            """
              .trimIndent()
              .toByteArray(),
          "OEBPS/Images/portada.jpg" to coverBytes,
          "OEBPS/Text/cap1.xhtml" to
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <html xmlns="http://www.w3.org/1999/xhtml">
              <head><title>Cap 1</title><style>p { color: black; }</style></head>
              <body>
                <script>alert('ignore');</script>
                <p>&iquest;Qui&eacute;n zarp&oacute; al alba&mdash;pregunt&oacute; el capit&aacute;n?</p>
                <p>El mar estaba en calma.</p>
              </body>
            </html>
            """
              .trimIndent()
              .toByteArray(),
          "OEBPS/Text/cap2.xhtml" to
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body>
                <h1>Capítulo Dos</h1>
                <p>Las velas se hincharon con el viento del norte.</p>
              </body>
            </html>
            """
              .trimIndent()
              .toByteArray(),
        ),
      )

    val parsed = parser.parseFile(epubFile, "fallback.epub")

    assertEquals("El Viaje del Alba", parsed.title)
    assertEquals("Elena Márquez", parsed.author)
    assertEquals("Español", parsed.language)
    assertEquals("Editorial Sur", parsed.publisher)
    assertEquals("Una novela sobre navegantes.", parsed.description)
    assertNotNull(parsed.coverBytes)
    assertArrayEquals(coverBytes, parsed.coverBytes)
    assertEquals(2, parsed.chapters.size)

    val first = parsed.chapters[0]
    assertEquals(1, first.chapterIndex)
    assertEquals("Prólogo en el puerto", first.title)
    assertTrue(first.contentText.contains("¿Quién zarpó al alba—preguntó el capitán?"))
    assertTrue(first.contentText.contains("El mar estaba en calma."))
    assertFalse(first.contentText.contains("alert"))
    assertFalse(first.contentText.contains("color: black"))

    val second = parsed.chapters[1]
    assertEquals(2, second.chapterIndex)
    assertEquals("La travesía nocturna", second.title)
    assertTrue(parsed.totalWords > 10)
  }

  @Test
  fun `parseFile supports EPUB 3 nav toc and skips empty cover page`() {
    val epubFile =
      createTestEpub(
        "epub3.epub",
        mapOf(
          "META-INF/container.xml" to
            """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="package.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """
              .trimIndent()
              .toByteArray(),
          "package.opf" to
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Cuentos Breves</dc:title>
                <dc:language>es</dc:language>
              </metadata>
              <manifest>
                <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
                <item id="cover-page" href="cover.xhtml" media-type="application/xhtml+xml"/>
                <item id="c1" href="c1.xhtml" media-type="application/xhtml+xml"/>
              </manifest>
              <spine>
                <itemref idref="cover-page"/>
                <itemref idref="c1"/>
              </spine>
            </package>
            """
              .trimIndent()
              .toByteArray(),
          "nav.xhtml" to
            """
            <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
              <body>
                <nav epub:type="toc">
                  <ol>
                    <li><a href="c1.xhtml">Historia del reloj</a></li>
                  </ol>
                </nav>
              </body>
            </html>
            """
              .trimIndent()
              .toByteArray(),
          "cover.xhtml" to
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><img src="cover.png" alt="Portada"/></body>
            </html>
            """
              .trimIndent()
              .toByteArray(),
          "c1.xhtml" to
            """
            <html xmlns="http://www.w3.org/1999/xhtml">
              <body><p>El reloj marcó la medianoche exacta.</p></body>
            </html>
            """
              .trimIndent()
              .toByteArray(),
        ),
      )

    val parsed = parser.parseFile(epubFile, "fallback.epub")
    assertEquals(1, parsed.chapters.size)
    assertEquals("Historia del reloj", parsed.chapters[0].title)
    assertEquals("Autor desconocido", parsed.author)
  }

  @Test(expected = EpubParseException::class)
  fun `parseFile rejects DRM encrypted xhtml`() {
    val epubFile =
      createTestEpub(
        "drm.epub",
        mapOf(
          "META-INF/container.xml" to
            """
            <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <rootfiles>
                <rootfile full-path="content.opf" media-type="application/oebps-package+xml"/>
              </rootfiles>
            </container>
            """
              .trimIndent()
              .toByteArray(),
          "META-INF/encryption.xml" to
            """
            <encryption xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
              <EncryptedData xmlns="http://www.w3.org/2001/04/xmlenc#">
                <CipherData><CipherReference URI="cap1.xhtml"/></CipherData>
              </EncryptedData>
            </encryption>
            """
              .trimIndent()
              .toByteArray(),
          "content.opf" to
            """
            <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>Bloqueado</dc:title>
              </metadata>
              <manifest><item id="c1" href="cap1.xhtml" media-type="application/xhtml+xml"/></manifest>
              <spine><itemref idref="c1"/></spine>
            </package>
            """
              .trimIndent()
              .toByteArray(),
          "cap1.xhtml" to "encrypted-bytes".toByteArray(),
        ),
      )

    parser.parseFile(epubFile, "drm.epub")
  }

  @Test(expected = EpubParseException::class)
  fun `parseFile rejects invalid non-zip file`() {
    val badFile = tempFolder.newFile("no_es_epub.epub")
    badFile.writeText("esto no es un archivo zip")
    parser.parseFile(badFile, "no_es_epub.epub")
  }

  private fun createTestEpub(name: String, entries: Map<String, ByteArray>): File {
    val file = tempFolder.newFile(name)
    val baos = ByteArrayOutputStream()
    ZipOutputStream(baos).use { zos ->
      for ((path, content) in entries) {
        zos.putNextEntry(ZipEntry(path))
        zos.write(content)
        zos.closeEntry()
      }
    }
    file.writeBytes(baos.toByteArray())
    return file
  }
}
