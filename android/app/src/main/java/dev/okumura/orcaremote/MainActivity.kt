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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavBackStackEntry
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private var notificationTarget by mutableStateOf<ExternalOpen?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationTarget = if (savedInstanceState == null) {
            intent.externalOpen(coldStart = true)
        } else {
            savedInstanceState.getString("externalOpen")?.let { apiJson.decodeFromString<ExternalOpen>(it) }
        }
        enableEdgeToEdge()
        setContent {
            OrcaTheme {
                RemoteApp(notificationTarget) { notificationTarget = null }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        notificationTarget = intent.externalOpen(coldStart = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        notificationTarget?.let {
            outState.putString("externalOpen", apiJson.encodeToString(it))
        }
    }
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

    suspend fun save(verified: Connection) = notificationSettingsMutex.withLock {
        val editor = preferences.edit()
            .putString("url", verified.url.toString())
            .putString("token", verified.token)
        if (verified.url != connection?.url) {
            editor.remove(NotifiedItems).remove(NotifiedSummary).remove(SummaryInitialized)
            getApplication<Application>().getSystemService(NotificationManager::class.java).cancelAll()
        }
        editor.apply()
        connection = verified
        updateNotificationSchedule()
    }

    fun setNotifications(enabled: Boolean) {
        viewModelScope.launch {
            notificationSettingsMutex.withLock {
                preferences.edit().putBoolean(NotificationsEnabled, enabled).apply()
                notificationsEnabled = enabled
                updateNotificationSchedule()
            }
        }
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
@Serializable private object ShareRoute
@Serializable private object SummaryRoute

private const val RefreshCounts = "refreshCounts"
private const val RefreshChats = "refreshChats"
private const val RefreshItems = "refreshItems"
private const val Answered = "answered"

@Composable
private fun newChatSender(entry: NavBackStackEntry, api: OrcaApi, bot: String, meta: ChatMetaStore): SendModel<String> {
    val resolver = LocalContext.current.applicationContext.contentResolver
    return viewModel(viewModelStoreOwner = entry) {
        val state = createSavedStateHandle()
        val chat = ChatDraft(state, AttachmentLimits.NewChat)
        SendModel(state, chat) {
            api.startChat(bot, it, chat.inline { file -> file.open(resolver) }).also { id -> meta.markRead(bot, id) }
        }
    }
}

@Composable
private fun RemoteApp(
    notificationTarget: ExternalOpen?,
    store: ConnectionStore = viewModel(),
    consumeNotification: () -> Unit,
) {
    val nav = rememberNavController()
    val currentEntry by nav.currentBackStackEntryAsState()
    val settingsFirst = rememberSaveable { store.connection == null }
    val connection = store.connection
    val context = LocalContext.current.applicationContext
    val meta = remember(connection) { context.chatMetaStore(connection?.url?.toString().orEmpty()) }
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
                    meta, showSuccess,
                    { showSuccess = false },
                    refreshCounts,
                    { entry.savedStateHandle[RefreshCounts] = false },
                    openSettings,
                    { nav.navigate(ChatsRoute(it)) },
                    { nav.navigate(ItemsRoute) },
                    { nav.navigate(SummaryRoute) },
                    openConversation,
                )
            }
        }
        composable<ShareRoute> { entry ->
            if (api != null) ShareScreen(api, back, openSettings) { bot, chat ->
                val draft = entry.savedStateHandle.get<String>(SharedDraftKey)
                nav.navigate(ChatsRoute(bot)) { popUpTo<ShareRoute> { inclusive = true } }
                if (chat == null) nav.navigate(NewChatRoute(bot))
                else openConversation(bot, chat.id, chat.displayTitle)
                nav.currentBackStackEntry?.savedStateHandle?.set(SharedDraftKey, draft)
            }
        }
        composable<ChatsRoute> { entry ->
            val (bot) = entry.toRoute<ChatsRoute>()
            val refreshChats by entry.savedStateHandle.getStateFlow(RefreshChats, false).collectAsState()
            if (api != null) {
                ChatsScreen(
                    api, meta, bot, back, openSettings, refreshChats,
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
                val sender = newChatSender(chatsEntry, api, bot, meta)
                ApplySharedDraft(entry.savedStateHandle, sender)
                NewChatScreen(
                    sender, bot, back, openSettings,
                    checkChats = {
                        chatsEntry.savedStateHandle[RefreshChats] = true
                        nav.popBackStack()
                    },
                )
            }
        }
        composable<ConversationRoute> { entry ->
            val (bot, chatId, title) = entry.toRoute<ConversationRoute>()
            if (api != null) ConversationScreen(api, meta, bot, chatId, title, entry.savedStateHandle, back, openSettings)
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
            if (api != null) ItemScreen(
                api, bot, number, back, openSettings, openConversation, refreshAfterAnswer,
                isCurrent = currentEntry == entry,
            ) {
                if (nav.currentBackStackEntry == entry) {
                    itemsEntry?.savedStateHandle?.set(Answered, true)
                    nav.popBackStack()
                }
            }
        }
        composable<SummaryRoute> {
            if (api != null) SummaryScreen(api, back, openSettings, openConversation)
        }
    }

    // Consume chat results even while a notification covers the form.
    val newChatEntry = remember(currentEntry) { runCatching { nav.getBackStackEntry<NewChatRoute>() }.getOrNull() }
    if (api != null && newChatEntry != null) {
        val (bot) = newChatEntry.toRoute<NewChatRoute>()
        val chatsEntry = remember(newChatEntry) { nav.getBackStackEntry<ChatsRoute>() }
        val sender = newChatSender(chatsEntry, api, bot, meta)
        val result = sender.result
        LaunchedEffect(newChatEntry, result) {
            if (result != null) {
                sender.resultShown()
                chatsEntry.savedStateHandle[RefreshChats] = true
                if (nav.currentBackStackEntry == newChatEntry) {
                    nav.navigate(ConversationRoute(bot, result, bot)) {
                        popUpTo<NewChatRoute> { inclusive = true }
                    }
                }
            }
        }
    }

    LaunchedEffect(notificationTarget, api) {
        if (notificationTarget == null) return@LaunchedEffect
        if (api == null) {
            openSettings()
            return@LaunchedEffect
        }
        consumeNotification()
        notificationTarget.share?.let { draft ->
            nav.navigate(ShareRoute)
            nav.currentBackStackEntry?.savedStateHandle?.set(SharedDraftKey, apiJson.encodeToString(draft))
            return@LaunchedEffect
        }
        val uri = notificationTarget.link?.let(Uri::parse) ?: return@LaunchedEffect
        val screen = uri.authority
        val (bot, number) = uri.pathSegments.let {
            it.getOrNull(0).orEmpty() to it.getOrNull(1)?.toLongOrNull()
        }
        when (screen) {
            "summary" -> nav.navigate(SummaryRoute) { launchSingleTop = true }
            "items" -> nav.navigate(ItemsRoute) { launchSingleTop = true }
            "item" -> if (bot.isNotBlank() && number != null && number > 0) {
                val route = ItemRoute(bot, number)
                if (runCatching { nav.getBackStackEntry(route) }.isSuccess) {
                    nav.popBackStack(route, inclusive = false)
                } else {
                    if (notificationTarget.coldStart) nav.navigate(ItemsRoute) { launchSingleTop = true }
                    nav.navigate(route)
                }
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
