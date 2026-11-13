package com.example.domain.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class WavInfo(
  val sampleRate: Int,
  val channels: Int,
  val bitsPerSample: Int,
  val dataOffset: Long,
  val dataSizeBytes: Long,
  val durationMs: Long,
)

object WavAudioCodec {

  const val DEFAULT_SAMPLE_RATE = 24_000
  const val DEFAULT_CHANNELS = 1
  const val DEFAULT_BITS_PER_SAMPLE = 16

  fun buildWavHeader(
    pcmDataSize: Long,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    channels: Int = DEFAULT_CHANNELS,
    bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
  ): ByteArray {
    val byteRate = sampleRate.toLong() * channels * (bitsPerSample / 8)
    val blockAlign = channels * (bitsPerSample / 8)
    val riffChunkSize = 36L + pcmDataSize

    val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(riffChunkSize.toInt())
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16) // PCM fmt chunk size
    buffer.putShort(1.toShort()) // AudioFormat = 1 (PCM)
    buffer.putShort(channels.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(byteRate.toInt())
    buffer.putShort(blockAlign.toShort())
    buffer.putShort(bitsPerSample.toShort())
    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(pcmDataSize.toInt())
    return buffer.array()
  }

  fun encodePcmToWav(
    pcmBytes: ByteArray,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    channels: Int = DEFAULT_CHANNELS,
    bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
  ): ByteArray {
    val header = buildWavHeader(pcmBytes.size.toLong(), sampleRate, channels, bitsPerSample)
    return ByteArrayOutputStream(header.size + pcmBytes.size).use { out ->
      out.write(header)
      out.write(pcmBytes)
      out.toByteArray()
    }
  }

  fun writePcmAsWav(
    pcmBytes: ByteArray,
    outputFile: File,
    sampleRate: Int = DEFAULT_SAMPLE_RATE,
    channels: Int = DEFAULT_CHANNELS,
    bitsPerSample: Int = DEFAULT_BITS_PER_SAMPLE,
  ): WavInfo {
    require(pcmBytes.isNotEmpty()) { "Los bytes de audio PCM están vacíos." }
    outputFile.parentFile?.mkdirs()
    val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
    val header = buildWavHeader(pcmBytes.size.toLong(), sampleRate, channels, bitsPerSample)
    BufferedOutputStream(FileOutputStream(tmpFile)).use { out ->
      out.write(header)
      out.write(pcmBytes)
      out.flush()
    }
    replaceFileAtomically(tmpFile, outputFile)
    return readWavInfo(outputFile)
      ?: throw IOException("No se pudo verificar el archivo WAV guardado.")
  }

  fun writeVerifiedWavBytes(wavBytes: ByteArray, outputFile: File): WavInfo {
    outputFile.parentFile?.mkdirs()
    val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
    tmpFile.writeBytes(wavBytes)
    val info = readWavInfo(tmpFile)
    if (info == null) {
      tmpFile.delete()
      throw IOException("Los bytes de audio no forman un archivo WAV válido.")
    }
    replaceFileAtomically(tmpFile, outputFile)
    return info
  }

  fun isValidWavFile(file: File): Boolean = readWavInfo(file) != null

  fun readWavInfo(file: File): WavInfo? {
    if (!file.exists() || !file.isFile || file.length() < 44L) return null
    return runCatching {
        BufferedInputStream(FileInputStream(file)).use { input ->
          val riffHeader = ByteArray(12)
          if (readFully(input, riffHeader) != 12) return@use null
          if (
            String(riffHeader, 0, 4, Charsets.US_ASCII) != "RIFF" ||
              String(riffHeader, 8, 4, Charsets.US_ASCII) != "WAVE"
          ) {
            return@use null
          }

          var offset = 12L
          var audioFormat = -1
          var channels = -1
          var sampleRate = -1
          var bitsPerSample = -1
          var dataOffset = -1L
          var dataSize = -1L

          val chunkHeader = ByteArray(8)
          while (readFully(input, chunkHeader) == 8) {
            val chunkId = String(chunkHeader, 0, 4, Charsets.US_ASCII)
            val chunkSize =
              ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and
                0xFFFFFFFFL
            offset += 8L

            if (chunkId == "fmt " && chunkSize >= 16L) {
              val fmtBytes = ByteArray(16)
              if (readFully(input, fmtBytes) != 16) return@use null
              val fmtBuf = ByteBuffer.wrap(fmtBytes).order(ByteOrder.LITTLE_ENDIAN)
              audioFormat = fmtBuf.short.toInt() and 0xFFFF
              channels = fmtBuf.short.toInt() and 0xFFFF
              sampleRate = fmtBuf.int
              fmtBuf.int // byteRate
              fmtBuf.short // blockAlign
              bitsPerSample = fmtBuf.short.toInt() and 0xFFFF
              val remaining = chunkSize - 16L
              if (remaining > 0L) skipFully(input, remaining)
              offset += chunkSize
            } else if (chunkId == "data") {
              dataOffset = offset
              dataSize = chunkSize
              break
            } else {
              skipFully(input, chunkSize)
              offset += chunkSize
            }

            if (chunkSize % 2L == 1L) {
              skipFully(input, 1L)
              offset += 1L
            }
          }

          if (audioFormat != 1 || channels <= 0 || sampleRate <= 0 || bitsPerSample <= 0) {
            return@use null
          }
          if (dataOffset < 0L || dataSize <= 0L) return@use null
          if (file.length() < dataOffset + dataSize) return@use null

          val byteRate = sampleRate.toLong() * channels * (bitsPerSample / 8)
          if (byteRate <= 0L) return@use null
          val durationMs = (dataSize * 1000L) / byteRate

          WavInfo(
            sampleRate = sampleRate,
            channels = channels,
            bitsPerSample = bitsPerSample,
            dataOffset = dataOffset,
            dataSizeBytes = dataSize,
            durationMs = durationMs,
          )
        }
      }
      .getOrNull()
  }

  fun mergeWavFiles(inputs: List<File>, outputFile: File): WavInfo {
    require(inputs.isNotEmpty()) { "Se requiere al menos un archivo WAV para combinar." }
    val infos =
      inputs.map { file ->
        readWavInfo(file)
          ?: throw IOException("El fragmento WAV ${file.name} está dañado o incompleto.")
      }
    val base = infos.first()
    for (info in infos) {
      if (
        info.sampleRate != base.sampleRate ||
          info.channels != base.channels ||
          info.bitsPerSample != base.bitsPerSample
      ) {
        throw IOException("Los fragmentos WAV tienen formatos de audio incompatibles.")
      }
    }

    outputFile.parentFile?.mkdirs()
    val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
    val totalDataBytes = infos.sumOf { it.dataSizeBytes }
    val header =
      buildWavHeader(
        pcmDataSize = totalDataBytes,
        sampleRate = base.sampleRate,
        channels = base.channels,
        bitsPerSample = base.bitsPerSample,
      )

    BufferedOutputStream(FileOutputStream(tmpFile)).use { out ->
      out.write(header)
      val buffer = ByteArray(16 * 1024)
      for ((index, file) in inputs.withIndex()) {
        val info = infos[index]
        BufferedInputStream(FileInputStream(file)).use { input ->
          skipFully(input, info.dataOffset)
          var remaining = info.dataSizeBytes
          while (remaining > 0L) {
            val toRead = minOf(buffer.size.toLong(), remaining).toInt()
            val read = input.read(buffer, 0, toRead)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
          }
        }
      }
      out.flush()
    }

    replaceFileAtomically(tmpFile, outputFile)
    return readWavInfo(outputFile)
      ?: throw IOException("No se pudo verificar el archivo WAV combinado.")
  }

  fun mergeWavFilesToStream(inputs: List<File>, outputStream: OutputStream): Long {
    require(inputs.isNotEmpty()) { "No hay archivos WAV para exportar." }
    val infos =
      inputs.map { file ->
        readWavInfo(file)
          ?: throw IOException("El archivo WAV ${file.name} está dañado o no existe.")
      }
    val base = infos.first()
    for (info in infos) {
      if (
        info.sampleRate != base.sampleRate ||
          info.channels != base.channels ||
          info.bitsPerSample != base.bitsPerSample
      ) {
        throw IOException("Los capítulos tienen formatos de audio incompatibles.")
      }
    }

    val totalDataBytes = infos.sumOf { it.dataSizeBytes }
    val header =
      buildWavHeader(
        pcmDataSize = totalDataBytes,
        sampleRate = base.sampleRate,
        channels = base.channels,
        bitsPerSample = base.bitsPerSample,
      )
    val bufferedOut = BufferedOutputStream(outputStream)
    bufferedOut.write(header)

    val buffer = ByteArray(16 * 1024)
    for ((index, file) in inputs.withIndex()) {
      val info = infos[index]
      BufferedInputStream(FileInputStream(file)).use { input ->
        skipFully(input, info.dataOffset)
        var remaining = info.dataSizeBytes
        while (remaining > 0L) {
          val toRead = minOf(buffer.size.toLong(), remaining).toInt()
          val read = input.read(buffer, 0, toRead)
          if (read <= 0) break
          bufferedOut.write(buffer, 0, read)
          remaining -= read
        }
      }
    }
    bufferedOut.flush()
    return 44L + totalDataBytes
  }

  private fun replaceFileAtomically(tmpFile: File, targetFile: File) {
    if (targetFile.exists()) {
      targetFile.delete()
    }
    if (!tmpFile.renameTo(targetFile)) {
      tmpFile.copyTo(targetFile, overwrite = true)
      tmpFile.delete()
    }
  }

  private fun readFully(input: InputStream, dest: ByteArray): Int {
    var total = 0
    while (total < dest.size) {
      val read = input.read(dest, total, dest.size - total)
      if (read < 0) break
      total += read
    }
    return total
  }

  private fun skipFully(input: InputStream, bytesToSkip: Long) {
    var remaining = bytesToSkip
    while (remaining > 0L) {
      val skipped = input.skip(remaining)
      if (skipped > 0L) {
        remaining -= skipped
      } else {
        if (input.read() < 0) break
        remaining -= 1L
      }
    }
  }
}
