package com.example.domain.audio

object ChapterTextChunker {

  const val DEFAULT_MAX_CHARS_PER_CHUNK = 1800

  private val PARAGRAPH_SPLIT_REGEX = Regex("\\n\\s*\\n")
  private val WORD_SPLIT_REGEX = Regex("\\s+")

  /**
   * Divide un capítulo largo en fragmentos ordenados respetando el límite máximo de caracteres.
   * Prioriza párrafos completos, luego límites de oración y solo en última instancia espacios entre palabras,
   * sin resumir, reescribir ni omitir texto del EPUB original.
   */
  fun splitChapter(
    chapterText: String,
    maxCharsPerChunk: Int = DEFAULT_MAX_CHARS_PER_CHUNK,
  ): List<String> {
    require(maxCharsPerChunk >= 200) { "maxCharsPerChunk debe ser al menos 200." }
    val normalized = chapterText.trim()
    if (normalized.isEmpty()) return emptyList()

    val paragraphs =
      normalized.split(PARAGRAPH_SPLIT_REGEX).map { it.trim() }.filter { it.isNotEmpty() }

    val result = mutableListOf<String>()
    val currentChunk = StringBuilder()

    fun flushCurrent() {
      if (currentChunk.isNotEmpty()) {
        result += currentChunk.toString().trim()
        currentChunk.setLength(0)
      }
    }

    for (paragraph in paragraphs) {
      if (paragraph.length <= maxCharsPerChunk) {
        val separator = if (currentChunk.isEmpty()) "" else "\n\n"
        if (currentChunk.length + separator.length + paragraph.length <= maxCharsPerChunk) {
          currentChunk.append(separator).append(paragraph)
        } else {
          flushCurrent()
          currentChunk.append(paragraph)
        }
      } else {
        flushCurrent()
        appendOversizedParagraph(paragraph, maxCharsPerChunk, result)
      }
    }

    flushCurrent()
    return result
  }

  fun splitSentences(paragraph: String): List<String> {
    if (paragraph.isBlank()) return emptyList()
    val sentences = mutableListOf<String>()
    val current = StringBuilder()
    var i = 0
    val length = paragraph.length

    while (i < length) {
      val c = paragraph[i]
      current.append(c)

      if (isSentenceTerminator(c)) {
        var j = i + 1
        val extra = StringBuilder()
        while (j < length && (isSentenceTerminator(paragraph[j]) || isClosingPunctuation(paragraph[j]))) {
          extra.append(paragraph[j])
          j++
        }
        if (j == length || paragraph[j].isWhitespace()) {
          current.append(extra)
          val s = current.toString().trim()
          if (s.isNotEmpty()) {
            sentences.add(s)
          }
          current.setLength(0)
          while (j < length && paragraph[j].isWhitespace()) {
            j++
          }
          i = j
        } else {
          i++
        }
      } else {
        i++
      }
    }

    if (current.isNotEmpty()) {
      val last = current.toString().trim()
      if (last.isNotEmpty()) {
        sentences.add(last)
      }
    }
    return sentences
  }

  private fun isSentenceTerminator(c: Char): Boolean =
    c == '.' || c == '!' || c == '?' || c == '…'

  private fun isClosingPunctuation(c: Char): Boolean =
    c == '"' || c == '”' || c == '“' || c == '’' || c == '\'' || c == '»' || c == '«' || c == ')' || c == ']'

  private fun appendOversizedParagraph(
    paragraph: String,
    maxChars: Int,
    out: MutableList<String>,
  ) {
    val sentences = splitSentences(paragraph)
    val buffer = StringBuilder()

    fun flushBuffer() {
      if (buffer.isNotEmpty()) {
        out += buffer.toString().trim()
        buffer.setLength(0)
      }
    }

    for (sentence in sentences) {
      if (sentence.length <= maxChars) {
        val sep = if (buffer.isEmpty()) "" else " "
        if (buffer.length + sep.length + sentence.length <= maxChars) {
          buffer.append(sep).append(sentence)
        } else {
          flushBuffer()
          buffer.append(sentence)
        }
      } else {
        flushBuffer()
        appendOversizedSentence(sentence, maxChars, out)
      }
    }
    flushBuffer()
  }

  private fun appendOversizedSentence(
    sentence: String,
    maxChars: Int,
    out: MutableList<String>,
  ) {
    val words = sentence.split(WORD_SPLIT_REGEX).filter { it.isNotEmpty() }
    val buffer = StringBuilder()

    for (word in words) {
      if (word.length > maxChars) {
        if (buffer.isNotEmpty()) {
          out += buffer.toString().trim()
          buffer.setLength(0)
        }
        var offset = 0
        while (offset < word.length) {
          val end = minOf(offset + maxChars, word.length)
          out += word.substring(offset, end)
          offset = end
        }
      } else {
        val sep = if (buffer.isEmpty()) "" else " "
        if (buffer.length + sep.length + word.length <= maxChars) {
          buffer.append(sep).append(word)
        } else {
          out += buffer.toString().trim()
          buffer.setLength(0)
          buffer.append(word)
        }
      }
    }

    if (buffer.isNotEmpty()) {
      out += buffer.toString().trim()
    }
  }
}
