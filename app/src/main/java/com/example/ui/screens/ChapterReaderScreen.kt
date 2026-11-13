package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextDecrease
import androidx.compose.material.icons.outlined.TextIncrease
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.model.NarrationEstimator
import com.example.ui.components.formatIntegerEs
import com.example.ui.viewmodel.ChapterReaderUiState
import com.example.ui.viewmodel.ChapterReaderViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterReaderScreen(
  viewModel: ChapterReaderViewModel,
  onNavigateBack: () -> Unit,
) {
  val uiState by viewModel.uiState.collectAsStateWithLifecycle()
  val snackbarHostState = remember { SnackbarHostState() }
  val clipboardManager = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  val scrollState = rememberScrollState()
  var textSizeSp by remember { mutableFloatStateOf(17f) }
  val copiedMsg = stringResource(R.string.copied_to_clipboard)

  val loadedState = uiState as? ChapterReaderUiState.Loaded
  LaunchedEffect(loadedState?.currentChapter?.id) { scrollState.scrollTo(0) }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.background,
    contentWindowInsets = WindowInsets.safeDrawing,
    snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    topBar = {
      TopAppBar(
        colors =
          TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
          ),
        navigationIcon = {
          IconButton(
            onClick = onNavigateBack,
            modifier = Modifier.minimumInteractiveComponentSize().testTag("reader_back_button"),
          ) {
            Icon(
              imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
              contentDescription = stringResource(R.string.action_back),
            )
          }
        },
        title = {
          if (loadedState != null) {
            Column {
              Text(
                text = loadedState.currentChapter.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
              Text(
                text =
                  "Capítulo ${loadedState.currentChapter.chapterIndex} de ${loadedState.chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          } else {
            Text(text = "Texto del capítulo")
          }
        },
        actions = {
          IconButton(
            onClick = { textSizeSp = (textSizeSp - 1.5f).coerceAtLeast(14f) },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Outlined.TextDecrease,
              contentDescription = "Reducir tamaño de texto",
            )
          }
          IconButton(
            onClick = { textSizeSp = (textSizeSp + 1.5f).coerceAtMost(24f) },
            modifier = Modifier.minimumInteractiveComponentSize(),
          ) {
            Icon(
              imageVector = Icons.Outlined.TextIncrease,
              contentDescription = "Aumentar tamaño de texto",
            )
          }
          if (loadedState != null) {
            IconButton(
              onClick = {
                clipboardManager.setText(AnnotatedString(loadedState.currentChapter.contentText))
                scope.launch { snackbarHostState.showSnackbar(copiedMsg) }
              },
              modifier = Modifier.minimumInteractiveComponentSize().testTag("copy_chapter_button"),
            ) {
              Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.action_copy_text),
              )
            }
          }
        },
      )
    },
    bottomBar = {
      if (loadedState != null) {
        Surface(
          color = MaterialTheme.colorScheme.surface,
          tonalElevation = 2.dp,
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
          Box(
            modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
            contentAlignment = Alignment.Center,
          ) {
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .widthIn(max = 680.dp)
                  .padding(horizontal = 20.dp, vertical = 12.dp),
              horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              OutlinedButton(
                onClick = viewModel::selectPreviousChapter,
                enabled = loadedState.hasPrevious,
                modifier = Modifier.weight(1f).height(48.dp).testTag("prev_chapter_button"),
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                  contentDescription = null,
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.action_prev_chapter))
              }
              Button(
                onClick = viewModel::selectNextChapter,
                enabled = loadedState.hasNext,
                modifier = Modifier.weight(1f).height(48.dp).testTag("next_chapter_button"),
              ) {
                Text(stringResource(R.string.action_next_chapter))
                Spacer(modifier = Modifier.width(6.dp))
                Icon(
                  imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                  contentDescription = null,
                )
              }
            }
          }
        }
      }
    },
  ) { innerPadding ->
    Box(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentAlignment = Alignment.TopCenter,
    ) {
      when (val state = uiState) {
        ChapterReaderUiState.Loading -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
          }
        }
        ChapterReaderUiState.NotFound -> {
          Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
              text = "No se encontró el texto de este capítulo.",
              style = MaterialTheme.typography.bodyLarge,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
        is ChapterReaderUiState.Loaded -> {
          val chapter = state.currentChapter
          Column(
            modifier =
              Modifier.fillMaxSize()
                .widthIn(max = 680.dp)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
          ) {
            Text(
              text = state.bookTitle,
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = chapter.title,
              style = MaterialTheme.typography.headlineMedium,
              color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
              text =
                "${formatIntegerEs(chapter.wordCount)} palabras · ${formatIntegerEs(chapter.characterCount)} caracteres · ${NarrationEstimator.formatDuration(chapter.estimatedMinutes)}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider(
              color = MaterialTheme.colorScheme.outlineVariant,
              modifier = Modifier.padding(vertical = 4.dp),
            )
            Text(
              text = chapter.contentText,
              style =
                MaterialTheme.typography.bodyLarge.copy(
                  fontSize = textSizeSp.sp,
                  lineHeight = (textSizeSp * 1.55f).sp,
                ),
              color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(modifier = Modifier.height(24.dp))
          }
        }
      }
    }
  }
}
