package com.nova.iptv.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.nova.iptv.R
import com.nova.iptv.data.db.SourceType
import com.nova.iptv.data.db.SubscriptionEntity
import com.nova.iptv.data.repo.SettingsRepository
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.ui.theme.LocalNovaFlags
import com.nova.iptv.ui.theme.NovaBlue
import com.nova.iptv.ui.theme.NovaViolet
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    subs: SubscriptionRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val subscriptions: StateFlow<List<SubscriptionEntity>?> =
        subs.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val activeId: StateFlow<Long> = settings.activeSubscriptionId.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    fun choose(id: Long, then: () -> Unit) {
        viewModelScope.launch {
            settings.setActiveSubscription(id)
            then()
        }
    }
}

/** Avatar palettes — each subscription gets its own colour identity. */
private val Palettes = listOf(
    listOf(Color(0xFF4F7BFF), Color(0xFF9B6CFF)),
    listOf(Color(0xFFFF5E8A), Color(0xFFFF9F5A)),
    listOf(Color(0xFF16C7A8), Color(0xFF2D8CFF)),
    listOf(Color(0xFFFFB648), Color(0xFFFF5E5E)),
    listOf(Color(0xFFB45CFF), Color(0xFFFF5CC8)),
    listOf(Color(0xFF3DDC84), Color(0xFF0FA3B1)),
)

/**
 * "Who's watching?" — the first screen on every launch.
 * Each subscription is a profile with its own favourites, history and continue-watching.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfilesScreen(
    onChosen: () -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    vm: ProfilesViewModel = hiltViewModel(),
) {
    val subs by vm.subscriptions.collectAsStateWithLifecycle()
    val activeId by vm.activeId.collectAsStateWithLifecycle()
    val tv = LocalNovaFlags.current.isTv
    val list = subs ?: return

    // Entrance: logo first, then title, then tiles one after another.
    val intro = remember { Animatable(0f) }
    LaunchedEffect(Unit) { intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(list.size) {
        if (tv && list.isNotEmpty()) {
            delay(400)
            runCatching { firstFocus.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF050509))) {
        AmbientGlow()
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(if (tv) 24.dp else 48.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer {
                    alpha = intro.value
                    translationY = (1f - intro.value) * -40f
                },
            ) {
                NovaLogo(if (tv) 56 else 48)
                Spacer(Modifier.width(14.dp))
                Text(
                    "NOVA",
                    color = Color.White,
                    fontSize = if (tv) 40.sp else 34.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 6.sp,
                )
            }
            Spacer(Modifier.height(36.dp))
            Text(
                stringResource(R.string.who_is_watching),
                color = Color.White,
                fontSize = if (tv) 44.sp else 34.sp,
                fontWeight = FontWeight.ExtraBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(intro.value),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.who_is_watching_hint),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(intro.value),
            )
            Spacer(Modifier.height(if (tv) 48.dp else 40.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(if (tv) 36.dp else 22.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(28.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                list.forEachIndexed { i, sub ->
                    ProfileTile(
                        sub = sub,
                        palette = Palettes[i % Palettes.size],
                        active = sub.id == activeId,
                        index = i,
                        modifier = if (i == 0) Modifier.focusRequester(firstFocus) else Modifier,
                        onClick = { vm.choose(sub.id, onChosen) },
                    )
                }
                AddTile(index = list.size, onClick = onAdd)
            }

            Spacer(Modifier.height(48.dp))
            ManageButton(onManage)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Slowly drifting coloured light behind everything. */
@Composable
private fun AmbientGlow() {
    val t = rememberInfiniteTransition(label = "glow")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(18_000, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val r = maxOf(w, h) * 0.65f
        drawCircle(
            Brush.radialGradient(
                listOf(NovaViolet.copy(alpha = 0.38f), Color.Transparent),
                center = Offset(w * (0.15f + 0.2f * a), h * (0.18f + 0.1f * b)), radius = r,
            ),
            radius = r, center = Offset(w * (0.15f + 0.2f * a), h * (0.18f + 0.1f * b)),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(NovaBlue.copy(alpha = 0.30f), Color.Transparent),
                center = Offset(w * (0.85f - 0.2f * b), h * (0.85f - 0.15f * a)), radius = r,
            ),
            radius = r, center = Offset(w * (0.85f - 0.2f * b), h * (0.85f - 0.15f * a)),
        )
        drawCircle(
            Brush.radialGradient(
                listOf(Color(0xFFFF4D8D).copy(alpha = 0.14f), Color.Transparent),
                center = Offset(w * (0.6f + 0.15f * a), h * 0.35f), radius = r * 0.7f,
            ),
            radius = r * 0.7f, center = Offset(w * (0.6f + 0.15f * a), h * 0.35f),
        )
        // Vignette.
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f)), startY = h * 0.55f))
    }
}

@Composable
private fun rememberEntrance(index: Int): Float {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(250L + index * 90L)
        anim.animateTo(1f, tween(600, easing = FastOutSlowInEasing))
    }
    return anim.value
}

@Composable
private fun ProfileTile(
    sub: SubscriptionEntity,
    palette: List<Color>,
    active: Boolean,
    index: Int,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val tv = LocalNovaFlags.current.isTv
    val size = if (tv) 150.dp else 112.dp
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        when {
            pressed -> 0.94f
            focused -> 1.1f
            else -> 1f
        }, tween(220), label = "scale",
    )
    val enter = rememberEntrance(index)
    val shape = RoundedCornerShape(if (tv) 40.dp else 32.dp)
    val initial = sub.name.trim().firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "N"

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(size + 24.dp)
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * 60f
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
            // Glow ring when focused / last used.
            if (focused || active) {
                Box(
                    Modifier
                        .size(size + 12.dp)
                        .scale(scale)
                        .border(
                            width = if (focused) 3.dp else 2.dp,
                            brush = Brush.linearGradient(if (focused) listOf(Color.White, Color.White) else palette),
                            shape = RoundedCornerShape(if (tv) 46.dp else 38.dp),
                        )
                )
            }
            Box(
                Modifier
                    .size(size)
                    .scale(scale)
                    .shadow(if (focused) 30.dp else 16.dp, shape, ambientColor = palette[1], spotColor = palette[0])
                    .clip(shape)
                    .background(Brush.linearGradient(palette))
                    .drawBehind {
                        // Soft highlight in the top corner for depth.
                        drawCircle(
                            Brush.radialGradient(
                                listOf(Color.White.copy(alpha = 0.35f), Color.Transparent),
                                center = Offset(this.size.width * 0.25f, this.size.height * 0.2f),
                                radius = this.size.width * 0.7f,
                            ),
                            radius = this.size.width * 0.7f,
                            center = Offset(this.size.width * 0.25f, this.size.height * 0.2f),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    initial,
                    color = Color.White,
                    fontSize = if (tv) 64.sp else 48.sp,
                    fontWeight = FontWeight.Black,
                )
                Text(
                    if (sub.type == SourceType.XTREAM) "XTREAM" else "M3U",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 10.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.25f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            if (active) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .border(2.dp, Color(0xFF050509), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Check, null, tint = palette[0], modifier = Modifier.size(16.dp)) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            sub.name,
            color = if (focused) Color.White else Color.White.copy(alpha = 0.9f),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        val status = expiryLabel(sub.expiresAt)
        if (status != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                status.first,
                color = status.second,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun expiryLabel(expiresAt: Long): Pair<String, Color>? {
    if (expiresAt <= 0) return null
    val days = ((expiresAt - System.currentTimeMillis()) / 86_400_000L).toInt()
    return when {
        days < 0 -> stringResource(R.string.profile_expired) to Color(0xFFFF6B7A)
        days <= 7 -> stringResource(R.string.profile_days_left, days) to Color(0xFFFFB648)
        else -> stringResource(R.string.profile_days_left, days) to Color.White.copy(alpha = 0.5f)
    }
}

@Composable
private fun AddTile(index: Int, onClick: () -> Unit) {
    val tv = LocalNovaFlags.current.isTv
    val size = if (tv) 150.dp else 112.dp
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, tween(220), label = "scale")
    val enter = rememberEntrance(index)
    val radius = if (tv) 40.dp else 32.dp
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(size + 24.dp)
            .graphicsLayer {
                alpha = enter * 0.95f
                translationY = (1f - enter) * 60f
            }
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(size)
                    .scale(scale)
                    .clip(RoundedCornerShape(radius))
                    .background(Color.White.copy(alpha = if (focused) 0.14f else 0.05f))
                    .drawBehind {
                        val stroke = 2.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = if (focused) 0.9f else 0.35f),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(radius.toPx()),
                            style = Stroke(stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))),
                            topLeft = Offset(stroke / 2, stroke / 2),
                            size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, null, tint = Color.White, modifier = Modifier.size(if (tv) 52.dp else 40.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.add_subscription),
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun ManageButton(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(Color.White.copy(alpha = if (focused) 0.18f else 0.06f))
            .border(1.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.25f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 26.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Settings, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.manage_subscriptions), color = Color.White, style = MaterialTheme.typography.titleSmall)
    }
}
