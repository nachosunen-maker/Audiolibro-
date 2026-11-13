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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
  ModalBottomSheet(
    onDismissRequest = onDismiss,
    containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
      modifier =
        Modifier.fillMaxWidth()
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
        icon = Icons.Outlined.AutoAwesome,
        title =
          if (audioProvider.isConfigured) {
            "Firebase AI Logic activo · ${audioProvider.modelId}"
          } else {
            "Firebase AI Logic pendiente · ${audioProvider.modelId}"
          },
        description =
          "Generación de voz WAV por fragmentos reanudables con perfiles Kore (femenina) y Charon (masculina), reproducción Media3 en segundo plano y exportación WAV.",
      )

      HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

      ArchitectureRow(
        icon = Icons.Outlined.Security,
        title = "App Check (Debug + Play Integrity)",
        description =
          "Sin claves API embebidas en el código ni servidores propios. Usa DebugAppCheckProviderFactory en desarrollo y PlayIntegrityAppCheckProviderFactory en producción.",
      )

      Spacer(modifier = Modifier.height(4.dp))
    }
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
