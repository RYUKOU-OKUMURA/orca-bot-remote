package dev.okumura.orcaremote

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

private data class NotificationOpen(val uri: Uri, val coldStart: Boolean)

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<NotificationOpen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationTarget = if (savedInstanceState == null) {
            intent.data?.takeIf { it.scheme == NotificationScheme }?.let { NotificationOpen(it, coldStart = true) }
        } else {
            savedInstanceState.getString("notificationTarget")?.let {
                NotificationOpen(Uri.parse(it), savedInstanceState.getBoolean("notificationColdStart"))
            }
        }
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
                typography = LargeType,
            ) {
                RemoteApp(notificationTarget) { notificationTarget = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationTarget = intent.data?.takeIf { it.scheme == NotificationScheme }
            ?.let { NotificationOpen(it, coldStart = false) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        notificationTarget?.let {
            outState.putString("notificationTarget", it.uri.toString())
            outState.putBoolean("notificationColdStart", it.coldStart)
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
    private val preferences = application.connectionPreferences()
    var connection by mutableStateOf(preferences.savedConnection())
        private set
    var notificationsEnabled by mutableStateOf(preferences.getBoolean(NotificationsEnabled, true))
        private set

    init {
        createNotificationChannels(application)
        updateNotificationSchedule()
    }

    fun save(verified: Connection) {
        val editor = preferences.edit()
            .putString("url", verified.url.toString())
            .putString("token", verified.token)
        if (verified.url != connection?.url) {
            editor.remove(NotifiedItems).remove(NotifiedSummary)
            getApplication<Application>().getSystemService(NotificationManager::class.java).cancelAll()
        }
        editor.apply()
        connection = verified
        updateNotificationSchedule()
    }

    fun setNotifications(enabled: Boolean) {
        preferences.edit().putBoolean(NotificationsEnabled, enabled).apply()
        notificationsEnabled = enabled
        updateNotificationSchedule()
    }

    fun claimNotificationPrompt(): Boolean {
        if (preferences.getBoolean("notificationPermissionAsked", false)) return false
        preferences.edit().putBoolean("notificationPermissionAsked", true).apply()
        return true
    }

    private fun updateNotificationSchedule() {
        scheduleNotifications(getApplication(), notificationsEnabled && connection != null)
    }
}

internal fun Context.connectionPreferences(): SharedPreferences = getSharedPreferences("connection", Context.MODE_PRIVATE)

internal fun SharedPreferences.savedConnection(): Connection? =
    Connection.parse(getString("url", "").orEmpty(), getString("token", "").orEmpty())

@Serializable private object SettingsRoute
@Serializable private data class HomeRoute(val connectionVerified: Boolean = false)
@Serializable private data class ChatsRoute(val bot: String)
@Serializable private data class NewChatRoute(val bot: String)
@Serializable private data class ConversationRoute(val bot: String, val chatId: String, val title: String)
@Serializable private object ItemsRoute
@Serializable private data class ItemRoute(val bot: String, val number: Long)
@Serializable private object SummaryRoute

private const val RefreshCounts = "refreshCounts"
private const val RefreshChats = "refreshChats"
private const val RefreshItems = "refreshItems"
private const val Answered = "answered"

@Composable
private fun newChatSender(entry: NavBackStackEntry, api: OrcaApi, bot: String): SendModel<String> =
    viewModel(viewModelStoreOwner = entry) { SendModel(createSavedStateHandle()) { api.startChat(bot, it) } }

@Composable
private fun RemoteApp(
    notificationTarget: NotificationOpen?,
    store: ConnectionStore = viewModel(),
    consumeNotification: () -> Unit,
) {
    val nav = rememberNavController()
    val settingsFirst = rememberSaveable { store.connection == null }
    val connection = store.connection
    val api = remember(connection) { connection?.let { OrcaApi(it) } }
    val openSettings = { nav.navigate(SettingsRoute) { launchSingleTop = true } }
    val back: () -> Unit = { nav.navigateUp() }
    val openConversation = { bot: String, chatId: String, title: String ->
        nav.navigate(ConversationRoute(bot, chatId, title))
    }

    NavHost(nav, startDestination = if (settingsFirst) SettingsRoute else HomeRoute()) {
        composable<SettingsRoute> {
            SettingsScreen(
                connection,
                if (nav.previousBackStackEntry != null) back else null,
                store.notificationsEnabled,
                store::setNotifications,
            ) {
                store.save(it)
                nav.navigate(HomeRoute(connectionVerified = true)) {
                    popUpTo(nav.graph.id)
                }
            }
        }
        composable<HomeRoute> { entry ->
            val verified = entry.toRoute<HomeRoute>().connectionVerified
            var showSuccess by rememberSaveable { mutableStateOf(verified) }
            val refreshCounts by entry.savedStateHandle.getStateFlow(RefreshCounts, false).collectAsState()
            LaunchedEffect(api) { if (api == null) openSettings() }
            if (api != null) {
                RequestNotificationPermission(store)
                HomeScreen(
                    api,
                    showSuccess,
                    { showSuccess = false },
                    refreshCounts,
                    { entry.savedStateHandle[RefreshCounts] = false },
                    openSettings,
                    { nav.navigate(ChatsRoute(it)) },
                    { nav.navigate(ItemsRoute) },
                    { nav.navigate(SummaryRoute) },
                )
            }
        }
        composable<ChatsRoute> { entry ->
            val (bot) = entry.toRoute<ChatsRoute>()
            val refreshChats by entry.savedStateHandle.getStateFlow(RefreshChats, false).collectAsState()
            if (api != null) {
                ChatsScreen(
                    api, bot, back, openSettings, refreshChats,
                    { entry.savedStateHandle[RefreshChats] = false },
                    { nav.navigate(NewChatRoute(bot)) },
                    { openConversation(bot, it.id, it.displayTitle) },
                )
            }
        }
        composable<NewChatRoute> { entry ->
            val (bot) = entry.toRoute<NewChatRoute>()
            val chatsEntry = remember(entry) { nav.getBackStackEntry<ChatsRoute>() }
            if (api != null) {
                val sender = newChatSender(chatsEntry, api, bot)
                NewChatScreen(
                    sender, bot, back, openSettings,
                    checkChats = {
                        chatsEntry.savedStateHandle[RefreshChats] = true
                        nav.popBackStack()
                    },
                ) { chatId ->
                    chatsEntry.savedStateHandle[RefreshChats] = true
                    nav.navigate(ConversationRoute(bot, chatId, bot)) {
                        popUpTo<NewChatRoute> { inclusive = true }
                    }
                }
            }
        }
        composable<ConversationRoute> { entry ->
            val (bot, chatId, title) = entry.toRoute<ConversationRoute>()
            if (api != null) ConversationScreen(api, bot, chatId, title, back, openSettings)
        }
        composable<ItemsRoute> { entry ->
            val answered by entry.savedStateHandle.getStateFlow(Answered, false).collectAsState()
            val refreshItems by entry.savedStateHandle.getStateFlow(RefreshItems, false).collectAsState()
            if (api != null) ItemsScreen(
                api, back, openSettings, refreshItems, { entry.savedStateHandle[RefreshItems] = false },
                answered, { entry.savedStateHandle[Answered] = false }, openConversation,
            ) {
                nav.navigate(ItemRoute(it.bot, it.number))
            }
        }
        composable<ItemRoute> { entry ->
            val (bot, number) = entry.toRoute<ItemRoute>()
            val itemsEntry = remember(entry) { runCatching { nav.getBackStackEntry<ItemsRoute>() }.getOrNull() }
            val refreshAfterAnswer: () -> Unit = {
                nav.getBackStackEntry<HomeRoute>().savedStateHandle[RefreshCounts] = true
                itemsEntry?.savedStateHandle?.set(RefreshItems, true)
            }
            if (api != null) ItemScreen(api, bot, number, back, openSettings, openConversation, refreshAfterAnswer) {
                itemsEntry?.savedStateHandle?.set(Answered, true)
                nav.popBackStack()
            }
        }
        composable<SummaryRoute> {
            if (api != null) SummaryScreen(api, back, openSettings, openConversation)
        }
    }

    LaunchedEffect(notificationTarget, api) {
        if (notificationTarget == null) return@LaunchedEffect
        consumeNotification()
        if (api == null) return@LaunchedEffect
        val screen = notificationTarget.uri.authority
        val (bot, number) = notificationTarget.uri.pathSegments.let {
            it.getOrNull(0).orEmpty() to it.getOrNull(1)?.toLongOrNull()
        }
        when (screen) {
            "summary" -> nav.navigate(SummaryRoute)
            "items" -> nav.navigate(ItemsRoute)
            "item" -> if (bot.isNotBlank() && number != null && number > 0) {
                if (notificationTarget.coldStart) nav.navigate(ItemsRoute)
                nav.navigate(ItemRoute(bot, number))
            }
        }
    }
}

@Composable
private fun RequestNotificationPermission(store: ConnectionStore) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(store.notificationsEnabled) {
        if (store.notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            store.claimNotificationPrompt()
        ) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
