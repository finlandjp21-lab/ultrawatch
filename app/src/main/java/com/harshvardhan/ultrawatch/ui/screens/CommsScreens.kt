package com.harshvardhan.ultrawatch.ui.screens

import android.Manifest
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import com.harshvardhan.ultrawatch.comms.ChatMessage
import com.harshvardhan.ultrawatch.comms.MessageStore
import com.harshvardhan.ultrawatch.comms.Phone
import com.harshvardhan.ultrawatch.core.Contact
import com.harshvardhan.ultrawatch.core.ContactMatcher
import com.harshvardhan.ultrawatch.core.ReplySuggester
import com.harshvardhan.ultrawatch.ui.*
import java.text.DateFormat
import java.util.Date

@Composable
fun CallsScreen() {
    val ctx = LocalContext.current
    var contacts by remember { mutableStateOf<List<Contact>>(emptyList()) }
    var dial by remember { mutableStateOf("") }
    var showPad by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf<String?>(null) }
    val load = rememberPermissionGate(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), then = { contacts = Phone.contacts(ctx) })
    androidx.compose.runtime.LaunchedEffect(Unit) { load() }
    val voice = rememberVoiceInput("Say a name") { query = it }
    val shown = query?.let { q -> contacts.filter { ContactMatcher.score(q, it) >= 50 }.sortedByDescending { ContactMatcher.score(q, it) } }
        ?: (contacts.filter { it.starred } + contacts.filter { !it.starred })

    AppScreen("Calls") {
        if (!Phone.hasTelephony(ctx)) item { InfoText("Calls go through your paired phone; talk on the watch speaker or earbuds.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SmallChip("Search by voice") { voice() }
                SmallChip(if (showPad) "Hide keypad" else "Keypad") { showPad = !showPad }
            }
        }
        if (showPad) {
            item { Text(dial.ifEmpty { "Enter number" }, fontSize = 18.sp) }
            listOf("123", "456", "789", "+0⌫").forEach { row ->
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { k -> SmallChip(k.toString()) { dial = if (k == '⌫') dial.dropLast(1) else dial + k } }
                    }
                }
            }
            item { ActionChip("Call", dial, primary = true) { if (dial.isNotBlank()) Phone.call(ctx, dial) } }
        }
        query?.let { q -> item { InfoText("Results for \"$q\"") } }
        shown.take(60).forEach { c ->
            item { ActionChip(c.name + if (c.starred) " ★" else "", c.number) { Phone.call(ctx, c.number) } }
        }
        if (contacts.isEmpty()) item { InfoText("Allow contacts access to see your favourites here.") }
    }
}

@Composable
fun MessagesScreen() {
    val ctx = LocalContext.current
    val messages by MessageStore.messages.collectAsState()
    var open by remember { mutableStateOf<ChatMessage?>(null) }
    var newTo by remember { mutableStateOf<Contact?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val current = open
    if (current != null) { MessageDetail(current) { open = null }; return }

    val pickContact = rememberVoiceInput("Who do you want to text?") { said ->
        newTo = ContactMatcher.bestMatch(said, Phone.contacts(ctx))
        status = if (newTo == null) "No contact named $said" else null
    }
    val dictate = rememberVoiceInput("Say your message") { text ->
        val to = newTo ?: return@rememberVoiceInput
        status = if (Phone.sendSms(ctx, to.number, text)) "Sent to ${to.name}" else { Phone.composeSms(ctx, to.number, text); "Confirm on the messages screen" }
        newTo = null
    }
    val contactsGate = rememberPermissionGate(arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS), then = { pickContact() })

    AppScreen("Messages") {
        if (!MessageStore.listenerEnabled(ctx)) {
            item { InfoText("Allow UltraWatch to read message notifications to see and reply to chats from any app.") }
            item { ActionChip("Allow access", primary = true) { ctx.startActivity(MessageStore.openListenerSettings()) } }
        }
        item { ActionChip(newTo?.let { "Speak message to ${it.name}" } ?: "New message", status, primary = newTo != null) {
            if (newTo != null) dictate() else contactsGate()
        } }
        if (messages.isEmpty()) item { InfoText("No messages yet") }
        messages.forEach { m ->
            item { ActionChip("${m.sender} · ${m.app}", m.text) { open = m } }
        }
    }
}

@Composable
private fun MessageDetail(m: ChatMessage, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var sent by remember { mutableStateOf<String?>(null) }
    fun send(text: String) { sent = if (MessageStore.reply(ctx, m.key, text)) "Sent: $text" else "This app doesn't allow replies from the watch" }
    val voice = rememberVoiceInput("Reply to ${m.sender}") { send(it) }
    val keyboard = rememberTextInput("Reply") { send(it) }
    val time = remember(m.time) { DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(m.time)) }

    AppScreen(m.sender) {
        item { InfoText("${m.app} · $time") }
        item { Text(m.text, fontSize = 15.sp) }
        sent?.let { item { InfoText(it, Brand.Green) } }
        if (m.canReply) {
            ReplySuggester.suggest(m.text).forEach { r -> item { ActionChip(r) { send(r) } } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SmallChip("Voice") { voice() }
                    SmallChip("Keyboard") { keyboard() }
                }
            }
        } else item { InfoText("Reply from your phone for this app.") }
        item { ActionChip("Back") { onClose() } }
    }
}
