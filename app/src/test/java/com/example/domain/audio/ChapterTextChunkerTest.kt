package com.example.domain.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChapterTextChunkerTest {

  @Test
  fun `splitChapter preserves all words in exact order across paragraphs`() {
    val paragraphs =
      (1..12).map { i ->
        "Párrafo número $i. Este pasaje contiene varias oraciones largas sobre la navegación antigua y las cartas marinas del Atlántico sur."
      }
    val original = paragraphs.joinToString("\n\n")

    val chunks = ChapterTextChunker.splitChapter(original, maxCharsPerChunk = 300)

    assertTrue(chunks.size >= 4)
    assertTrue(chunks.all { it.length <= 300 })

    val originalWords = original.split(Regex("\\s+")).filter { it.isNotBlank() }
    val chunkedWords = chunks.joinToString(" ").split(Regex("\\s+")).filter { it.isNotBlank() }
    assertEquals(originalWords, chunkedWords)
  }

  @Test
  fun `splitChapter splits oversized paragraph by sentence boundaries without cutting phrases`() {
    val s1 = "Primera oración completa con sentido narrativo claro y suficiente longitud para probar límites."
    val s2 = "Segunda oración independiente que tampoco debe cortarse por la mitad al dividir el párrafo."
    val s3 = "Tercera oración final que cierra el bloque literario sin perder ninguna palabra."
    val paragraph = "$s1 $s2 $s3"

    val chunks = ChapterTextChunker.splitChapter(paragraph, maxCharsPerChunk = 200)

    assertEquals(2, chunks.size)
    assertEquals("$s1 $s2", chunks[0])
    assertEquals(s3, chunks[1])
  }

  @Test
  fun `splitChapter handles empty text safely`() {
    assertTrue(ChapterTextChunker.splitChapter("   \n\n  ").isEmpty())
  }
}
