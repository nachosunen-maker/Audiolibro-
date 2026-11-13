package com.example.epub

import com.example.domain.model.NarrationEstimator
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

class EpubParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class EpubParsedChapter(
  val chapterIndex: Int,
  val title: String,
  val contentText: String,
  val wordCount: Int,
  val characterCount: Int,
  val estimatedMinutes: Int,
)

data class EpubParsedBook(
  val title: String,
  val author: String,
  val language: String,
  val publisher: String,
  val description: String,
  val coverBytes: ByteArray?,
  val chapters: List<EpubParsedChapter>,
  val totalWords: Int,
  val totalCharacters: Int,
  val estimatedMinutes: Int,
)

private data class ManifestItem(
  val id: String,
  val resolvedPath: String,
  val mediaType: String,
  val properties: Set<String>,
)

class EpubParser {

  fun parseStream(
    inputStream: InputStream,
    fallbackTitle: String,
    tempCacheDir: File,
  ): EpubParsedBook {
    val tempFile = File.createTempFile("audiolibro_import_", ".epub", tempCacheDir)
    return try {
      tempFile.outputStream().use { out -> inputStream.copyTo(out) }
      parseFile(tempFile, fallbackTitle)
    } finally {
      tempFile.delete()
    }
  }

  fun parseFile(epubFile: File, fallbackTitle: String): EpubParsedBook {
    if (!epubFile.exists() || epubFile.length() == 0L) {
      throw EpubParseException("El archivo EPUB está vacío o no se pudo leer.")
    }

    val zipFile =
      try {
        ZipFile(epubFile)
      } catch (e: ZipException) {
        throw EpubParseException("El archivo seleccionado no es un EPUB válido.", e)
      } catch (e: Exception) {
        throw EpubParseException("No se pudo abrir el archivo EPUB.", e)
      }

    return zipFile.use { zip ->
      val entryMap = buildEntryLookup(zip)
      if (entryMap.isEmpty()) {
        throw EpubParseException("El archivo EPUB no contiene archivos internos.")
      }

      val opfPath = resolveOpfPath(zip, entryMap)
      checkDrmProtection(zip, entryMap)

      val opfBytes =
        readEntryBytes(zip, entryMap, opfPath)
          ?: throw EpubParseException("No se encontró el archivo de paquete OPF del libro.")
      val opfDoc = parseXml(opfBytes)
      val opfDir = parentDirectory(opfPath)

      val metadata = parseOpfMetadata(opfDoc)
      val manifest = parseManifest(opfDoc, opfDir)
      val (spineIdRefs, spineTocId) = parseSpine(opfDoc)

      val tocTitles = parseTableOfContents(zip, entryMap, manifest, spineTocId)
      val coverBytes = extractCoverBytes(zip, entryMap, manifest, metadata.coverMetaId)

      val chapters = mutableListOf<EpubParsedChapter>()
      val seenPaths = mutableSetOf<String>()
      var chapterCounter = 0

      for (idRef in spineIdRefs) {
        val item = manifest[idRef] ?: continue
        if (!isReadableTextItem(item)) continue
        if (!seenPaths.add(item.resolvedPath)) continue

        val chapterBytes = readEntryBytes(zip, entryMap, item.resolvedPath) ?: continue
        val rawHtml = decodeHtmlBytes(chapterBytes)
        val extractedText = HtmlTextExtractor.extractText(rawHtml)
        val wordCount = HtmlTextExtractor.countWords(extractedText)
        if (wordCount == 0) continue

        chapterCounter += 1
        val tocTitle = tocTitles[item.resolvedPath]
        val headingTitle = HtmlTextExtractor.extractHeading(rawHtml)
        val resolvedTitle =
          when {
            !tocTitle.isNullOrBlank() -> tocTitle
            !headingTitle.isNullOrBlank() -> headingTitle
            else -> "Capítulo $chapterCounter"
          }

        val charCount = extractedText.length
        chapters +=
          EpubParsedChapter(
            chapterIndex = chapterCounter,
            title = resolvedTitle,
            contentText = extractedText,
            wordCount = wordCount,
            characterCount = charCount,
            estimatedMinutes = NarrationEstimator.estimateMinutes(wordCount),
          )
      }

      if (chapters.isEmpty()) {
        throw EpubParseException(
          "No se pudo extraer texto legible de este EPUB. Puede estar formado solo por imágenes o estar cifrado."
        )
      }

      val totalWords = chapters.sumOf { it.wordCount }
      val totalChars = chapters.sumOf { it.characterCount }
      val totalMinutes = NarrationEstimator.estimateMinutes(totalWords)

      val cleanTitle =
        metadata.title.ifBlank {
          fallbackTitle.removeSuffix(".epub").removeSuffix(".EPUB").ifBlank { "Libro sin título" }
        }

      EpubParsedBook(
        title = cleanTitle,
        author = metadata.author.ifBlank { "Autor desconocido" },
        language = normalizeLanguage(metadata.language),
        publisher = metadata.publisher,
        description = metadata.description,
        coverBytes = coverBytes,
        chapters = chapters,
        totalWords = totalWords,
        totalCharacters = totalChars,
        estimatedMinutes = totalMinutes,
      )
    }
  }

  private fun buildEntryLookup(zip: ZipFile): Map<String, ZipEntry> {
    val map = linkedMapOf<String, ZipEntry>()
    val entries = zip.entries()
    while (entries.hasMoreElements()) {
      val entry = entries.nextElement()
      if (!entry.isDirectory) {
        val normalized = normalizeZipPath(entry.name)
        map[normalized] = entry
      }
    }
    return map
  }

  private fun readEntryBytes(
    zip: ZipFile,
    entryMap: Map<String, ZipEntry>,
    path: String,
  ): ByteArray? {
    val normalized = normalizeZipPath(path)
    val entry =
      entryMap[normalized]
        ?: entryMap.entries.firstOrNull { it.key.equals(normalized, ignoreCase = true) }?.value
        ?: return null
    return zip.getInputStream(entry).use { it.readBytes() }
  }

  private fun resolveOpfPath(zip: ZipFile, entryMap: Map<String, ZipEntry>): String {
    val containerBytes = readEntryBytes(zip, entryMap, "META-INF/container.xml")
    if (containerBytes != null) {
      val doc = parseXml(containerBytes)
      val rootfiles = elementsByLocalName(doc, "rootfile")
      val path =
        rootfiles.firstNotNullOfOrNull { element ->
          element.getAttribute("full-path").takeIf { it.isNotBlank() }
        }
      if (!path.isNullOrBlank()) {
        return normalizeZipPath(path)
      }
    }

    return entryMap.keys.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
      ?: throw EpubParseException(
        "El archivo EPUB no contiene META-INF/container.xml ni ningún paquete OPF."
      )
  }

  private fun checkDrmProtection(zip: ZipFile, entryMap: Map<String, ZipEntry>) {
    if (entryMap.keys.any { it.equals("META-INF/rights.xml", ignoreCase = true) }) {
      throw EpubParseException("Este libro EPUB tiene protección DRM (rights.xml) y no se puede procesar.")
    }
    val encryptionBytes = readEntryBytes(zip, entryMap, "META-INF/encryption.xml") ?: return
    val encDoc =
      try {
        parseXml(encryptionBytes)
      } catch (_: Exception) {
        return
      }
    val cipherReferences = elementsByLocalName(encDoc, "CipherReference")
    val hasEncryptedContent =
      cipherReferences.any { ref ->
        val uri = ref.getAttribute("URI").lowercase()
        uri.endsWith(".xhtml") ||
          uri.endsWith(".html") ||
          uri.endsWith(".htm") ||
          uri.endsWith(".opf")
      }
    if (hasEncryptedContent) {
      throw EpubParseException(
        "El contenido de este libro EPUB está cifrado con DRM y no es posible extraer su texto."
      )
    }
  }

  private data class OpfMetadata(
    val title: String,
    val author: String,
    val language: String,
    val publisher: String,
    val description: String,
    val coverMetaId: String?,
  )

  private fun parseOpfMetadata(opfDoc: Document): OpfMetadata {
    val title =
      elementsByLocalName(opfDoc, "title")
        .firstOrNull { !it.textContent.isNullOrBlank() }
        ?.textContent
        ?.trim()
        .orEmpty()

    val authors =
      elementsByLocalName(opfDoc, "creator")
        .mapNotNull { it.textContent?.trim()?.takeIf(String::isNotBlank) }
        .distinct()
    val author = authors.joinToString(", ")

    val language =
      elementsByLocalName(opfDoc, "language")
        .firstOrNull { !it.textContent.isNullOrBlank() }
        ?.textContent
        ?.trim()
        .orEmpty()

    val publisher =
      elementsByLocalName(opfDoc, "publisher")
        .firstOrNull { !it.textContent.isNullOrBlank() }
        ?.textContent
        ?.trim()
        .orEmpty()

    val rawDescription =
      elementsByLocalName(opfDoc, "description")
        .firstOrNull { !it.textContent.isNullOrBlank() }
        ?.textContent
        ?.trim()
        .orEmpty()
    val description =
      if (rawDescription.isNotBlank()) HtmlTextExtractor.extractText(rawDescription) else ""

    val coverMetaId =
      elementsByLocalName(opfDoc, "meta")
        .firstOrNull { el ->
          el.getAttribute("name").equals("cover", ignoreCase = true) &&
            el.getAttribute("content").isNotBlank()
        }
        ?.getAttribute("content")
        ?.trim()

    return OpfMetadata(
      title = HtmlTextExtractor.decodeEntities(title),
      author = HtmlTextExtractor.decodeEntities(author),
      language = language,
      publisher = HtmlTextExtractor.decodeEntities(publisher),
      description = description,
      coverMetaId = coverMetaId,
    )
  }

  private fun parseManifest(opfDoc: Document, opfDir: String): Map<String, ManifestItem> {
    val manifest = linkedMapOf<String, ManifestItem>()
    val items = elementsByLocalName(opfDoc, "item")
    for (el in items) {
      val id = el.getAttribute("id").trim()
      val href = el.getAttribute("href").trim()
      if (id.isEmpty() || href.isEmpty()) continue
      val mediaType = el.getAttribute("media-type").trim().lowercase()
      val propsRaw = el.getAttribute("properties").trim()
      val props =
        if (propsRaw.isEmpty()) emptySet() else propsRaw.split(Regex("\\s+")).toSet()
      val resolved = resolveRelativePath(opfDir, href)
      manifest[id] = ManifestItem(id = id, resolvedPath = resolved, mediaType = mediaType, properties = props)
    }
    return manifest
  }

  private fun parseSpine(opfDoc: Document): Pair<List<String>, String?> {
    val spineEl = elementsByLocalName(opfDoc, "spine").firstOrNull()
    val tocId = spineEl?.getAttribute("toc")?.trim()?.takeIf { it.isNotEmpty() }
    val idRefs =
      elementsByLocalName(opfDoc, "itemref").mapNotNull { el ->
        el.getAttribute("idref").trim().takeIf { it.isNotEmpty() }
      }
    return idRefs to tocId
  }

  private fun parseTableOfContents(
    zip: ZipFile,
    entryMap: Map<String, ZipEntry>,
    manifest: Map<String, ManifestItem>,
    spineTocId: String?,
  ): Map<String, String> {
    val navItem = manifest.values.firstOrNull { "nav" in it.properties }
    if (navItem != null) {
      val navBytes = readEntryBytes(zip, entryMap, navItem.resolvedPath)
      if (navBytes != null) {
        val navTitles = parseEpub3Nav(navBytes, parentDirectory(navItem.resolvedPath))
        if (navTitles.isNotEmpty()) return navTitles
      }
    }

    val ncxItem =
      (spineTocId?.let { manifest[it] })
        ?: manifest.values.firstOrNull {
          it.mediaType == "application/x-dtbncx+xml" ||
            it.resolvedPath.endsWith(".ncx", ignoreCase = true)
        }
    if (ncxItem != null) {
      val ncxBytes = readEntryBytes(zip, entryMap, ncxItem.resolvedPath)
      if (ncxBytes != null) {
        val ncxTitles = parseEpub2Ncx(ncxBytes, parentDirectory(ncxItem.resolvedPath))
        if (ncxTitles.isNotEmpty()) return ncxTitles
      }
    }

    return emptyMap()
  }

  private fun parseEpub3Nav(navBytes: ByteArray, navDir: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val doc =
      try {
        parseXml(navBytes)
      } catch (_: Exception) {
        return result
      }

    val navElements = elementsByLocalName(doc, "nav")
    val tocNav =
      navElements.firstOrNull { nav ->
        val epubType = nav.getAttribute("epub:type")
        val nsType = nav.getAttributeNS("http://www.idpf.org/2007/ops", "type")
        val role = nav.getAttribute("role")
        epubType.contains("toc", ignoreCase = true) ||
          nsType.contains("toc", ignoreCase = true) ||
          role.contains("doc-toc", ignoreCase = true)
      } ?: navElements.firstOrNull()

    if (tocNav == null) return result

    val anchors = tocNav.getElementsByTagName("*")
    for (i in 0 until anchors.length) {
      val node = anchors.item(i) as? Element ?: continue
      if (localNameOf(node) != "a") continue
      val href = node.getAttribute("href").trim()
      if (href.isEmpty()) continue
      val label = HtmlTextExtractor.cleanInline(node.textContent.orEmpty())
      if (label.isEmpty()) continue
      val targetPath = resolveRelativePath(navDir, href.substringBefore('#'))
      result.putIfAbsent(targetPath, label)
    }
    return result
  }

  private fun parseEpub2Ncx(ncxBytes: ByteArray, ncxDir: String): Map<String, String> {
    val result = linkedMapOf<String, String>()
    val doc =
      try {
        parseXml(ncxBytes)
      } catch (_: Exception) {
        return result
      }

    val navPoints = elementsByLocalName(doc, "navPoint")
    for (navPoint in navPoints) {
      val label =
        directChildByLocalName(navPoint, "navLabel")
          ?.let { directChildByLocalName(it, "text")?.textContent }
          ?.let { HtmlTextExtractor.cleanInline(it) }
          .orEmpty()
      val src =
        directChildByLocalName(navPoint, "content")
          ?.getAttribute("src")
          ?.trim()
          .orEmpty()
      if (label.isNotEmpty() && src.isNotEmpty()) {
        val targetPath = resolveRelativePath(ncxDir, src.substringBefore('#'))
        result.putIfAbsent(targetPath, label)
      }
    }
    return result
  }

  private fun extractCoverBytes(
    zip: ZipFile,
    entryMap: Map<String, ZipEntry>,
    manifest: Map<String, ManifestItem>,
    coverMetaId: String?,
  ): ByteArray? {
    val coverItem =
      manifest.values.firstOrNull { "cover-image" in it.properties }
        ?: coverMetaId?.let { manifest[it] }?.takeIf { it.mediaType.startsWith("image/") }
        ?: manifest.values.firstOrNull {
          it.mediaType.startsWith("image/") &&
            (it.id.contains("cover", ignoreCase = true) ||
              it.resolvedPath.contains("cover", ignoreCase = true))
        }
    return coverItem?.let { readEntryBytes(zip, entryMap, it.resolvedPath) }
  }

  private fun isReadableTextItem(item: ManifestItem): Boolean {
    if ("nav" in item.properties && item.resolvedPath.endsWith("nav.xhtml", ignoreCase = true)) {
      return false
    }
    val type = item.mediaType
    if (
      type == "application/xhtml+xml" ||
        type == "text/html" ||
        type == "application/xml" ||
        type == "text/xml"
    ) {
      return true
    }
    val lower = item.resolvedPath.lowercase()
    return lower.endsWith(".xhtml") || lower.endsWith(".html") || lower.endsWith(".htm")
  }

  private fun decodeHtmlBytes(bytes: ByteArray): String {
    val headProbe = bytes.copyOf(minOf(bytes.size, 512)).toString(Charsets.ISO_8859_1)
    val encodingMatch =
      Regex("""(?:encoding|charset)\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)
        .find(headProbe)
    val charset =
      encodingMatch
        ?.groupValues
        ?.getOrNull(1)
        ?.let { name -> runCatching { Charset.forName(name) }.getOrNull() }
        ?: Charsets.UTF_8
    return String(bytes, charset)
  }

  private fun parseXml(bytes: ByteArray): Document {
    val factory = DocumentBuilderFactory.newInstance()
    factory.isNamespaceAware = true
    runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
    runCatching { factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    runCatching { factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
    factory.isExpandEntityReferences = false
    val builder = factory.newDocumentBuilder()
    return builder.parse(ByteArrayInputStream(bytes))
  }

  private fun elementsByLocalName(doc: Document, localName: String): List<Element> {
    val list = doc.getElementsByTagName("*")
    val result = mutableListOf<Element>()
    for (i in 0 until list.length) {
      val el = list.item(i) as? Element ?: continue
      if (localNameOf(el).equals(localName, ignoreCase = true)) {
        result += el
      }
    }
    return result
  }

  private fun directChildByLocalName(parent: Element, localName: String): Element? {
    val children = parent.childNodes
    for (i in 0 until children.length) {
      val node = children.item(i)
      if (node.nodeType == Node.ELEMENT_NODE && node is Element) {
        if (localNameOf(node).equals(localName, ignoreCase = true)) {
          return node
        }
      }
    }
    return null
  }

  private fun localNameOf(element: Element): String {
    return element.localName ?: element.tagName.substringAfter(':')
  }

  private fun normalizeLanguage(raw: String): String {
    val clean = raw.trim().lowercase()
    return when {
      clean.startsWith("es") -> "Español"
      clean.startsWith("en") -> "Inglés"
      clean.startsWith("fr") -> "Francés"
      clean.startsWith("pt") -> "Portugués"
      clean.startsWith("it") -> "Italiano"
      clean.startsWith("de") -> "Alemán"
      clean.startsWith("ca") -> "Catalán"
      clean.startsWith("gl") -> "Gallego"
      clean.startsWith("eu") -> "Euskera"
      clean.isNotEmpty() -> raw.uppercase()
      else -> "Sin idioma"
    }
  }

  private fun parentDirectory(path: String): String {
    val idx = path.lastIndexOf('/')
    return if (idx >= 0) path.substring(0, idx) else ""
  }

  private fun resolveRelativePath(baseDir: String, rawHref: String): String {
    val decoded = percentDecode(rawHref.substringBefore('#').substringBefore('?'))
    val combined =
      when {
        decoded.startsWith("/") -> decoded.removePrefix("/")
        baseDir.isEmpty() -> decoded
        else -> "$baseDir/$decoded"
      }
    return normalizeZipPath(combined)
  }

  private fun normalizeZipPath(path: String): String {
    val parts = path.replace('\\', '/').split('/')
    val stack = ArrayDeque<String>()
    for (part in parts) {
      when {
        part.isEmpty() || part == "." -> Unit
        part == ".." -> if (stack.isNotEmpty()) stack.removeLast()
        else -> stack.addLast(part)
      }
    }
    return stack.joinToString("/")
  }

  private fun percentDecode(input: String): String {
    if (!input.contains('%')) return input
    val bytes = ArrayList<Byte>(input.length)
    var i = 0
    while (i < input.length) {
      val ch = input[i]
      if (ch == '%' && i + 2 < input.length) {
        val hex = input.substring(i + 1, i + 3).toIntOrNull(16)
        if (hex != null) {
          bytes.add(hex.toByte())
          i += 3
          continue
        }
      }
      val utf8 = ch.toString().toByteArray(Charsets.UTF_8)
      for (b in utf8) bytes.add(b)
      i++
    }
    return bytes.toByteArray().toString(Charsets.UTF_8)
  }
}
