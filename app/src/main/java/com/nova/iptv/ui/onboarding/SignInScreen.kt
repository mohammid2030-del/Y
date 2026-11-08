package com.nova.iptv.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nova.iptv.R
import com.nova.iptv.data.account.AccountRepository
import com.nova.iptv.data.cloud.Cloud
import com.nova.iptv.data.repo.SubscriptionRepository
import com.nova.iptv.ui.components.novaFocus
import com.nova.iptv.ui.theme.LocalNovaFlags
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignInViewModel @Inject constructor(
    private val cloud: Cloud,
    private val account: AccountRepository,
    private val subs: SubscriptionRepository,
) : ViewModel() {
    sealed interface State {
        object Loading : State
        data class Code(val code: Cloud.DeviceCode) : State
        data class Failed(val reason: String) : State
        object Denied : State
        data class Done(val email: String, val hasSubs: Boolean) : State
    }
    var state by mutableStateOf<State>(State.Loading)
        private set
    private var job: Job? = null

    init { start() }

    fun start() {
        job?.cancel()
        state = State.Loading
        job = viewModelScope.launch {
            val code = cloud.startGoogle() ?: run { state = State.Failed(cloud.lastError); return@launch }
            state = State.Code(code)
            while (true) {
                delay(code.interval * 1000L)
                when (val r = cloud.pollGoogle(code)) {
                    is Cloud.Login.Ok -> {
                        account.onSignedIn(r.email)
                        runCatching { account.pull() }
                        state = State.Done(r.email, subs.getAll().isNotEmpty())
                        return@launch
                    }
                    Cloud.Login.Pending -> Unit
                    Cloud.Login.Expired -> { start(); return@launch }
                    Cloud.Login.Denied -> { state = State.Denied; return@launch }
                    Cloud.Login.Failed -> { state = State.Failed(cloud.lastError); return@launch }
                }
            }
        }
    }

    suspend fun hasSubs() = subs.getAll().isNotEmpty()
}

/** Turns Google's error code into a hint about what to fix (same hints as Apple and Samsung). */
@Composable
fun googleHint(e: String): String {
    val l = e.lowercase()
    val hint = when {
        l == "not_configured" -> return stringResource(R.string.g_err_not_configured)
        l == "network" -> return stringResource(R.string.g_err_network)
        "invalid_client" in l || "unauthorized_client" in l -> stringResource(R.string.g_err_client)
        "restricted" in l || "consent" in l || "access_denied" in l -> stringResource(R.string.g_err_consent)
        "operation_not_allowed" in l || "not enabled" in l -> stringResource(R.string.g_err_provider)
        "invalid_idp_response" in l || "audience" in l -> stringResource(R.string.g_err_audience)
        else -> return e
    }
    return "$hint ($e)"
}

/** Google sign-in: a short code on screen, entered on the phone at google.com/device (or a QR). */
@Composable
fun SignInScreen(
    onDone: (hasSubs: Boolean) -> Unit,
    onSkip: () -> Unit,
    skipIsLater: Boolean,
    vm: SignInViewModel = hiltViewModel(),
) {
    val tv = LocalNovaFlags.current.isTv
    val ctx = LocalContext.current
    val st = vm.state
    LaunchedEffect(st) { if (st is SignInViewModel.State.Done) onDone(st.hasSubs) }
    val skipFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { delay(300); runCatching { skipFocus.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF050509))) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            NovaLogo(if (tv) 56 else 48)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.sign_in_google), color = Color.White, fontSize = if (tv) 38.sp else 28.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.google_why), color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
            Spacer(Modifier.height(28.dp))
            Box(Modifier.height(if (tv) 300.dp else 360.dp), contentAlignment = Alignment.Center) {
                when (st) {
                    SignInViewModel.State.Loading, is SignInViewModel.State.Done -> CircularProgressIndicator()
                    is SignInViewModel.State.Code -> {
                        val c = st.code
                        val link = c.url.removePrefix("https://")
                        val content: @Composable () -> Unit = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("1. " + stringResource(R.string.google_step1) + " " + link, color = Color.White, fontSize = if (tv) 24.sp else 18.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(6.dp))
                                Text("2. " + stringResource(R.string.google_step2), color = Color.White, fontSize = if (tv) 24.sp else 18.sp, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(14.dp))
                                Text(
                                    c.user, color = Color.White, fontSize = if (tv) 56.sp else 40.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 28.dp, vertical = 10.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text("  " + stringResource(R.string.google_waiting), color = Color.White.copy(alpha = 0.6f))
                                }
                            }
                        }
                        if (tv) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                            QrCode(c.url, Modifier.size(220.dp)); content()
                        } else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            content()
                            Spacer(Modifier.height(14.dp))
                            OutlinedButton(onClick = {
                                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(c.url))) }
                            }) { Text(stringResource(R.string.google_open_page)) }
                        }
                    }
                    is SignInViewModel.State.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.google_failed), color = MaterialTheme.colorScheme.error, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(googleHint(st.reason), color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 720.dp))
                    }
                    SignInViewModel.State.Denied -> Text(stringResource(R.string.google_denied), color = MaterialTheme.colorScheme.error, fontSize = 20.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedButton(onClick = { vm.start() }, modifier = Modifier.novaFocus(RoundedCornerShape(50))) { Text(stringResource(R.string.google_new_code)) }
                Button(onClick = onSkip, modifier = Modifier.focusRequester(skipFocus).novaFocus(RoundedCornerShape(50))) {
                    Text(stringResource(if (skipIsLater) R.string.later else R.string.add_manually))
                }
            }
        }
    }
}

@Composable
private fun QrCode(text: String, modifier: Modifier) {
    val matrix = remember(text) { runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }.getOrNull() } ?: return
    Canvas(modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(10.dp)) {
        val n = matrix.width
        val cell = size.minDimension / n
        for (y in 0 until n) for (x in 0 until n) if (matrix.get(x, y)) {
            drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
        }
    }
}
