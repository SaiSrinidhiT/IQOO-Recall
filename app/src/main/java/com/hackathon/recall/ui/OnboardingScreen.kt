package com.hackathon.recall.ui

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.work.WorkManager
import com.hackathon.recall.R
import com.hackathon.recall.ingest.IndexWorker

/** Whether the one-time first-run screen has been shown (docs/DECISIONS.md-adjacent: a plain flag is enough, no vault access needed). */
object Onboarding {
    private const val PREFS = "settings"
    private const val KEY = "onboarding_complete"
    fun completed(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setCompleted(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, true).apply()
}

private enum class OnboardingStep { INTRO, GRANTED, DENIED }

/**
 * First-run gate (RecallRoot, after the vault unlocks): explains the scan, then requests photo
 * access up front instead of leaving it to a card buried in Home. Granting it kicks off IndexWorker
 * immediately, same as the fallback prompt still shown on Home/Settings for anyone who skips here.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(OnboardingStep.INTRO) }
    val flow = remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(IndexWorker.UNIQUE) }
    val work by flow.collectAsState(emptyList())
    val indexing = work.any { !it.state.isFinished }
    // Until the new run shows up, WorkManager may still report the previous (finished) run; don't flash "Done".
    var sawIndexing by remember { mutableStateOf(false) }
    LaunchedEffect(indexing) { if (indexing) sawIndexing = true }

    val photoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = if (photoAccess(context) != PhotoAccess.NONE) {
            IndexWorker.enqueue(context, userInitiated = true)
            OnboardingStep.GRANTED
        } else {
            OnboardingStep.DENIED
        }
    }

    fun finish() {
        Onboarding.setCompleted(context)
        onDone()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp)) {
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(
                stringResource(R.string.onboarding_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.onboarding_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.onboarding_privacy), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            when (step) {
                OnboardingStep.GRANTED -> {
                    Spacer(Modifier.height(28.dp))
                    IndexStatus(showSummaryWhenDone = sawIndexing && !indexing)
                }
                OnboardingStep.DENIED -> {
                    Spacer(Modifier.height(28.dp))
                    Text(
                        stringResource(R.string.onboarding_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                OnboardingStep.INTRO -> {}
            }
        }
        }

        when (step) {
            OnboardingStep.INTRO -> {
                Button(
                    onClick = { photoLauncher.launch(photoPermissions()) },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.action_allow_access)) }
                TextButton(onClick = ::finish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_not_now)) }
            }
            else -> {
                Button(
                    onClick = ::finish,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) { Text(stringResource(R.string.action_continue)) }
            }
        }
    }
}
