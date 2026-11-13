package com.example.epub

object HtmlTextExtractor {

  private val COMMENT_REGEX = Regex("<!--.*?-->", setOf(RegexOption.DOT_MATCHES_ALL))
  private val XML_DECL_REGEX = Regex("<\\?xml[^>]*\\?>", setOf(RegexOption.IGNORE_CASE))
  private val DOCTYPE_REGEX = Regex("<!DOCTYPE[^>]*>", setOf(RegexOption.IGNORE_CASE))
  private val STRIP_BLOCK_REGEX =
    Regex(
      "<(head|script|style|noscript|svg)\\b[^>]*>.*?</\\1>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  private val HEADING_REGEX =
    Regex(
      "<(h[1-3])\\b[^>]*>(.*?)</\\1>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  private val TITLE_TAG_REGEX =
    Regex(
      "<title\\b[^>]*>(.*?)</title>",
      setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
  private val DOUBLE_NEWLINE_TAGS =
    Regex(
      "</?(p|div|h[1-6]|blockquote|section|article|header|footer|pre)\\b[^>]*>|<hr\\b[^>]*/?>",
      setOf(RegexOption.IGNORE_CASE),
    )
  private val SINGLE_NEWLINE_TAGS =
    Regex("<br\\b[^>]*/?>|</?(tr|li|dt|dd)\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
  private val LIST_ITEM_OPEN = Regex("<li\\b[^>]*>", setOf(RegexOption.IGNORE_CASE))
  private val ANY_TAG_REGEX = Regex("<[^>]+>")
  private val NUMERIC_HEX_ENTITY = Regex("&#[xX]([0-9a-fA-F]+);")
  private val NUMERIC_DEC_ENTITY = Regex("&#([0-9]+);")
  private val NAMED_ENTITY = Regex("&([a-zA-Z]+);")
  private val HORIZONTAL_WS = Regex("[ \\t\\x0B\\f\\r]+")
  private val EXCESS_NEWLINES = Regex("\\n{3,}")
  private val WORD_SPLIT_REGEX = Regex("\\s+")

  private val NAMED_ENTITIES =
    mapOf(
      "nbsp" to " ",
      "amp" to "&",
      "lt" to "<",
      "gt" to ">",
      "quot" to "\"",
      "apos" to "'",
      "rsquo" to "’",
      "lsquo" to "‘",
      "rdquo" to "”",
      "ldquo" to "“",
      "mdash" to "—",
      "ndash" to "–",
      "hellip" to "…",
      "laquo" to "«",
      "raquo" to "»",
      "bull" to "•",
      "middot" to "·",
      "copy" to "©",
      "reg" to "®",
      "trade" to "™",
      "euro" to "€",
      "pound" to "£",
      "iquest" to "¿",
      "iexcl" to "¡",
      "ordf" to "ª",
      "ordm" to "º",
      "aacute" to "á",
      "eacute" to "é",
      "iacute" to "í",
      "oacute" to "ó",
      "uacute" to "ú",
      "Aacute" to "Á",
      "Eacute" to "É",
      "Iacute" to "Í",
      "Oacute" to "Ó",
      "Uacute" to "Ú",
      "ntilde" to "ñ",
      "Ntilde" to "Ñ",
      "uuml" to "ü",
      "Uuml" to "Ü",
      "ccedil" to "ç",
      "Ccedil" to "Ç",
      "agrave" to "à",
      "egrave" to "è",
      "igrave" to "ì",
      "ograve" to "ò",
      "ugrave" to "ù",
      "Agrave" to "À",
      "Egrave" to "È",
      "Igrave" to "Ì",
      "Ograve" to "Ò",
      "Ugrave" to "Ù",
      "acirc" to "â",
      "ecirc" to "ê",
      "icirc" to "î",
      "ocirc" to "ô",
      "ucirc" to "û",
      "deg" to "°",
      "sect" to "§",
      "para" to "¶",
      "times" to "×",
      "divide" to "÷",
    )

  fun extractHeading(rawHtml: String): String? {
    val body = COMMENT_REGEX.replace(rawHtml, " ")
    val headingMatch = HEADING_REGEX.find(body)
    if (headingMatch != null) {
      val clean = cleanInline(headingMatch.groupValues[2])
      if (clean.isNotBlank() && clean.length <= 140) return clean
    }
    val titleMatch = TITLE_TAG_REGEX.find(body)
    if (titleMatch != null) {
      val clean = cleanInline(titleMatch.groupValues[1])
      if (clean.isNotBlank() && clean.length <= 140) return clean
    }
    return null
  }

  fun extractText(rawHtml: String): String {
    var text = rawHtml
    text = XML_DECL_REGEX.replace(text, "")
    text = DOCTYPE_REGEX.replace(text, "")
    text = COMMENT_REGEX.replace(text, "")
    text = STRIP_BLOCK_REGEX.replace(text, " ")
    text = LIST_ITEM_OPEN.replace(text, "\n• ")
    text = SINGLE_NEWLINE_TAGS.replace(text, "\n")
    text = DOUBLE_NEWLINE_TAGS.replace(text, "\n\n")
    text = ANY_TAG_REGEX.replace(text, "")
    text = decodeEntities(text)

    val lines =
      text
        .split('\n')
        .map { line -> HORIZONTAL_WS.replace(line, " ").trim() }
        .joinToString("\n")

    return EXCESS_NEWLINES.replace(lines, "\n\n").trim()
  }

  fun cleanInline(rawHtml: String): String {
    val stripped = ANY_TAG_REGEX.replace(rawHtml, " ")
    val decoded = decodeEntities(stripped)
    return decoded.replace('\n', ' ').let { HORIZONTAL_WS.replace(it, " ") }.trim()
  }

  fun countWords(plainText: String): Int {
    val trimmed = plainText.trim()
    if (trimmed.isEmpty()) return 0
    return trimmed.split(WORD_SPLIT_REGEX).count { it.isNotBlank() }
  }

  fun decodeEntities(input: String): String {
    var result =
      NUMERIC_HEX_ENTITY.replace(input) { match ->
        val codePoint = match.groupValues[1].toIntOrNull(16)
        if (codePoint != null && codePoint in 1..0x10FFFF) {
          String(Character.toChars(codePoint))
        } else {
          match.value
        }
      }
    result =
      NUMERIC_DEC_ENTITY.replace(result) { match ->
        val codePoint = match.groupValues[1].toIntOrNull()
        if (codePoint != null && codePoint in 1..0x10FFFF) {
          String(Character.toChars(codePoint))
        } else {
          match.value
        }
      }
    result =
      NAMED_ENTITY.replace(result) { match ->
        NAMED_ENTITIES[match.groupValues[1]] ?: match.value
      }
    return result
  }
}
