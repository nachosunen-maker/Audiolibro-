package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.OfflineBolt
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.domain.audio.AudioSynthesisProvider
import com.example.domain.model.NarrationEstimator
import com.example.player.PlaybackUiState
import java.io.File
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

@Composable
fun BookCover(
  title: String,
  author: String,
  coverPath: String?,
  modifier: Modifier = Modifier,
) {
  val hasValidCover =
    remember(coverPath) { !coverPath.isNullOrBlank() && File(coverPath).exists() }

  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(10.dp),
    shadowElevation = 3.dp,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
  ) {
    if (hasValidCover) {
      AsyncImage(
        model = File(coverPath!!),
        contentDescription = "Portada de $title",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
      )
    } else {
      val palette = remember(title) { coverPaletteFor(title) }
      Box(modifier = Modifier.fillMaxSize().background(palette.background)) {
        Box(
          modifier =
            Modifier.width(7.dp)
              .fillMaxHeight()
              .background(palette.spine)
              .align(Alignment.CenterStart)
        )
        Column(
          modifier =
            Modifier.fillMaxSize()
              .padding(start = 13.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
          verticalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = palette.foreground,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
          )
          Text(
            text = author,
            style = MaterialTheme.typography.labelSmall,
            color = palette.foreground.copy(alpha = 0.82f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
  }
}

private data class CoverPalette(val background: Color, val spine: Color, val foreground: Color)

private fun coverPaletteFor(title: String): CoverPalette {
  val palettes =
    listOf(
      CoverPalette(Color(0xFF9C4221), Color(0xFF752D13), Color(0xFFFAF6F0)),
      CoverPalette(Color(0xFF3E5641), Color(0xFF283A2A), Color(0xFFFAF6F0)),
      CoverPalette(Color(0xFF4A4258), Color(0xFF322C3D), Color(0xFFF6F2F9)),
      CoverPalette(Color(0xFF7D5A2A), Color(0xFF5A3F1A), Color(0xFFFAF6F0)),
      CoverPalette(Color(0xFF2E4D5B), Color(0xFF1D343E), Color(0xFFF3F8FA)),
    )
  val index = abs(title.hashCode()) % palettes.size
  return palettes[index]
}

@Composable
fun InfoChip(
  icon: ImageVector,
  text: String,
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
  contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
  Surface(
    modifier = modifier,
    shape = RoundedCornerShape(8.dp),
    color = containerColor,
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = contentColor,
        modifier = Modifier.size(14.dp),
      )
      Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = contentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
fun MiniPlayerBar(
  playbackState: PlaybackUiState,
  onTogglePlayPause: () -> Unit,
  onOpenPlayer: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!playbackState.hasActiveAudio) return

  Surface(
    onClick = onOpenPlayer,
    modifier = modifier.fillMaxWidth().testTag("mini_player_bar"),
    shape = MaterialTheme.shapes.large,
    color = MaterialTheme.colorScheme.primaryContainer,
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)),
    shadowElevation = 4.dp,
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Icon(
        imageVector = Icons.Outlined.GraphicEq,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(22.dp),
      )

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = playbackState.currentChapterTitle.ifBlank { playbackState.activeBookTitle },
          style = MaterialTheme.typography.titleSmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text =
            "${playbackState.activeBookTitle} · ${NarrationEstimator.formatPlaybackTime(playbackState.positionMs)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      IconButton(
        onClick = onTogglePlayPause,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("mini_player_toggle"),
      ) {
        Icon(
          imageVector =
            if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
          contentDescription = if (playbackState.isPlaying) "Pausar" else "Reproducir",
          tint = MaterialTheme.colorScheme.primary,
        )
      }
    }
  }
}

fun formatIntegerEs(value: Int): String {
  return NumberFormat.getIntegerInstance(Locale("es", "ES")).format(value)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchitectureInfoSheet(
  audioProvider: AudioSynthesisProvider,
  onDismiss: () -> Unit,
) {
  var showLegalNotices by remember { mutableStateOf(false) }

  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 24.dp)
          .padding(bottom = 32.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(
        text = "Arquitectura de Audiolibro",
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )

      ArchitectureRow(
        icon = Icons.Outlined.MenuBook,
        title = "Lectura EPUB local + Room",
        description =
          "Procesamiento 100 % en el dispositivo mediante Storage Access Framework, lectura ZIP/OPF/NCX/NAV, división por párrafos/oraciones y guardado local en Room.",
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      ArchitectureRow(
        icon = Icons.Outlined.OfflineBolt,
        title =
          if (audioProvider.isConfigured) {
            "Motor local sin conexión · ${audioProvider.modelId}"
          } else {
            "Modelo local no encontrado · ${audioProvider.modelId}"
          },
        description =
          "Síntesis neuronal local mediante Piper VITS + sherpa-onnx (22.050 Hz, 16 bits PCM WAV) con dos voces en español de España: masculina (ID 0) y femenina (ID 1). No requiere Internet ni nube.",
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      ArchitectureRow(
        icon = Icons.Outlined.Gavel,
        title = "Transparencia de voz sintética y licencias",
        description =
          "Las voces son generadas sintéticamente en el dispositivo por un modelo neuronal entrenado con grabaciones humanas; no son grabaciones literales de las personas locutoras.",
      )

      OutlinedButton(
        onClick = { showLegalNotices = true },
        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("open_legal_notices_button"),
        shape = MaterialTheme.shapes.medium,
      ) {
        Icon(imageVector = Icons.Outlined.Gavel, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Ver avisos legales y licencias")
      }

      Spacer(modifier = Modifier.height(4.dp))
    }
  }

  if (showLegalNotices) {
    LegalNoticesDialog(onDismiss = { showLegalNotices = false })
  }
}

@Composable
fun LegalNoticesDialog(onDismiss: () -> Unit) {
  AlertDialog(
    onDismissRequest = onDismiss,
    modifier = Modifier.testTag("legal_notices_dialog"),
    title = { Text("Avisos legales y licencias") },
    text = {
      Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        LegalLicenseBlock(
          title = "Aviso sobre voces sintéticas",
          license = "Generación neuronal local",
          body =
            "Las narraciones generadas por Audiolibro se producen de forma sintética mediante modelos neuronales entrenados con grabaciones humanas. No constituyen grabaciones literales ni actuaciones directas de los hablantes originales.",
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LegalLicenseBlock(
          title = "sherpa-onnx (k2-fsa/sherpa-onnx v2.0.1)",
          license = "Apache License 2.0",
          body =
            "Motor de inferencia offline para texto a voz basado en ONNX Runtime, distribuido bajo licencia Apache 2.0.",
        )
        LegalLicenseBlock(
          title = "Modelo Piper VITS es_ES-sharvard-medium",
          license = "MIT License",
          body =
            "Pesos y configuración del modelo acústico Piper empaquetados para sherpa-onnx bajo licencia MIT.",
        )
        LegalLicenseBlock(
          title = "Corpus SHARVARD (Aubanel, García Lecumberri y Cooke)",
          license = "Creative Commons Atribución (CC BY 3.0 / CC BY 4.0)",
          body =
            "Basado en el corpus SHARVARD de español peninsular (University of Edinburgh DataShare · DOI: 10.3109/14992027.2014.907507), atribuido a Vincent Aubanel, María Luisa García Lecumberri y Martin Cooke.",
        )
        LegalLicenseBlock(
          title = "eSpeak NG (espeak-ng-data)",
          license = "GNU General Public License v3.0+ (GPLv3+)",
          body =
            "Datos fonéticos y conversión texto-a-fonemas en español proporcionados por eSpeak NG bajo licencia GPLv3 o posterior.",
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = onDismiss,
        modifier = Modifier.minimumInteractiveComponentSize().testTag("close_legal_notices_button"),
      ) {
        Text("Cerrar")
      }
    },
  )
}

@Composable
private fun LegalLicenseBlock(title: String, license: String, body: String) {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = license,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.primary,
    )
    Text(
      text = body,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Composable
private fun ArchitectureRow(
  icon: ImageVector,
  title: String,
  description: String,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(
      modifier =
        Modifier.size(36.dp)
          .clip(RoundedCornerShape(10.dp))
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        imageVector = icon,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier.size(20.dp),
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
