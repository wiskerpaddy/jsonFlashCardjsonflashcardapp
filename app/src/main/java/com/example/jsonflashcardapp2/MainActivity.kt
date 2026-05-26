package com.example.jsonflashcardapp2

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

// 1. データモデル
@Serializable
data class Flashcard(
    val word: String,
    val answer: String,
    val tags: List<String> = emptyList(),
    val isWrong: Boolean = false, // ← 追加
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

    LaunchedEffect(selectedDeck) {
        cards = storageManager.loadCards(selectedDeck)
        prefs.edit().putString("selected_deck", selectedDeck).apply()
    }

    // インポート用のランチャー
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val jsonText = stream.bufferedReader().use { it.readText() }
                if (storageManager.importJson(selectedDeck, jsonText)) {
                    cards = storageManager.loadCards(selectedDeck)
                    deckList = storageManager.getDeckList()
                    Toast.makeText(context, "Import Success!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Invalid JSON Format", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // エクスポート用のランチャー
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
                        IconButton(onClick = { onDarkModeChanged(!isDarkMode) }) {
                            Text(if (isDarkMode) "☀️" else "🌙", fontSize = 20.sp)
                        }
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
//                    Text(
//                        text = "使いかた：既存のデッキを選択するか、新しいデッキ名を入力して作成してください。その後、各モードを選択して学習を開始します。",
//                        fontSize = 14.sp,
//                        color = Color.Gray,
//                        modifier = Modifier.padding(bottom = 24.dp)
//                    )

                    // デッキ管理エリア
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

                            // 横幅を統一したデッキ選択
                            Box(modifier = Modifier.fillMaxWidth(0.85f), contentAlignment = Alignment.Center) {
                                Button(
                                    onClick = { showMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = CircleShape
                                ) {
                                    Text("Deck: $selectedDeck")
                                    Icon(Icons.Default.ArrowDropDown, null)
                                }
                                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                    deckList.forEach { deck ->
                                        DropdownMenuItem(text = { Text(deck) }, onClick = {
                                            selectedDeck = deck
                                            showMenu = false
                                        })
                                    }
                                }
                            }

                            // 高さとカプセル形状を完全に同期させ、文字視認性を確保したエリア
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

                            // エラーテキストをRowの外側に配置してテキスト潰れと位置ズレを防止
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

                        OutlinedButton(
                            onClick = { currentMode = "game" },
                            modifier = Modifier.fillMaxWidth(0.8f).height(50.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.secondary)
                        ) { Text("Mini Game Activate") }
                    }
                }
            } else {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Button(onClick = { currentMode = null }) { Text("Return Menu") }
                }
                Spacer(modifier = Modifier.height(16.dp))

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
                    "game" -> Box(modifier = Modifier.fillMaxSize().weight(1f)) { GameScreen() }
                }
            }
        }
    }

    if (showHelpDialog) {
        AlertDialog(
            onDismissRequest = { showHelpDialog = false },
            title = { Text("使いかた") },
            text = { Text("既存的デッキを選択するか、新しいデッキ名を入力して作成してください。その後、各モードを選択して学習を開始します。") },
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
                        Icon(Icons.Default.Add, null) // FileDownload の代わり
                        Text("Import", fontSize = 12.sp)
                    }
                    Button(onClick = onExportClick, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, null) // FileUpload の代わり
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
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), // 軽い影を追加
                // ここを修正：!card.isWrong の時も LightGray の枠線を引く
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

    // SharedPreferencesの用意（進捗保存用）
    val prefs = remember { context.getSharedPreferences("app_prefs", Context.MODE_PRIVATE) }

    val indexKey = "current_index_$deckName"
    val filterKey = "show_only_wrong_$deckName"

    // 初期値をSharedPreferencesから読み込む（なければデフォルト値）
    var showOnlyWrong by remember(deckName) {
        mutableStateOf(prefs.getBoolean(filterKey, false))
    }

    // チェックした問題のみに絞り込む
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

    // 保存されていたインデックスを読み込み、現在のリスト範囲内に収める
    val savedIndex = prefs.getInt(indexKey, 0)
    var currentIndex by remember(deckName, showOnlyWrong) {
        mutableStateOf(savedIndex.coerceIn(0, displayCards.size - 1))
    }

    var showAnswer by remember { mutableStateOf(false) }

    // インデックスが範囲外にならないよう調整
    val safeIndex = currentIndex.coerceIn(0, displayCards.size - 1)
    val currentCard = displayCards[safeIndex]

    // スクリプトの見切れ対策：カードが切り替わるたびにスクロール位置を最上部にリセット
    val scrollState = rememberScrollState()
    LaunchedEffect(safeIndex, showAnswer) {
        scrollState.scrollTo(0)
    }

    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Card ${safeIndex + 1} / ${displayCards.size}")
            Spacer(Modifier.width(8.dp))
            // フィルタ切り替えスイッチ
            FilterChip(
                selected = showOnlyWrong,
                onClick = {
                    showOnlyWrong = !showOnlyWrong
                    currentIndex = 0
                    showAnswer = false
                    // フィルター状態を保存
                    prefs.edit().putBoolean(filterKey, showOnlyWrong).putInt(indexKey, 0).apply()
                },
                label = { Text("Check Only", fontSize = 10.sp) }
            )
        }

        Card(
            onClick = { showAnswer = !showAnswer },
            modifier = Modifier
                .fillMaxWidth()
                .height(250.dp) // 長文に対応するため少し高さを広げました
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
            // Boxから、スクロール可能なColumnに変更して見切れを完全に防ぐ
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(scrollState), // ← ここで縦スクロールを有効化
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val textToShow = if (showAnswer) currentCard.answer else currentCard.word
                // 長文の時は自動的に少し文字サイズを下げる
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

        // 間意がえた！ボタン（チェックのトグル）
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

        Row {
            Button(onClick = {
                currentIndex = (safeIndex - 1 + displayCards.size) % displayCards.size
                showAnswer = false
                // 進捗を保存
                prefs.edit().putInt(indexKey, currentIndex).apply()
            }) { Text("Back") }

            Spacer(Modifier.width(16.dp))

            Button(onClick = {
                currentIndex = (safeIndex + 1) % displayCards.size
                showAnswer = false
                // 進捗を保存
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