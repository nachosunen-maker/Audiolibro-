import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction

abstract class PreparePiperTtsAssetsTask : DefaultTask() {
  @get:Input abstract val aarUrl: Property<String>
  @get:Input abstract val aarSha256: Property<String>
  @get:Input abstract val modelArchiveUrl: Property<String>
  @get:Input abstract val modelArchiveSha256: Property<String>
  @get:OutputFile abstract val aarOutputFile: RegularFileProperty
  @get:OutputDirectory abstract val modelOutputDir: DirectoryProperty

  @TaskAction
  fun prepare() {
    val aarFile = aarOutputFile.get().asFile
    val modelDir = modelOutputDir.get().asFile
    val client =
      HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(30))
        .build()

    fun downloadIfNeeded(url: String, expectedSha256: String, dest: File) {
      if (dest.exists() && dest.length() > 1024L && sha256(dest) == expectedSha256) {
        return
      }
      dest.parentFile.mkdirs()
      val tmp = File(dest.parentFile, "${dest.name}.part")
      println("Downloading $url -> ${dest.absolutePath}")
      val req =
        HttpRequest.newBuilder(URI.create(url))
          .header("User-Agent", "Audiolibro-Gradle-Setup")
          .timeout(Duration.ofMinutes(5))
          .GET()
          .build()
      val resp = client.send(req, HttpResponse.BodyHandlers.ofFile(tmp.toPath()))
      if (resp.statusCode() !in 200..299) {
        tmp.delete()
        error("Failed downloading $url: HTTP ${resp.statusCode()}")
      }
      val actual = sha256(tmp)
      if (actual != expectedSha256) {
        tmp.delete()
        error("SHA-256 mismatch for $url: expected $expectedSha256, got $actual")
      }
      Files.move(tmp.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }

    downloadIfNeeded(aarUrl.get(), aarSha256.get(), aarFile)

    val markerModel = File(modelDir, "es_ES-sharvard-medium.onnx")
    val markerConfig = File(modelDir, "es_ES-sharvard-medium.onnx.json")
    val markerTokens = File(modelDir, "tokens.txt")
    val markerEspeak = File(modelDir, "espeak-ng-data/es_dict")
    if (
      markerModel.exists() &&
        markerModel.length() > 10_000_000L &&
        markerConfig.exists() &&
        markerTokens.exists() &&
        markerEspeak.exists()
    ) {
      return
    }

    val archiveFile = File(temporaryDir, "vits-piper-es_ES-sharvard-medium.tar.bz2")
    downloadIfNeeded(modelArchiveUrl.get(), modelArchiveSha256.get(), archiveFile)
    val assetsParent = modelDir.parentFile.apply { mkdirs() }
    if (modelDir.exists()) modelDir.deleteRecursively()
    val pyScript =
      "import tarfile, sys\nwith tarfile.open(sys.argv[1], 'r:bz2') as tf:\n    tf.extractall(sys.argv[2])\n"
    val proc =
      ProcessBuilder("python3", "-c", pyScript, archiveFile.absolutePath, assetsParent.absolutePath)
        .redirectErrorStream(true)
        .start()
    val out = proc.inputStream.bufferedReader().readText()
    val code = proc.waitFor()
    if (code != 0) {
      error("Extraction failed ($code): $out")
    }
    archiveFile.delete()
  }

  private fun sha256(file: File): String {
    val md = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
      val buf = ByteArray(64 * 1024)
      while (true) {
        val r = input.read(buf)
        if (r <= 0) break
        md.update(buf, 0, r)
      }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
  }
}

val preparePiperTtsAssets =
  tasks.register<PreparePiperTtsAssetsTask>("preparePiperTtsAssets") {
    aarUrl.set(
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/v2.0.1/sherpa-onnx-2.0.1.aar"
    )
    aarSha256.set("a8d09d1d32f3eb8520482c6d3d43fae674681c5b050385967c70a3c2c9bf0ee7")
    modelArchiveUrl.set(
      "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-es_ES-sharvard-medium.tar.bz2"
    )
    modelArchiveSha256.set("b30a7a83df0518f0ee1c7039506648cade99f1f9b498fc49ed2ced2e2536bb5a")
    aarOutputFile.set(layout.projectDirectory.file("libs/sherpa-onnx-2.0.1.aar"))
    modelOutputDir.set(
      layout.projectDirectory.dir("src/main/assets/tts/vits-piper-es_ES-sharvard-medium")
    )
  }

tasks.named("preBuild") { dependsOn(preparePiperTtsAssets) }

tasks.register("reportOfflineApkSizes") {
  doLast {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk").get().asFile
    check(apk.exists()) { "APK not found at ${apk.absolutePath}" }
    println("APK_TOTAL_BYTES=${apk.length()}")
    java.util.zip.ZipFile(apk).use { zip ->
      var onnxRaw = 0L
      var onnxCompressed = 0L
      var auxRaw = 0L
      var auxCompressed = 0L
      var nativeRaw = 0L
      var nativeCompressed = 0L
      var dexRaw = 0L
      var dexCompressed = 0L
      var otherRaw = 0L
      var otherCompressed = 0L
      val nativeDetails = mutableListOf<String>()
      val entries = zip.entries()
      while (entries.hasMoreElements()) {
        val e = entries.nextElement()
        val raw = e.size.coerceAtLeast(0L)
        val comp = e.compressedSize.coerceAtLeast(0L)
        when {
          e.name.endsWith(".onnx") -> {
            onnxRaw += raw
            onnxCompressed += comp
          }
          e.name.startsWith("assets/tts/") -> {
            auxRaw += raw
            auxCompressed += comp
          }
          e.name.startsWith("lib/") && e.name.endsWith(".so") -> {
            nativeRaw += raw
            nativeCompressed += comp
            nativeDetails += "${e.name}:raw=$raw,comp=$comp"
          }
          e.name.endsWith(".dex") -> {
            dexRaw += raw
            dexCompressed += comp
          }
          else -> {
            otherRaw += raw
            otherCompressed += comp
          }
        }
      }
      println("ONNX_MODEL:raw=$onnxRaw,comp=$onnxCompressed")
      println("MODEL_AUX_AND_ESPEAK:raw=$auxRaw,comp=$auxCompressed")
      println("NATIVE_LIBS:raw=$nativeRaw,comp=$nativeCompressed")
      nativeDetails.sorted().forEach { println("  NATIVE_SO=$it") }
      println("DEX:raw=$dexRaw,comp=$dexCompressed")
      println("OTHER:raw=$otherRaw,comp=$otherCompressed")
    }
  }
}

