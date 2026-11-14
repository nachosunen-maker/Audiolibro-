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
import kotlin.math.max
import kotlin.math.roundToInt

data class WavInfo(
  val sampleRate: Int,
  val channels: Int,
  val bitsPerSample: Int,
  val dataOffset: Long,
  val dataSizeBytes: Long,
  val durationMs: Long,
)

object WavAudioCodec {

  const val DEFAULT_SAMPLE_RATE = 22_050
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

    outputFile.parentFile?.mkdirs()
    val tmpFile = File(outputFile.parentFile, "${outputFile.name}.tmp")
    BufferedOutputStream(FileOutputStream(tmpFile)).use { out ->
      writeMergedWavs(inputs, infos, out)
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

    val bufferedOut = BufferedOutputStream(outputStream)
    val totalBytes = writeMergedWavs(inputs, infos, bufferedOut)
    bufferedOut.flush()
    return totalBytes
  }

  private fun writeMergedWavs(
    inputs: List<File>,
    infos: List<WavInfo>,
    out: OutputStream,
  ): Long {
    val base = infos.first()
    val uniformFormat =
      infos.all {
        it.sampleRate == base.sampleRate &&
          it.channels == base.channels &&
          it.bitsPerSample == base.bitsPerSample
      }

    if (uniformFormat) {
      val totalDataBytes = infos.sumOf { it.dataSizeBytes }
      val header =
        buildWavHeader(
          pcmDataSize = totalDataBytes,
          sampleRate = base.sampleRate,
          channels = base.channels,
          bitsPerSample = base.bitsPerSample,
        )
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
      return 44L + totalDataBytes
    }

    // Heterogeneous sample rates / channel counts (e.g. legacy 24 kHz chapters mixed with 22.05 kHz):
    // normalize safely via streaming linear PCM-16 mono resampling so existing user audio stays playable.
    for (info in infos) {
      if (info.bitsPerSample != 16 || info.channels !in 1..2) {
        throw IOException(
          "No se pudo normalizar un archivo WAV (${info.bitsPerSample} bits, ${info.channels} canales)."
        )
      }
    }

    val targetSampleRate = DEFAULT_SAMPLE_RATE
    val targetChannels = DEFAULT_CHANNELS
    val frameCounts =
      infos.map { info ->
        val inFrames = info.dataSizeBytes / (info.channels * 2L)
        if (inFrames <= 0L) 0L
        else if (info.sampleRate == targetSampleRate) inFrames
        else max(1L, (inFrames * targetSampleRate.toLong()) / info.sampleRate.toLong())
      }
    val totalDataBytes = frameCounts.sumOf { it * targetChannels * 2L }

    out.write(
      buildWavHeader(
        pcmDataSize = totalDataBytes,
        sampleRate = targetSampleRate,
        channels = targetChannels,
        bitsPerSample = DEFAULT_BITS_PER_SAMPLE,
      )
    )

    for ((index, file) in inputs.withIndex()) {
      val info = infos[index]
      val outFrames = frameCounts[index]
      if (outFrames <= 0L) continue
      streamResampledMonoPcm16(
        file = file,
        info = info,
        targetFrames = outFrames,
        out = out,
      )
    }

    return 44L + totalDataBytes
  }

  private fun streamResampledMonoPcm16(
    file: File,
    info: WavInfo,
    targetFrames: Long,
    out: OutputStream,
  ) {
    val inChannels = info.channels
    val inBytesPerFrame = inChannels * 2
    val inFrames = info.dataSizeBytes / inBytesPerFrame
    if (inFrames <= 0L || targetFrames <= 0L) return

    BufferedInputStream(FileInputStream(file), 32 * 1024).use { input ->
      skipFully(input, info.dataOffset)

      val frameBytes = ByteArray(inBytesPerFrame)
      fun readNextMonoFrame(): Int {
        if (readFully(input, frameBytes) < inBytesPerFrame) return 0
        val s0 =
          ((frameBytes[1].toInt() shl 8) or (frameBytes[0].toInt() and 0xFF)).toShort().toInt()
        if (inChannels == 1) return s0
        val s1 =
          ((frameBytes[3].toInt() shl 8) or (frameBytes[2].toInt() and 0xFF)).toShort().toInt()
        return (s0 + s1) / 2
      }

      val outChunkBuf = ByteArray(16 * 1024)
      var outChunkPos = 0

      fun writeMonoSample(sample: Int) {
        val clamped = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        outChunkBuf[outChunkPos++] = (clamped and 0xFF).toByte()
        outChunkBuf[outChunkPos++] = ((clamped shr 8) and 0xFF).toByte()
        if (outChunkPos == outChunkBuf.size) {
          out.write(outChunkBuf, 0, outChunkPos)
          outChunkPos = 0
        }
      }

      if (inFrames == targetFrames && inChannels == 1) {
        val copyBuf = ByteArray(16 * 1024)
        var remaining = targetFrames * 2L
        while (remaining > 0L) {
          val toRead = minOf(copyBuf.size.toLong(), remaining).toInt()
          val read = input.read(copyBuf, 0, toRead)
          if (read <= 0) break
          out.write(copyBuf, 0, read)
          remaining -= read
        }
        return
      }

      var f0 = readNextMonoFrame()
      var f1 = if (inFrames > 1L) readNextMonoFrame() else f0
      var fetchedLastIdx = if (inFrames > 1L) 1L else 0L
      val step =
        if (targetFrames > 1L && inFrames > 1L) {
          (inFrames - 1L).toDouble() / (targetFrames - 1L).toDouble()
        } else {
          0.0
        }

      for (outIdx in 0L until targetFrames) {
        val exactPos = outIdx.toDouble() * step
        val idx0 = exactPos.toLong().coerceIn(0L, (inFrames - 1L).coerceAtLeast(0L))
        val frac = exactPos - idx0.toDouble()

        while (fetchedLastIdx < idx0 + 1L && fetchedLastIdx < inFrames - 1L) {
          f0 = f1
          f1 = readNextMonoFrame()
          fetchedLastIdx++
        }

        val s0 = if (idx0 == fetchedLastIdx) f1 else f0
        val s1 = if (idx0 + 1L <= fetchedLastIdx) f1 else s0
        val interpolated = (s0.toDouble() + (s1 - s0).toDouble() * frac).roundToInt()
        writeMonoSample(interpolated)
      }

      if (outChunkPos > 0) {
        out.write(outChunkBuf, 0, outChunkPos)
      }
    }
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
