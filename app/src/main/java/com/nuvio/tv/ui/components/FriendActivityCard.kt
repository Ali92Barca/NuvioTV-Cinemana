package com.nuvio.tv.ui.components

import android.text.BidiFormatter
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.FriendActivityDisplay
import com.nuvio.tv.ui.theme.NuvioTheme
import com.nuvio.tv.ui.util.contentTextDirection
import java.time.Instant
import java.util.Date

@Composable
internal fun FriendActivityAvatar(activity: FriendActivityDisplay, modifier: Modifier = Modifier) {
    val ring = if (activity.watching) Color(0xFF48D89B) else Color(0xFFFFBA50)
    Box(
        modifier = modifier.padding(8.dp).size(36.dp)
            .border(2.dp, ring, CircleShape).padding(2.dp).clip(CircleShape)
            .background(NuvioTheme.colors.BackgroundCard),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
        if (activity.avatarUrl != null) {
            AsyncImage(
                model = activity.avatarUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
internal fun FriendActivityCardTitle(title: String, modifier: Modifier = Modifier) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall.copy(textDirection = title.contentTextDirection()),
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f))))
            .padding(start = 10.dp, end = 10.dp, top = 18.dp, bottom = 8.dp)
    )
}

@Composable
internal fun FriendActivityLabels(activity: FriendActivityDisplay, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val bidi = remember(rtl) { BidiFormatter.getInstance(rtl) }
    val direction = if (rtl) TextDirection.Rtl else TextDirection.Ltr
    val status = stringResource(if (activity.watching) R.string.trakt_friend_watching else R.string.trakt_friend_watched)
    val time = remember(activity.timestamp, context) {
        activity.timestamp?.let { timestamp ->
            runCatching { DateFormat.getTimeFormat(context).format(Date(Instant.parse(timestamp).toEpochMilli())) }
                .getOrNull()
        }
    }
    val details = listOfNotNull(activity.episode, time).joinToString(" - ")
    Column(modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        Text(
            text = "${bidi.unicodeWrap(activity.name)} $status",
            style = MaterialTheme.typography.titleSmall.copy(textDirection = direction),
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = if (details.isEmpty()) status else "$status ${bidi.unicodeWrap(details)}",
            style = MaterialTheme.typography.labelMedium.copy(textDirection = direction),
            color = NuvioTheme.colors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
