package com.example.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.ui.screens.AudiobookPlayerScreen
import com.example.ui.screens.BookDetailScreen
import com.example.ui.screens.ChapterReaderScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.viewmodel.AudiobookViewModelFactory
import com.example.ui.viewmodel.BookDetailViewModel
import com.example.ui.viewmodel.ChapterReaderViewModel
import com.example.ui.viewmodel.LibraryViewModel
import com.example.ui.viewmodel.PlayerViewModel

object Routes {
  const val LIBRARY = "library"
  const val BOOK_DETAIL = "book/{bookId}"
  const val CHAPTER_READER = "book/{bookId}/chapter/{chapterIndex}"
  const val PLAYER = "book/{bookId}/player?chapterIndex={chapterIndex}"

  fun bookDetail(bookId: String): String = "book/${Uri.encode(bookId)}"

  fun chapterReader(bookId: String, chapterIndex: Int): String =
    "book/${Uri.encode(bookId)}/chapter/$chapterIndex"

  fun player(bookId: String, startChapterIndex: Int? = null): String {
    val idx = startChapterIndex ?: -1
    return "book/${Uri.encode(bookId)}/player?chapterIndex=$idx"
  }
}

@Composable
fun AudiobookApp() {
  val navController = rememberNavController()
  val context = LocalContext.current

  NavHost(navController = navController, startDestination = Routes.LIBRARY) {
    composable(route = Routes.LIBRARY) {
      val viewModel: LibraryViewModel =
        viewModel(factory = AudiobookViewModelFactory(context = context))
      LibraryScreen(
        viewModel = viewModel,
        onOpenBook = { bookId -> navController.navigate(Routes.bookDetail(bookId)) },
        onOpenPlayer = { bookId -> navController.navigate(Routes.player(bookId)) },
      )
    }

    composable(
      route = Routes.BOOK_DETAIL,
      arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
    ) { backStackEntry ->
      val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
      val viewModel: BookDetailViewModel =
        viewModel(factory = AudiobookViewModelFactory(context = context, bookId = bookId))
      BookDetailScreen(
        viewModel = viewModel,
        onNavigateBack = { navController.popBackStack() },
        onOpenChapterText = { id, chapterIndex ->
          navController.navigate(Routes.chapterReader(id, chapterIndex))
        },
        onOpenPlayer = { id, startChapterIndex ->
          navController.navigate(Routes.player(id, startChapterIndex))
        },
      )
    }

    composable(
      route = Routes.CHAPTER_READER,
      arguments =
        listOf(
          navArgument("bookId") { type = NavType.StringType },
          navArgument("chapterIndex") { type = NavType.IntType },
        ),
    ) { backStackEntry ->
      val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
      val chapterIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: 1
      val viewModel: ChapterReaderViewModel =
        viewModel(
          factory =
            AudiobookViewModelFactory(
              context = context,
              bookId = bookId,
              initialChapterIndex = chapterIndex,
            )
        )
      ChapterReaderScreen(
        viewModel = viewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }

    composable(
      route = Routes.PLAYER,
      arguments =
        listOf(
          navArgument("bookId") { type = NavType.StringType },
          navArgument("chapterIndex") {
            type = NavType.IntType
            defaultValue = -1
          },
        ),
    ) { backStackEntry ->
      val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
      val rawIndex = backStackEntry.arguments?.getInt("chapterIndex") ?: -1
      val startChapterIndex = if (rawIndex > 0) rawIndex else null
      val viewModel: PlayerViewModel =
        viewModel(
          factory =
            AudiobookViewModelFactory(
              context = context,
              bookId = bookId,
              startPlaybackChapterIndex = startChapterIndex,
            )
        )
      AudiobookPlayerScreen(
        viewModel = viewModel,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
