package com.example.jsonflashcardapp2

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import kotlinx.coroutines.launch
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.OutlinedTextFieldDefaults
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.compose.ui.text.style.TextOverflow
import android.app.Service
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

// 1. データモデル
@Serializable
data class Flashcard(
    val word: String,
    val answer: String,
    val tags: List<String> = emptyList(),
    val isWrong: Boolean = false,
    val memo: String = ""
)

// 2. 保存管理クラス
class AppStorageManager(private val context: Context) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun getDeckList(): List<String> {
        return context.filesDir.listFiles { _, name -> name.endsWith(".json") }
            ?.map { it.nameWithoutExtension }?.sorted() ?: listOf("cards")
    }

    private fun getFileForDeck(deckName: String): File {
        return File(context.filesDir, "$deckName.json")
    }

    fun saveCards(deckName: String, cards: List<Flashcard>) {
        val jsonString = json.encodeToString(cards)
        getFileForDeck(deckName).writeText(jsonString)
    }

    fun loadCards(deckName: String): List<Flashcard> {
        val file = getFileForDeck(deckName)
        return try {
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString(jsonString)
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getJsonText(deckName: String): String = if (getFileForDeck(deckName).exists()) getFileForDeck(deckName).readText() else "[]"

    fun importJson(deckName: String, jsonString: String): Boolean {
        return try {
            val importedCards = json.decodeFromString<List<Flashcard>>(jsonString)
            saveCards(deckName, importedCards)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ④ デッキ削除ロジックの追加
    fun deleteDeck(deckName: String): Boolean {
        val file = getFileForDeck(deckName)
        return if (file.exists()) file.delete() else false
    }
}

// 3. メインActivity
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storageManager = AppStorageManager(this)
        setContent {
            val context = LocalContext.current
            val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
            var isDarkMode by remember { mutableStateOf(prefs.getBoolean("is_dark_mode", false)) }

            val colorScheme = if (isDarkMode) darkColorScheme() else lightColorScheme()

            MaterialTheme(colorScheme = colorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    FlashCardApp(
                        storageManager = storageManager,
                        isDarkMode = isDarkMode,
                        onDarkModeChanged = { dark ->
                            isDarkMode = dark
                            prefs.edit().putBoolean("is_dark_mode", dark).apply()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashCardApp(
    storageManager: AppStorageManager,
    isDarkMode: Boolean,
    onDarkModeChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    var selectedDeck by remember { mutableStateOf(prefs.getString("selected_deck", "cards") ?: "cards") }
    var deckList by remember { mutableStateOf(storageManager.getDeckList()) }
    var currentMode by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf(storageManager.loadCards(selectedDeck)) }
    var newDeckName by remember { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var showHelpDialog by remember { mutableStateOf(false) }
    var showDeleteConfirmation by remember { mutableStateOf(false) } // ④ 削除ダイアログ用

    BackHandler(enabled = currentMode != null) {
        currentMode = null
        cards = storageManager.loadCards(selectedDeck)
    }

    LaunchedEffect(selectedDeck) {
        cards = storageManager.loadCards(selectedDeck)
        prefs.edit().putString("selected_deck", selectedDeck).apply()
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            val importedDeckName = getDeckNameFromUri(context, it)
            context.contentResolver.openInputStream(it)?.use { stream ->
                val jsonText = stream.bufferedReader().use { reader -> reader.readText() }
                if (storageManager.importJson(importedDeckName, jsonText)) {
                    selectedDeck = importedDeckName
                    cards = storageManager.loadCards(importedDeckName)
                    deckList = storageManager.getDeckList()
                    Toast.makeText(context, "Imported as: $importedDeckName", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid JSON Format", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { stream ->
                stream.write(storageManager.getJsonText(selectedDeck).toByteArray())
                Toast.makeText(context, "Export Success!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("                 Flash Card App") },
                actions = {
                    IconButton(onClick = { onDarkModeChanged(!isDarkMode) }) {
                        Text(if (isDarkMode) "☀️" else "🌙", fontSize = 20.sp)
                    }
                    IconButton(onClick = { showHelpDialog = true }) {
                        Icon(Icons.Default.Info, contentDescription = "Help")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)
        ) {
            if (currentMode == null) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text("デッキ管理", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                            // ④ 横幅を維持しつつ、削除ボタンを配置したRow構造
                            Row(
                                modifier = Modifier.fillMaxWidth(0.85f),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Button(
                                        onClick = { showMenu = true },
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = CircleShape
                                    ) {
                                        // ② 長いデッキ名対策：1行に収めてあふれたら「...」にする
                                        Text(
                                            text = "Deck: $selectedDeck",
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false),
                                            fontSize = 14.sp
                                        )
                                        Icon(Icons.Default.ArrowDropDown, null)
                                    }
                                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                        deckList.forEach { deck ->
                                            DropdownMenuItem(
                                                text = {
                                                    Text(
                                                        text = deck,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                },
                                                onClick = {
                                                    selectedDeck = deck
                                                    showMenu = false
                                                }
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                // デッキ削除ボタン
                                IconButton(
                                    onClick = { showDeleteConfirmation = true }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Delete Deck", tint = Color.Red)
                                }
                            }

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center,
                                modifier = Modifier.fillMaxWidth(0.85f)
                            ) {
                                OutlinedTextField(
                                    value = newDeckName,
                                    onValueChange = {
                                        newDeckName = it
                                        if (it.isNotBlank()) showError = false
                                    },
                                    placeholder = { Text("New Deck Name", fontSize = 14.sp) },
                                    modifier = Modifier.weight(1f).height(56.dp),
                                    shape = CircleShape,
                                    isError = showError,
                                    singleLine = true
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Button(
                                    onClick = {
                                        if (newDeckName.isNotBlank()) {
                                            val name = newDeckName.trim()
                                            storageManager.saveCards(name, emptyList())
                                            deckList = storageManager.getDeckList()
                                            selectedDeck = name
                                            newDeckName = ""
                                            showError = false
                                        } else {
                                            showError = true
                                        }
                                    },
                                    modifier = Modifier.height(56.dp),
                                    shape = CircleShape
                                ) { Text("Create") }
                            }

                            Box(
                                modifier = Modifier.fillMaxWidth(0.85f).height(16.dp),
                                contentAlignment = Alignment.CenterStart
                            ) {
                                if (showError) {
                                    Text("デッキ名を入力してください", color = Color.Red, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp))
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("学習モード選択", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Color.Gray)

                        OutlinedButton(
                            onClick = { currentMode = "register" },
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                        ) { Text("Reg/Edit Mode") }

                        Button(
                            onClick = { currentMode = "study" },
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp)
                        ) { Text("Flash Mode", fontWeight = FontWeight.Bold) }

                        Button(
                            onClick = { currentMode = "audio" },
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary)
                        ) { Text("Audio Player Mode") }

                        OutlinedButton(
                            onClick = { currentMode = "json_editor" },
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Gray)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Raw JSON Editor")
                        }
                    }
                }
            } else {
                if (currentMode != "json_editor") { // JSONエディタ側で独自の戻る/保存を制御するため除外
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Button(onClick = { currentMode = null }) { Text("Return Menu") }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }

                when (currentMode) {
                    "register" -> {
                        RegistrationAndManagementScreen(
                            cards = cards,
                            onCardsUpdated = { updatedList ->
                                cards = updatedList
                                storageManager.saveCards(selectedDeck, updatedList)
                            },
                            onImportClick = { importLauncher.launch("application/json") },
                            onExportClick = { exportLauncher.launch("$selectedDeck.json") }
                        )
                    }
                    "study" -> StudyScreen(
                        deckName = selectedDeck,
                        cards = cards,
                        isDarkMode = isDarkMode,
                        onCardUpdated = { index, isWrong ->
                            val newList = cards.toMutableList()
                            newList[index] = newList[index].copy(isWrong = isWrong)
                            cards = newList
                            storageManager.saveCards(selectedDeck, newList)
                        }
                    )
                    "audio" -> AudioPlayerScreen(deckName = selectedDeck, cards = cards, isDarkMode = isDarkMode)
                    "game" -> Box(modifier = Modifier.fillMaxSize().weight(1f)) { GameScreen() }
                    "json_editor" -> {
                        JsonEditorScreen(
                            deckName = selectedDeck,
                            storageManager = storageManager,
                            onBack = {
                                currentMode = null
                                cards = storageManager.loadCards(selectedDeck)
                            }
                        )
                    }
                }
            }
        }
    }

    // ④ デッキ削除確認ダイアログ
    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("デッキの削除") },
            text = { Text("デッキ「$selectedDeck」を完全に削除しますか？この操作は取り消せません。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        storageManager.deleteDeck(selectedDeck)
                        deckList = storageManager.getDeckList()
                        // リストが空になったら自動でデフォルトの「cards」を作り直して選択
                        selectedDeck = deckList.firstOrNull() ?: "cards"
                        if (deckList.isEmpty()) {
                            storageManager.saveCards("cards", emptyList())
                            deckList = storageManager.getDeckList()
                            selectedDeck = "cards"
                        }
                        cards = storageManager.loadCards(selectedDeck)
                        showDeleteConfirmation = false
                        Toast.makeText(context, "デッキを削除しました", Toast.LENGTH_SHORT).show()
                    }
                ) { Text("削除", color = Color.Red) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) { Text("キャンセル") }
            }
        )
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("使いかた") },
            text = { Text("既存のデッキを選択するか、新しいデッキ名を入力して作成してください。その後、各モードを選択して学習を開始します。") },
            confirmButton = {
                TextButton(onClick = { showHelpDialog = false }) { Text("閉じる") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegistrationAndManagementScreen(
    cards: List<Flashcard>,
    onCardsUpdated: (List<Flashcard>) -> Unit,
    onImportClick: () -> Unit,
    onExportClick: () -> Unit
) {
    var word by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var memo by remember { mutableStateOf("") }
    var tagsInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredCards = cards.filter {
        it.word.contains(searchQuery, ignoreCase = true) ||
                it.answer.contains(searchQuery, ignoreCase = true) ||
                it.memo.contains(searchQuery, ignoreCase = true) ||
                it.tags.any { tag -> tag.contains(searchQuery, ignoreCase = true) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                elevation = CardDefaults.cardElevation(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("New Word Registration", fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    TextField(value = word, onValueChange = { word = it }, label = { Text("Word") }, modifier = Modifier.fillMaxWidth())
                    TextField(value = answer, onValueChange = { answer = it }, label = { Text("Answer") }, modifier = Modifier.fillMaxWidth())
                    TextField(value = memo, onValueChange = { memo = it }, label = { Text("Memo") }, modifier = Modifier.fillMaxWidth())
                    TextField(value = tagsInput, onValueChange = { tagsInput = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (word.isNotBlank() && answer.isNotBlank()) {
                                val tagList = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                onCardsUpdated(cards + Flashcard(word, answer, tagList, memo = memo))
                                word = ""; answer = ""; tagsInput = ""; memo = ""
                            }
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) { Text("Add to List") }
                }
            }
        }

        item {
            Column(
                modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(12.dp)
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter keywords...") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onImportClick, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, null)
                        Text("Import", fontSize = 12.sp)
                    }
                    Button(onClick = onExportClick, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null)
                        Text("Export", fontSize = 12.sp)
                    }
                }
            }
        }

        items(filteredCards) { card ->
            var isEditing by remember { mutableStateOf(false) }
            var editedWord by remember { mutableStateOf(card.word) }
            var editedAnswer by remember { mutableStateOf(card.answer) }
            var editedMemo by remember { mutableStateOf(card.memo) }
            var editedTags by remember { mutableStateOf(card.tags.joinToString(", ")) }

            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(
                    width = if (card.isWrong) 2.dp else 1.dp,
                    color = if (card.isWrong) Color.Red else Color.LightGray
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    if (isEditing) {
                        TextField(value = editedWord, onValueChange = { editedWord = it }, label = { Text("Word") }, modifier = Modifier.fillMaxWidth())
                        TextField(value = editedAnswer, onValueChange = { editedAnswer = it }, label = { Text("Answer") }, modifier = Modifier.fillMaxWidth())
                        TextField(value = editedMemo, onValueChange = { editedMemo = it }, label = { Text("Memo") }, modifier = Modifier.fillMaxWidth())
                        TextField(value = editedTags, onValueChange = { editedTags = it }, label = { Text("Tags") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                            Button(onClick = {
                                val newTagList = editedTags.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                val newList = cards.map {
                                    if (it == card) it.copy(word = editedWord, answer = editedAnswer, memo = editedMemo, tags = newTagList) else it
                                }
                                onCardsUpdated(newList)
                                isEditing = false
                            }) { Text("Save") }
                            Spacer(modifier = Modifier.width(8.dp))
                            TextButton(onClick = { isEditing = false }) { Text("Cancel") }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (card.isWrong) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.padding(end = 8.dp))
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(card.word, fontWeight = FontWeight.Bold)
                                Text(card.answer, color = Color.Gray)
                                if (card.memo.isNotBlank()) {
                                    Text("Memo: ${card.memo}", color = Color.DarkGray, fontSize = 14.sp)
                                }
                            }
                            IconButton(onClick = { isEditing = true }) {
                                Icon(Icons.Default.Edit, "Edit")
                            }
                            IconButton(onClick = { onCardsUpdated(cards.filter { it != card }) }) {
                                Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StudyScreen(deckName: String, cards: List<Flashcard>, isDarkMode: Boolean, onCardUpdated: (Int, Boolean) -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
    val indexKey = "current_index_$deckName"
    val filterKey = "show_only_wrong_$deckName"

    var showOnlyWrong by remember(deckName) {
        mutableStateOf(prefs.getBoolean(filterKey, false))
    }

    val displayCards = if (showOnlyWrong) cards.filter { it.isWrong } else cards

    if (displayCards.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (showOnlyWrong) "Check済みカードはありません" else "カードがありません")
                if (showOnlyWrong) {
                    Button(onClick = {
                        showOnlyWrong = false
                        prefs.edit().putBoolean(filterKey, false).apply()
                    }) { Text("すべてのカードに戻る") }
                }
            }
        }
        return
    }

    val savedIndex = prefs.getInt(indexKey, 0)
    var currentIndex by remember(deckName, showOnlyWrong) {
        mutableStateOf(savedIndex.coerceIn(0, displayCards.size - 1))
    }

    var showAnswer by remember { mutableStateOf(false) }
    val safeIndex = currentIndex.coerceIn(0, displayCards.size - 1)
    val currentCard = displayCards[safeIndex]

    val studyScrollState = rememberScrollState()
    LaunchedEffect(safeIndex, showAnswer) {
        studyScrollState.scrollTo(0)
    }

    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Card ${safeIndex + 1} / ${displayCards.size}")
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = showOnlyWrong,
                onClick = {
                    showOnlyWrong = !showOnlyWrong
                    currentIndex = 0
                    showAnswer = false
                    prefs.edit().putBoolean(filterKey, showOnlyWrong).putInt(indexKey, 0).apply()
                },
                label = { Text("Check Only", fontSize = 10.sp) }
            )
        }

        Card(
            onClick = { showAnswer = !showAnswer },
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp)
                .padding(16.dp),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (currentCard.isWrong) Color.Red else Color.LightGray
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (currentCard.isWrong) {
                    if (isDarkMode) Color(0xFF3A1E1E) else Color(0xFFFFEBEE)
                } else MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(studyScrollState),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textToShow = if (showAnswer) currentCard.answer else currentCard.word
                val fontSize = if (textToShow.length > 30) 18.sp else 22.sp

                Text(
                    text = textToShow,
                    fontSize = fontSize,
                    color = if (showAnswer) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                if (showAnswer && currentCard.memo.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = currentCard.memo,
                        fontSize = 14.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        IconButton(
            onClick = {
                val originalIndex = cards.indexOf(currentCard)
                onCardUpdated(originalIndex, !currentCard.isWrong)
            }
        ) {
            Icon(
                imageVector = if (currentCard.isWrong) Icons.Default.CheckCircle else Icons.Default.AddCircle,
                contentDescription = "Mark as wrong",
                tint = if (currentCard.isWrong) Color.Red else Color.Gray,
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(Modifier.height(16.dp))

        // ==========================================
        // ▼ 追加：カード位置（枚数）のジャンプバー
        // ==========================================
        if (displayCards.size > 1) { // カードが2枚以上ある時だけスライダーを表示
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "カード位置: ${safeIndex + 1} / ${displayCards.size}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )

                Slider(
                    value = safeIndex.toFloat(),
                    onValueChange = { newValue ->
                        currentIndex = newValue.toInt()
                        // スライダーで移動中は、誤って答えが見えないように必ず表面に戻す
                        showAnswer = false
                    },
                    onValueChangeFinished = {
                        // 指を離した時に、その位置を進捗として保存する
                        prefs.edit().putInt(indexKey, currentIndex).apply()
                    },
                    valueRange = 0f..(displayCards.size - 1).toFloat(),
                    modifier = Modifier.fillMaxWidth(0.85f)
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        // ==========================================

        Row {
            Button(onClick = {
                currentIndex = (safeIndex - 1 + displayCards.size) % displayCards.size
                showAnswer = false
                prefs.edit().putInt(indexKey, currentIndex).apply()
            }) { Text("Back") }

            Spacer(Modifier.width(16.dp))

            Button(onClick = {
                currentIndex = (safeIndex + 1) % displayCards.size
                showAnswer = false
                prefs.edit().putInt(indexKey, currentIndex).apply()
            }) { Text("Next") }
        }
    }
}

@Composable
fun GameScreen() {
    AndroidView(factory = { context ->
        WebView(context).apply {
            settings.javaScriptEnabled = true
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/index.html")
        }
    }, modifier = Modifier.fillMaxSize())
}

//@Composable
//fun AudioPlayerScreen(deckName: String, cards: List<Flashcard>, isDarkMode: Boolean) {
//    val context = LocalContext.current
//    val coroutineScope = rememberCoroutineScope()
//
//    // ① 音声モード進捗保持用の SharedPreferences 設定
//    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }
//    val audioIndexKey = "audio_index_$deckName"
//
//    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
//    var isPlaying by remember { mutableStateOf(false) }
//
//    // ① 保存されていた再生位置の読み込み
//    val savedIndex = prefs.getInt(audioIndexKey, 0)
//    var currentIndex by remember(deckName) {
//        mutableStateOf(savedIndex.coerceIn(0, cards.size - 1))
//    }
//
//    var currentDisplay by remember { mutableStateOf("word") }
//    var pauseSeconds by remember { mutableStateOf(2f) }
//    var speechRate by remember { mutableStateOf(1.0f) } // ③ 読み上げ速度用の状態を追加
//
//    // ② 見切れ対策用のスクロール状態
//    val audioScrollState = rememberScrollState()
//
//    // カードが切り替わった時、または表示（Word/Ans）が変わった時に最上部へスクロールリセット
//    LaunchedEffect(currentIndex, currentDisplay) {
//        audioScrollState.scrollTo(0)
//    }
//
//    fun playCurrentCard() {
//        if (currentIndex >= cards.size || !isPlaying) {
//            isPlaying = false
//            return
//        }
//        val card = cards[currentIndex]
//
//        // ③ 読み上げ実行の直前に速度を設定する
//        tts?.setSpeechRate(speechRate)
//
//        // 1. 問題の読み上げ
//        tts?.language = Locale.US
//        tts?.speak(card.word, TextToSpeech.QUEUE_FLUSH, null, "word_$currentIndex")
//
//        // 2. ポーズ
//        tts?.playSilentUtterance((pauseSeconds * 1000).toLong(), TextToSpeech.QUEUE_ADD, "pause_$currentIndex")
//
//        // 3. 答えの読み上げ
//        tts?.language = Locale.JAPAN
//        tts?.speak(card.answer, TextToSpeech.QUEUE_ADD, null, "ans_$currentIndex")
//
//        // 4. 次へ進む待機
//        tts?.playSilentUtterance(1500, TextToSpeech.QUEUE_ADD, "next_$currentIndex")
//    }
//
//    DisposableEffect(context) {
//        val textToSpeech = TextToSpeech(context) { status -> }
//
//        textToSpeech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
//            override fun onStart(utteranceId: String?) {
//                coroutineScope.launch {
//                    utteranceId?.let {
//                        if (it.startsWith("word_")) currentDisplay = "word"
//                        if (it.startsWith("ans_")) currentDisplay = "ans"
//                    }
//                }
//            }
//
//            override fun onDone(utteranceId: String?) {
//                coroutineScope.launch {
//                    if (utteranceId?.startsWith("next_") == true && isPlaying) {
//                        if (currentIndex < cards.size - 1) {
//                            currentIndex++
//                            // ① 自動ループ時にも進捗インデックスを永続化
//                            prefs.edit().putInt(audioIndexKey, currentIndex).apply()
//                            playCurrentCard()
//                        } else {
//                            isPlaying = false
//                        }
//                    }
//                }
//            }
//
//            @Deprecated("Deprecated in Java")
//            override fun onError(utteranceId: String?) {
//                coroutineScope.launch { isPlaying = false }
//            }
//        })
//
//        tts = textToSpeech
//
//        onDispose {
//            textToSpeech.stop()
//            textToSpeech.shutdown()
//        }
//    }
//
//    if (cards.isEmpty()) {
//        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("カードがありません") }
//        return
//    }
//
//    val safeIndex = currentIndex.coerceIn(0, cards.size - 1)
//    val currentCard = cards[safeIndex]
//
//    Column(
//        modifier = Modifier.fillMaxSize().padding(16.dp),
//        horizontalAlignment = Alignment.CenterHorizontally,
//        verticalArrangement = Arrangement.Center
//    ) {
//        Text("Listening: $deckName (${safeIndex + 1}/${cards.size})", fontWeight = FontWeight.Bold)
//        Spacer(Modifier.height(16.dp))
//
//        // カード表示領域
//        Card(
//            modifier = Modifier.fillMaxWidth().height(250.dp),
//            elevation = CardDefaults.cardElevation(8.dp),
//            colors = CardDefaults.cardColors(
//                containerColor = if (isDarkMode) MaterialTheme.colorScheme.surfaceVariant else Color.White
//            )
//        ) {
//            // ② 縦スクロール（verticalScroll）を付与して見切れを完全に防ぐ
//            Column(
//                modifier = Modifier
//                    .fillMaxSize()
//                    .padding(16.dp)
//                    .verticalScroll(audioScrollState),
//                verticalArrangement = Arrangement.Center,
//                horizontalAlignment = Alignment.CenterHorizontally
//            ) {
//                // 長文に対応した文字サイズ調整
//                val wordFontSize = if (currentCard.word.length > 30) 20.sp else 28.sp
//                val answerFontSize = if (currentCard.answer.length > 30) 18.sp else 24.sp
//
//                Text(
//                    text = currentCard.word,
//                    fontSize = wordFontSize,
//                    fontWeight = FontWeight.ExtraBold,
//                    color = if (currentDisplay == "word") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
//                )
//
//                Spacer(modifier = Modifier.height(24.dp))
//
//                if (currentDisplay == "ans") {
//                    Text(
//                        text = currentCard.answer,
//                        fontSize = answerFontSize,
//                        color = MaterialTheme.colorScheme.secondary
//                    )
//                }
//            }
//        }
//
//        Spacer(Modifier.height(24.dp))
//
//        // ==========================================
//        // ▼ 追加：カード再生位置（枚数）のジャンプバー
//        // ==========================================
//        Column(
//            modifier = Modifier.fillMaxWidth(),
//            horizontalAlignment = Alignment.CenterHorizontally
//        ) {
//            Text(
//                text = "再生位置: ${currentIndex + 1} / ${cards.size}",
//                fontSize = 14.sp,
//                fontWeight = FontWeight.Bold
//            )
//
//            Slider(
//                value = currentIndex.toFloat(),
//                onValueChange = { newValue ->
//                    // スライダーを動かしている最中はインデックスだけを更新し、表示を単語(word)に戻す
//                    val newIndex = newValue.toInt()
//                    AudioPlayerManager.currentIndex.value = newIndex
//                    AudioPlayerManager.currentDisplay.value = "word"
//                },
//                onValueChangeFinished = {
//                    // スライダーから指を離した瞬間に、その位置を保存する
//                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
//                        .edit().putInt("audio_index_$deckName", AudioPlayerManager.currentIndex.value).apply()
//
//                    // もし再生中(Play状態)にバーをいじったなら、指を離した位置からすぐ読み上げを再開させる
//                    if (isPlaying) {
//                        AudioPlayerManager.playCurrentCard()
//                    }
//                },
//                valueRange = 0f..maxOf(0f, (cards.size - 1).toFloat()),
//                modifier = Modifier.fillMaxWidth(0.85f)
//            )
//        }
//        // ==========================================
//
//        // コントロールパネル
//        Row(
//            verticalAlignment = Alignment.CenterVertically,
//            horizontalArrangement = Arrangement.spacedBy(16.dp)
//        ) {
//            Button(onClick = {
//                currentIndex = maxOf(0, currentIndex - 1)
//                // ① ボタン押下時に再生位置を保存
//                prefs.edit().putInt(audioIndexKey, currentIndex).apply()
//                if (isPlaying) { playCurrentCard() } else { currentDisplay = "word" }
//            }) { Text("Prev") }
//
//            if (isPlaying) {
//                Button(
//                    onClick = {
//                        isPlaying = false
//                        tts?.stop()
//                    },
//                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
//                ) { Text("Stop") }
//            } else {
//                Button(
//                    onClick = {
//                        isPlaying = true
//                        playCurrentCard()
//                    },
//                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
//                ) { Text("Play") }
//            }
//
//            Button(onClick = {
//                currentIndex = minOf(cards.size - 1, currentIndex + 1)
//                // ① ボタン押下時に再生位置を保存
//                prefs.edit().putInt(audioIndexKey, currentIndex).apply()
//                if (isPlaying) { playCurrentCard() } else { currentDisplay = "word" }
//            }) { Text("Next") }
//        }
//
//        Spacer(Modifier.height(20.dp))
//
//        // ポーズ時間調整スライダー
//        Text("Pause Duration: ${String.format("%.1f", pauseSeconds)} sec", fontSize = 14.sp)
//        Slider(
//            value = pauseSeconds,
//            onValueChange = {
//                pauseSeconds = it
//                if (isPlaying) playCurrentCard()
//            },
//            valueRange = 1f..5f,
//            steps = 8,
//            modifier = Modifier.fillMaxWidth(0.8f)
//        )
//
//        Spacer(Modifier.height(10.dp))
//
//        // ③ 速さ調整スライダーを追加
//        Text("Speech Rate: ${String.format("%.1f", speechRate)}x", fontSize = 14.sp)
//        Slider(
//            value = speechRate,
//            onValueChange = {
//                speechRate = it
//                tts?.setSpeechRate(speechRate) // 変更時にスライダーの速度設定を反映
//            },
//            valueRange = 0.5f..2.0f,
//            steps = 6, // 0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0 の刻み
//            modifier = Modifier.fillMaxWidth(0.8f)
//        )
//    }
//}

// ==========================================
// 1. 音声処理を画面から独立させた管理者（お引越し先）
// ==========================================
object AudioPlayerManager {
    val isPlaying = mutableStateOf(false)
    val currentIndex = mutableStateOf(0)
    val currentDisplay = mutableStateOf("word")
    val pauseSeconds = mutableStateOf(2f)
    val speechRate = mutableStateOf(1.0f)

    private var tts: TextToSpeech? = null
    private var cards: List<Flashcard> = emptyList()
    @SuppressLint("StaticFieldLeak")
    private var deckName: String = ""
    private var prefs: android.content.SharedPreferences? = null
    private var context: Context? = null
    private val scope = CoroutineScope(Dispatchers.Main)

    fun initialize(ctx: Context, newCards: List<Flashcard>, newDeckName: String) {
        if (deckName == newDeckName && cards == newCards && tts != null) return

        context = ctx.applicationContext
        cards = newCards
        deckName = newDeckName
        prefs = context?.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)

        val savedIndex = prefs?.getInt("audio_index_$deckName", 0) ?: 0
        currentIndex.value = savedIndex.coerceIn(0, maxOf(0, cards.size - 1))

        if (tts == null) {
            tts = TextToSpeech(context) { _ -> }
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    scope.launch {
                        if (utteranceId?.startsWith("word_") == true) currentDisplay.value = "word"
                        if (utteranceId?.startsWith("ans_") == true) currentDisplay.value = "ans"
                    }
                }
                override fun onDone(utteranceId: String?) {
                    scope.launch {
                        if (utteranceId?.startsWith("next_") == true && isPlaying.value) {
                            if (currentIndex.value < cards.size - 1) {
                                currentIndex.value++
                                prefs?.edit()?.putInt("audio_index_$deckName", currentIndex.value)?.apply()
                                playCurrentCard()
                            } else {
                                stop()
                            }
                        }
                    }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    scope.launch { stop() }
                }
            })
        }
    }

    fun play() {
        if (cards.isEmpty()) return
        isPlaying.value = true

        // バックグラウンド用のサービスを起動
        val intent = Intent(context, AudioService::class.java)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context?.startForegroundService(intent)
        } else {
            context?.startService(intent)
        }
        playCurrentCard()
    }

    fun stop() {
        isPlaying.value = false
        tts?.stop()
        currentDisplay.value = "word"

        // バックグラウンド用のサービスを停止
        val intent = Intent(context, AudioService::class.java)
        context?.stopService(intent)
    }

    fun playCurrentCard() {
        if (currentIndex.value >= cards.size || !isPlaying.value) {
            stop()
            return
        }
        val card = cards[currentIndex.value]
        tts?.setSpeechRate(speechRate.value)

        tts?.language = Locale.US
        tts?.speak(card.word, TextToSpeech.QUEUE_FLUSH, null, "word_${currentIndex.value}")
        tts?.playSilentUtterance((pauseSeconds.value * 1000).toLong(), TextToSpeech.QUEUE_ADD, "pause_${currentIndex.value}")

        tts?.language = Locale.JAPAN
        tts?.speak(card.answer, TextToSpeech.QUEUE_ADD, null, "ans_${currentIndex.value}")
        tts?.playSilentUtterance(1500, TextToSpeech.QUEUE_ADD, "next_${currentIndex.value}")
    }
}

// ==========================================
// 2. 新しいUI画面（音声処理を持たず、管理者を見るだけ）
// ==========================================
@Composable
fun AudioPlayerScreen(deckName: String, cards: List<Flashcard>, isDarkMode: Boolean) {
    val context = LocalContext.current

    // 画面を開いた時にデータを管理者に渡す
    LaunchedEffect(deckName, cards) {
        AudioPlayerManager.initialize(context, cards, deckName)
    }

    // 管理者の状態を監視（Observe）する
    val isPlaying by AudioPlayerManager.isPlaying
    val currentIndex by AudioPlayerManager.currentIndex
    val currentDisplay by AudioPlayerManager.currentDisplay
    val pauseSeconds by AudioPlayerManager.pauseSeconds
    val speechRate by AudioPlayerManager.speechRate

    val audioScrollState = rememberScrollState()

    LaunchedEffect(currentIndex, currentDisplay) {
        audioScrollState.scrollTo(0)
    }

    if (cards.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { Text("カードがありません") }
        return
    }

    val safeIndex = currentIndex.coerceIn(0, cards.size - 1)
    val currentCard = cards[safeIndex]

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Listening: $deckName (${safeIndex + 1}/${cards.size})", fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth().height(250.dp),
            elevation = CardDefaults.cardElevation(8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (isDarkMode) MaterialTheme.colorScheme.surfaceVariant else Color.White
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(audioScrollState),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val wordFontSize = if (currentCard.word.length > 30) 20.sp else 28.sp
                val answerFontSize = if (currentCard.answer.length > 30) 18.sp else 24.sp

                Text(
                    text = currentCard.word,
                    fontSize = wordFontSize,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (currentDisplay == "word") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(24.dp))

                if (currentDisplay == "ans") {
                    Text(
                        text = currentCard.answer,
                        fontSize = answerFontSize,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // ジャンプバー
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "再生位置: ${currentIndex + 1} / ${cards.size}",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )

            Slider(
                value = currentIndex.toFloat(),
                onValueChange = { newValue ->
                    AudioPlayerManager.currentIndex.value = newValue.toInt()
                    AudioPlayerManager.currentDisplay.value = "word"
                },
                onValueChangeFinished = {
                    context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                        .edit().putInt("audio_index_$deckName", AudioPlayerManager.currentIndex.value).apply()

                    if (isPlaying) {
                        AudioPlayerManager.playCurrentCard()
                    }
                },
                valueRange = 0f..maxOf(0f, (cards.size - 1).toFloat()),
                modifier = Modifier.fillMaxWidth(0.85f)
            )
        }

        // コントロールボタン
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(onClick = {
                AudioPlayerManager.currentIndex.value = maxOf(0, AudioPlayerManager.currentIndex.value - 1)
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("audio_index_$deckName", AudioPlayerManager.currentIndex.value).apply()
                if (isPlaying) { AudioPlayerManager.playCurrentCard() } else { AudioPlayerManager.currentDisplay.value = "word" }
            }) { Text("Prev") }

            if (isPlaying) {
                Button(
                    onClick = { AudioPlayerManager.stop() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
                ) { Text("Stop") }
            } else {
                Button(
                    onClick = { AudioPlayerManager.play() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Play") }
            }

            Button(onClick = {
                AudioPlayerManager.currentIndex.value = minOf(cards.size - 1, AudioPlayerManager.currentIndex.value + 1)
                context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
                    .edit().putInt("audio_index_$deckName", AudioPlayerManager.currentIndex.value).apply()
                if (isPlaying) { AudioPlayerManager.playCurrentCard() } else { AudioPlayerManager.currentDisplay.value = "word" }
            }) { Text("Next") }
        }

        Spacer(Modifier.height(20.dp))

//        Text("Pause Duration: ${String.format("%.1f", pauseSeconds)} sec", fontSize = 14.sp)
//        Slider(
//            value = pauseSeconds,
//            onValueChange = {
//                AudioPlayerManager.pauseSeconds.value = it
//                if (isPlaying) AudioPlayerManager.playCurrentCard()
//            },
//            valueRange = 1f..5f,
//            steps = 8,
//            modifier = Modifier.fillMaxWidth(0.8f)
//        )

        Spacer(Modifier.height(10.dp))

        Text("Speech Rate: ${String.format("%.1f", speechRate)}x", fontSize = 14.sp)
        Slider(
            value = speechRate,
            onValueChange = {
                AudioPlayerManager.speechRate.value = it
                if (isPlaying) AudioPlayerManager.playCurrentCard()
            },
            valueRange = 0.5f..2.0f,
            steps = 6,
            modifier = Modifier.fillMaxWidth(0.8f)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JsonEditorScreen(
    deckName: String,
    storageManager: AppStorageManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    var jsonText by remember { mutableStateOf(storageManager.getJsonText(deckName)) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Editing: $deckName.json",
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            // ボタンエリア（押し出されないようになります）
            Row(
                modifier = Modifier.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onBack) {
                    Text("Cancel")
                }
                Button(onClick = {
                    try {
                        val format = Json { ignoreUnknownKeys = true }
                        val parsedCards = format.decodeFromString<List<Flashcard>>(jsonText)
                        storageManager.saveCards(deckName, parsedCards)
                        Toast.makeText(context, "Saved Successfully!", Toast.LENGTH_SHORT).show()
                        onBack()
                    } catch (e: Exception) {
                        errorMessage = "JSON Error: ${e.localizedMessage}"
                    }
                }) {
                    Text("Save")
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (errorMessage != null) {
            Text(
                text = errorMessage!!,
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }

        OutlinedTextField(
            value = jsonText,
            onValueChange = {
                jsonText = it
                errorMessage = null
            },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        )
    }
}

fun getDeckNameFromUri(context: Context, uri: Uri): String {
    var fileName = "imported_deck"
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) {
                fileName = cursor.getString(index)
            }
        }
    }
    return fileName.substringBeforeLast(".")
}

// 1. バックグラウンドでプロセスを維持するための「サービス」
class AudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val channelId = "flashcard_audio_channel"
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "音声再生", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        // バックグラウンド実行をOSに認めてもらうための通知
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Flash Card App")
            .setContentText("バックグラウンドで音声を再生中...")
            .setSmallIcon(android.R.drawable.ic_media_play) // 既存のアイコンで代用
            .build()

        startForeground(1, notification)
        return START_STICKY
    }
}