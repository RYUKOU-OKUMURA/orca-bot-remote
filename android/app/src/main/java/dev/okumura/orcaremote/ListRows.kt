package dev.okumura.orcaremote

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun OrcaIcon(
    @DrawableRes id: Int,
    description: String? = null,
    modifier: Modifier = Modifier.size(22.dp),
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) = Icon(painterResource(id), description, modifier, tint)

@Composable
internal fun BotRow(bot: Bot, unread: Boolean, expanded: Boolean, open: () -> Unit, toggle: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.small
    val pausedWithPendingItems = bot.paused && bot.needsYou > 0
    val preview = if (bot.needsYou == 1) "1 needs you" else "${bot.needsYou} need you"
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape)
            .background(if (expanded) colors.secondaryContainer else Color.Transparent)
            .then(if (expanded) Modifier.border(1.dp, colors.outline, shape) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ListRow(
            title = bot.name,
            unread = unread,
            work = if (bot.working) listOfNotNull("作業中", bot.activeWork?.takeIf(String::isNotBlank)).joinToString(" · ") else null,
            onClick = open,
            modifier = Modifier.weight(1f),
            leading = { Mascot(bot) },
            subtitle = when {
                pausedWithPendingItems -> "$preview · 停止中"
                bot.needsYou > 0 -> preview
                bot.paused -> "停止中"
                else -> markdownPreview(bot.lastSaid) ?: bot.specialty
            },
            subtitleIcon = if (bot.needsYou > 0) R.drawable.ic_pending else R.drawable.ic_activity,
            subtitleColor = if (bot.needsYou > 0) colors.tertiary else colors.onSurfaceVariant,
            subtitleMaxLines = if (pausedWithPendingItems) Int.MAX_VALUE else 1,
            caption = relativeTime(bot.lastActivityAt, compact = true),
            showChevron = false,
        )
        IconButton(onClick = toggle) {
            OrcaIcon(
                if (expanded) R.drawable.ic_expanded else R.drawable.ic_chevron,
                if (expanded) "${bot.name}のチャットをたたむ" else "${bot.name}のチャットを表示",
            )
        }
    }
}

@Composable
internal fun ChatRow(chat: Chat, bot: String, meta: ChatMetaStore, modifier: Modifier = Modifier, showPreview: Boolean = true, open: () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ListRow(
            title = chat.displayTitle,
            onClick = open,
            modifier = Modifier.weight(1f),
            unread = meta.unread(bot, chat),
            work = if (chat.working) "作業中" else null,
            leading = { OrcaIcon(if (chat.home) R.drawable.ic_home else R.drawable.ic_chat) },
            titleMaxLines = if (showPreview) 2 else 1,
            subtitle = if (showPreview) markdownPreview(chat.lastSaid) ?: markdownPreview(chat.lastAsked) else null,
            caption = relativeTime(chat.lastActivityAt, compact = true),
            showChevron = false,
        )
        val pinned = meta.pinned(bot, chat.id)
        IconToggleButton(checked = pinned, onCheckedChange = { meta.togglePin(bot, chat.id) }) {
            OrcaIcon(if (pinned) R.drawable.ic_pin_filled else R.drawable.ic_pin,
                if (pinned) "${chat.displayTitle}のピン留めを解除" else "${chat.displayTitle}をピン留め",
                tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
internal fun ListRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    subtitle: String? = null,
    @DrawableRes subtitleIcon: Int? = null,
    subtitleColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    caption: String? = null,
    unread: Boolean = false,
    work: String? = null,
    showChevron: Boolean = true,
    titleMaxLines: Int = 1,
    subtitleMaxLines: Int = 1,
) {
    Row(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(OrcaGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (unread) Box(Modifier.size(8.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                    .semantics { contentDescription = "未読" })
                Text(
                    title,
                    fontWeight = if (unread) FontWeight.Bold else null,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = titleMaxLines,
                    overflow = TextOverflow.Ellipsis,
                )
                caption?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            work?.let { WorkingBadge(it) }
            subtitle?.let {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    subtitleIcon?.let { id -> OrcaIcon(id, modifier = Modifier.size(16.dp), tint = subtitleColor) }
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = subtitleColor,
                        maxLines = subtitleMaxLines,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (showChevron) OrcaIcon(R.drawable.ic_chevron, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun WorkingBadge(label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
        Text(label, style = MaterialTheme.typography.labelMedium, color = orcaAccentText,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val Mascots = mapOf(
    "mascot:A1" to R.drawable.mascot_a1,
    "mascot:B1" to R.drawable.mascot_b1,
    "mascot:D1" to R.drawable.mascot_d1,
    "mascot:E1" to R.drawable.mascot_e1,
    "mascot:F1" to R.drawable.mascot_f1,
    "mascot:G1" to R.drawable.mascot_g1,
    "mascot:H1" to R.drawable.mascot_h1,
    "mascot:H2" to R.drawable.mascot_h2,
    "mascot:I1" to R.drawable.mascot_i1,
    "mascot:J1" to R.drawable.mascot_j1,
    "mascot:K2" to R.drawable.mascot_k2,
    "mascot:L2" to R.drawable.mascot_l2,
    "mascot:M1" to R.drawable.mascot_m1,
    "mascot:N1" to R.drawable.mascot_n1,
    "mascot:O1" to R.drawable.mascot_o1,
    "mascot:P3" to R.drawable.mascot_p3,
    "mascot:Q1" to R.drawable.mascot_q1,
    "mascot:R3" to R.drawable.mascot_r3,
    "mascot:S2" to R.drawable.mascot_s2,
    "mascot:T1" to R.drawable.mascot_t1,
)

@Composable
private fun Mascot(bot: Bot) {
    val shape = MaterialTheme.shapes.small
    val tile = Modifier.size(44.dp).clip(shape)
    val id = Mascots[bot.avatar]
    if (id != null) {
        Image(painterResource(id), contentDescription = null, modifier = tile, contentScale = ContentScale.Crop)
    } else {
        val colors = MaterialTheme.colorScheme
        Box(tile.background(colors.secondaryContainer), contentAlignment = Alignment.Center) {
            val name = bot.name.trimStart()
            val initial = name.codePoints().findFirst().orElse('-'.code)
            Text(String(Character.toChars(initial)), style = MaterialTheme.typography.titleMedium)
        }
    }
}
