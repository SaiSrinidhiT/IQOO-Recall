package com.hackathon.recall.ui

import android.content.Context
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.hackathon.recall.R
import com.hackathon.recall.actions.PackBuilder
import com.hackathon.recall.data.DocumentEntity
import com.hackathon.recall.data.displayTitle
import com.hackathon.recall.data.effectiveType
import com.hackathon.recall.i18n.docTypeName
import com.hackathon.recall.i18n.templateName
import com.hackathon.recall.model.DocType
import com.hackathon.recall.search.OwnerIntent
import com.hackathon.recall.search.QueryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One question and its reply. [result] is null while the search runs. */
/** One question and its reply. [result] is null while working; [partial] holds a reply still streaming in. */
internal data class Turn(val id: Int, val query: String, val result: QueryResult? = null, val failed: Boolean = false, val partial: String = "")

/**
 * The open chat, kept in a ViewModel tied to this screen's back-stack entry: opening a document's
 * preview drops the screen's composition, and without this Back came home to an empty "Recent chats"
 * page and an answer still streaming was cancelled. Work runs in [viewModelScope] so it carries on.
 */
internal class ChatState : ViewModel() {
    val turns = mutableStateListOf<Turn>()
    var chatId by mutableLongStateOf(System.currentTimeMillis())
    /** A chip's question is sent once, not again every time the screen comes back. */
    var initialSent = false
    /** Auto-scroll only when the chat itself changes, so Back keeps the scroll position you left. */
    var scrolledFor = ""
}

/**
 * "Ask Recall": a multi-turn chat over the vault. Empty state shows recent searches and suggestions
 * built from what is in the vault; replies show matching documents with Open and Share as PDF.
 */
@Composable
fun ResultsScreen(nav: NavHostController, initialQuery: String) {
    val container = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state: ChatState = viewModel()
    val turns = state.turns
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val snackbar = remember { SnackbarHostState() }
    val docsFlow = remember { container.repository.observeDocuments() }
    val docs by docsFlow.collectAsState(emptyList())
    var history by remember { mutableStateOf<List<SavedChat>>(emptyList()) }
    var relations by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var sharingId by remember { mutableStateOf<Long?>(null) }
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        container.packBuilder.deleteSharedFiles()
    }

    LaunchedEffect(Unit) {
        history = ChatHistory.load(container.database)
        relations = People.relations(container.database)
    }

    /** Saves every finished turn of the open chat, so reopening it shows the replies, not a blank start. */
    suspend fun persist() {
        val saved = turns.mapNotNull { ChatHistory.snapshot(it.query, it.result, it.failed) }
        history = ChatHistory.save(container.database, SavedChat(state.chatId, System.currentTimeMillis(), saved))
    }

    fun newChat() {
        turns.clear()
        state.chatId = System.currentTimeMillis()
    }

    fun openChat(saved: SavedChat) {
        state.viewModelScope.launch {
            val restored = saved.turns.mapIndexed { i, t ->
                val result = ChatHistory.restore(t, container.repository)
                Turn(i + 1, t.query, result, failed = result == null)
            }
            turns.clear()
            turns.addAll(restored)
            state.chatId = saved.id
        }
    }

    fun send(raw: String) {
        val query = raw.trim()
        if (query.isEmpty()) return
        input = ""
        val id = (turns.lastOrNull()?.id ?: 0) + 1
        val conversation = state.chatId
        turns += Turn(id, query)
        state.viewModelScope.launch {
            val ownerFilter = OwnerIntent.resolve(query, relations)
            // Tokens arrive on the model's thread; hop to the UI thread and append while still streaming.
            val onToken: (String) -> Unit = { piece ->
                state.viewModelScope.launch {
                    val i = turns.indexOfFirst { it.id == id }
                    if (i >= 0 && turns[i].result == null) turns[i] = turns[i].copy(partial = turns[i].partial + piece)
                }
            }
            val result = runCatching {
                withContext(Dispatchers.Default) { container.queryEngine.ask(query, ownerFilter = ownerFilter, onToken = onToken) }
            }
            val i = turns.indexOfFirst { it.id == id }
            if (i >= 0) turns[i] = turns[i].copy(result = result.getOrNull(), failed = result.isFailure)
            // Skip if the user started or opened another chat while this answer was on its way.
            if (state.chatId == conversation) persist()
        }
    }

    fun share(doc: DocumentEntity) {
        if (sharingId != null) return
        sharingId = doc.id
        scope.launch {
            val built = container.packBuilder.buildDocument(doc.id)
            sharingId = null
            when (built) {
                is PackBuilder.Result.Ready -> shareLauncher.launch(container.packBuilder.shareIntent(built.file))
                is PackBuilder.Result.Blocked -> snackbar.showSnackbar(context.getString(R.string.pack_blocked, built.document, built.reason))
                is PackBuilder.Result.Failed -> snackbar.showSnackbar(context.getString(R.string.pack_failed, built.reason))
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank() && !state.initialSent) {
            state.initialSent = true
            send(initialQuery)
        }
    }
    val last = turns.lastOrNull()
    val showFollowUps = last != null && (last.result != null || last.failed)
    val followUps = remember(docs, turns.size) {
        val asked = turns.map { it.query.lowercase() }.toSet()
        suggestions(context, docs).filter { it.lowercase() !in asked }.take(3)
    }
    LaunchedEffect(turns.size, last?.result, showFollowUps) {
        val key = "${state.chatId}:${turns.size}:${last?.result != null}:$showFollowUps"
        if (turns.isNotEmpty() && key != state.scrolledFor) {
            state.scrolledFor = key
            listState.animateScrollToItem(if (showFollowUps) turns.size else turns.size - 1)
        }
    }
    // Keep the newest words in view while a reply streams in.
    LaunchedEffect(last?.partial?.length) {
        if (last != null && last.result == null && last.partial.isNotEmpty()) listState.scrollToItem(turns.size - 1, Int.MAX_VALUE)
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            ChatHeader(onBack = { nav.popBackStack() }, onNewChat = if (turns.isEmpty()) null else ::newChat)

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (turns.isEmpty()) {
                    EmptyChat(
                        history = history,
                        suggestions = remember(docs) { suggestions(context, docs) },
                        onPick = ::send,
                        onOpenChat = ::openChat,
                        onDeleteChat = { chat -> scope.launch { history = ChatHistory.delete(container.database, chat.id) } },
                        onClearHistory = { scope.launch { history = ChatHistory.clear(container.database) } },
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                    ) {
                        items(turns, key = { it.id }) { turn ->
                            TurnView(
                                turn = turn,
                                sharingId = sharingId,
                                onOpen = { nav.navigate(Routes.doc(it.id)) },
                                onShare = ::share,
                                onChecklist = { nav.navigate(Routes.checklist(it)) },
                                onScan = { nav.navigate(Routes.scan()) },
                            )
                        }
                        if (showFollowUps && followUps.isNotEmpty()) {
                            item(key = "follow-ups") { FollowUps(followUps, onPick = ::send) }
                        }
                    }
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ChatInput(value = input, onValueChange = { input = it }, onSend = { send(input) }, modifier = Modifier.navigationBarsPadding())
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).imePadding().padding(bottom = 88.dp))
    }
}

@Composable
private fun ChatHeader(onBack: () -> Unit, onNewChat: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(36.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back), modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Text(stringResource(R.string.chat_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (onNewChat != null) TextButton(onClick = onNewChat) { Text(stringResource(R.string.chat_new), fontWeight = FontWeight.Normal) }
    }
}

@Composable
private fun EmptyChat(
    history: List<SavedChat>,
    suggestions: List<String>,
    onPick: (String) -> Unit,
    onOpenChat: (SavedChat) -> Unit,
    onDeleteChat: (SavedChat) -> Unit,
    onClearHistory: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.chat_empty_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Normal)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.chat_empty_subtitle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.chat_recent), Modifier.weight(1f))
                TextButton(onClick = onClearHistory) { Text(stringResource(R.string.chat_clear), fontWeight = FontWeight.Normal) }
            }
            history.forEach { chat -> HistoryRow(chat, onOpen = { onOpenChat(chat) }, onDelete = { onDeleteChat(chat) }) }
        }

        Spacer(Modifier.height(24.dp))
        SectionLabel(stringResource(R.string.chat_suggestions))
        Spacer(Modifier.height(4.dp))
        suggestions.forEach { SuggestionRow(it, onPick) }
    }
}

/** A past conversation: its first question, how long it is and when it was last used. Tap to reopen. */
@Composable
private fun HistoryRow(chat: SavedChat, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val ago = DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                context.resources.getQuantityString(R.plurals.chat_turns, chat.turns.size, chat.turns.size) + " · " + ago,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.chat_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
private fun SuggestionRow(text: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(text) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(14.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal)
    }
}

@Composable
private fun TurnView(
    turn: Turn,
    sharingId: Long?,
    onOpen: (DocumentEntity) -> Unit,
    onShare: (DocumentEntity) -> Unit,
    onChecklist: (String) -> Unit,
    onScan: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                turn.query,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Normal,
                modifier = Modifier.widthIn(max = 300.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }

        val result = turn.result
        Row(verticalAlignment = Alignment.Top) {
            AssistantAvatar()
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f).padding(top = 3.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AssistantReply(turn, result, sharingId, onOpen, onShare, onChecklist, onScan)
            }
        }
    }
}

@Composable
private fun AssistantReply(
    turn: Turn,
    result: QueryResult?,
    sharingId: Long?,
    onOpen: (DocumentEntity) -> Unit,
    onShare: (DocumentEntity) -> Unit,
    onChecklist: (String) -> Unit,
    onScan: () -> Unit,
) {
    val context = LocalContext.current
    when {
        turn.failed -> Reply(stringResource(R.string.chat_error))
        result == null && turn.partial.isNotBlank() -> Reply(turn.partial.trimStart() + " ▍")
        result == null -> TypingIndicator()
        result is QueryResult.Chat -> Reply(result.reply)
        result is QueryResult.Found -> {
            val cited = result.answer.citedDocIds.toSet()
            val hits = result.hits.sortedByDescending { it.doc.id in cited }.map { it.doc }
            if (hits.isEmpty()) {
                Reply(stringResource(R.string.chat_not_found))
                OutlinedButton(onClick = onScan) { Text(stringResource(R.string.chat_scan), fontWeight = FontWeight.Normal) }
            } else {
                if (result.answer.text.isNotBlank()) Reply(result.answer.text)
                val wantsPdf = turn.query.contains("pdf", ignoreCase = true) || turn.query.contains("पीडीएफ") || turn.query.contains("పీడీఎఫ్")
                hits.forEachIndexed { i, doc ->
                    DocResultCard(doc, sharing = sharingId == doc.id, primaryShare = wantsPdf && i == 0, onOpen = { onOpen(doc) }, onShare = { onShare(doc) })
                }
            }
        }
        result is QueryResult.Pack -> {
            Reply(stringResource(R.string.chat_pack_ready, context.templateName(result.templateId)))
            OutlinedButton(onClick = { onChecklist(result.templateId) }) { Text(stringResource(R.string.chat_open_checklist), fontWeight = FontWeight.Normal) }
        }
        result is QueryResult.Reminders -> {
            Reply(stringResource(if (result.docs.isEmpty()) R.string.no_expiry_docs else R.string.chat_expiring))
            result.docs.forEach { doc -> DocResultCard(doc, sharing = sharingId == doc.id, primaryShare = false, onOpen = { onOpen(doc) }, onShare = { onShare(doc) }) }
        }
    }
}

@Composable
private fun Reply(text: String) {
    Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Normal, color = MaterialTheme.colorScheme.onBackground)
}

/** Recall's mark beside every reply, in the launcher icon's colours (drawn, not decoded from the PNG). */
@Composable
private fun AssistantAvatar() {
    Box(
        Modifier.size(28.dp).background(Brush.linearGradient(listOf(Color(0xFFFFC928), Color(0xFFFF7A1A))), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Default.Search, contentDescription = null, tint = Color.White, modifier = Modifier.size(15.dp)) }
}

/** Three pulsing dots while Recall works out the question and searches. */
@Composable
private fun TypingIndicator() {
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier.background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { i ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 500, delayMillis = i * 160), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

/** Tap-to-ask follow-ups under the latest reply, so the conversation keeps going without typing. */
@Composable
private fun FollowUps(options: List<String>, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 38.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { text ->
            Surface(
                onClick = { onPick(text) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
private fun DocResultCard(doc: DocumentEntity, sharing: Boolean, primaryShare: Boolean, onOpen: () -> Unit, onShare: () -> Unit) {
    val context = LocalContext.current
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
    ) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumbnail(doc, Modifier.size(56.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(context.docTypeName(doc.effectiveType()), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (doc.displayTitle().isNotBlank()) {
                    Text(doc.displayTitle(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                doc.expiryOn?.let { ExpiryBadge(LocalDate.parse(it)) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen) { Text(stringResource(R.string.chat_open), fontWeight = FontWeight.Normal) }
            Spacer(Modifier.width(4.dp))
            if (sharing) {
                CircularProgressIndicator(Modifier.padding(horizontal = 20.dp).size(18.dp), strokeWidth = 2.dp)
            } else if (primaryShare) {
                Button(onClick = onShare, shape = RoundedCornerShape(12.dp)) { Text(stringResource(R.string.chat_share_pdf), fontWeight = FontWeight.Normal) }
            } else {
                TextButton(onClick = onShare) { Text(stringResource(R.string.chat_share_pdf), fontWeight = FontWeight.Normal) }
            }
        }
    }
}

@Composable
private fun ChatInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.chat_hint), fontWeight = FontWeight.Normal) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Normal),
            maxLines = 4,
            shape = RoundedCornerShape(24.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                focusedTextColor = MaterialTheme.colorScheme.onSurface,
                unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.action_ask), modifier = Modifier.size(18.dp))
        }
    }
}

/** Up to six suggestions: the vault's most common document types, expiries, then fixed examples. */
private fun suggestions(context: Context, docs: List<DocumentEntity>): List<String> {
    val out = LinkedHashSet<String>()
    docs.groupingBy { it.effectiveType().name }.eachCount().entries
        .sortedByDescending { it.value }
        .mapNotNull { DocType.parse(it.key) }
        .filter { it != DocType.OTHER_DOCUMENT }
        .take(3)
        .forEach { out += context.getString(R.string.suggest_show_type, context.docTypeName(it)) }
    if (docs.any { it.expiryOn != null }) out += context.getString(R.string.suggest_expiring)
    listOf(R.string.suggest_car_insurance, R.string.suggest_resume_pdf, R.string.suggest_salary, R.string.suggest_home_loan)
        .forEach { out += context.getString(it) }
    return out.take(6)
}
