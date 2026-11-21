package dev.okumura.orcaremote

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
                typography = LargeType,
            ) {
                RemoteApp()
            }
        }
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF24605B),
    primaryContainer = Color(0xFFC1EDE6),
    onPrimaryContainer = Color(0xFF083A35),
    secondaryContainer = Color(0xFFE2EAEF),
    onSecondaryContainer = Color(0xFF263C48),
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFF8FAF9),
    surfaceContainer = Color(0xFFEDF3F1),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA4D7D0),
    primaryContainer = Color(0xFF174A44),
    onPrimaryContainer = Color(0xFFC1EDE6),
    secondaryContainer = Color(0xFF344957),
    onSecondaryContainer = Color(0xFFDFEBF2),
)

private val LargeType = Typography().run {
    copy(
        bodyLarge = bodyLarge.copy(fontSize = 18.sp, lineHeight = 28.sp),
        bodyMedium = bodyMedium.copy(fontSize = 16.sp, lineHeight = 24.sp),
        bodySmall = bodySmall.copy(fontSize = 14.sp, lineHeight = 21.sp),
        titleLarge = titleLarge.copy(fontSize = 24.sp, lineHeight = 32.sp),
        titleMedium = titleMedium.copy(fontSize = 20.sp, lineHeight = 28.sp),
        labelLarge = labelLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    )
}

internal class ConnectionStore(application: Application) : AndroidViewModel(application) {
    private val preferences = application.getSharedPreferences("connection", Application.MODE_PRIVATE)
    var connection by mutableStateOf(
        Connection.parse(
            preferences.getString("url", "").orEmpty(),
            preferences.getString("token", "").orEmpty(),
        )
    )
        private set

    fun save(verified: Connection) {
        preferences.edit()
            .putString("url", verified.url.toString())
            .putString("token", verified.token)
            .apply()
        connection = verified
    }
}

@Serializable private object SettingsRoute
@Serializable private data class HomeRoute(val connectionVerified: Boolean = false)
@Serializable private data class ChatsRoute(val bot: String)
@Serializable private data class ConversationRoute(val bot: String, val chatId: String, val title: String)
@Serializable private object ItemsRoute
@Serializable private data class ItemRoute(val bot: String, val number: Long)
@Serializable private object SummaryRoute

@Composable
private fun RemoteApp(store: ConnectionStore = viewModel()) {
    val nav = rememberNavController()
    val settingsFirst = rememberSaveable { store.connection == null }
    val connection = store.connection
    val api = remember(connection) { connection?.let(::OrcaApi) }
    val openSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } }
    val back: () -> Unit = { nav.navigateUp() }
    val openConversation = { bot: String, chatId: String, title: String ->
        nav.navigate(ConversationRoute(bot, chatId, title))
    }

    NavHost(nav, startDestination = if (settingsFirst) SettingsRoute else HomeRoute()) {
        composable<SettingsRoute> {
            SettingsScreen(connection, if (nav.previousBackStackEntry != null) back else null) {
                store.save(it)
                nav.navigate(HomeRoute(connectionVerified = true)) {
                    popUpTo(nav.graph.id)
                }
            }
        }
        composable<HomeRoute> { entry ->
            val verified = entry.toRoute<HomeRoute>().connectionVerified
            var showSuccess by rememberSaveable { mutableStateOf(verified) }
            LaunchedEffect(api) { if (api == null) openSettings() }
            if (api != null) {
                HomeScreen(
                    api,
                    showSuccess,
                    { showSuccess = false },
                    openSettings,
                    { nav.navigate(ChatsRoute(it)) },
                    { nav.navigate(ItemsRoute) },
                    { nav.navigate(SummaryRoute) },
                )
            }
        }
        composable<ChatsRoute> { entry ->
            val (bot) = entry.toRoute<ChatsRoute>()
            if (api != null) ChatsScreen(api, bot, back, openSettings) {
                openConversation(bot, it.id, it.displayTitle)
            }
        }
        composable<ConversationRoute> { entry ->
            val (bot, chatId, title) = entry.toRoute<ConversationRoute>()
            if (api != null) ConversationScreen(api, bot, chatId, title, back, openSettings)
        }
        composable<ItemsRoute> {
            if (api != null) ItemsScreen(api, back, openSettings, openConversation) {
                nav.navigate(ItemRoute(it.bot, it.number))
            }
        }
        composable<ItemRoute> { entry ->
            val (bot, number) = entry.toRoute<ItemRoute>()
            if (api != null) ItemScreen(api, bot, number, back, openSettings, openConversation)
        }
        composable<SummaryRoute> {
            if (api != null) SummaryScreen(api, back, openSettings, openConversation)
        }
    }
}
