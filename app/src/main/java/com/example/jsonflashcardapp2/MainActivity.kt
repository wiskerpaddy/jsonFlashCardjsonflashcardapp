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

// 1. データモデル
@Serializable
data class Flashcard(
    val word: String,
    val answer: String,
    val tags: List<String> = emptyList(),
    val isWrong: Boolean = false // ← 追加
)

// 2. 保存管理クラス
class AppStorageManager(context: Context) {
    private val file = File(context.filesDir, "cards.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun saveCards(cards: List<Flashcard>) {
        val jsonString = json.encodeToString(cards)
        file.writeText(jsonString)
    }

    fun loadCards(): List<Flashcard> {
        return try {
            if (file.exists()) {
                val jsonString = file.readText()
                json.decodeFromString(jsonString)
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getJsonText(): String = if (file.exists()) file.readText() else "[]"

    fun importJson(jsonString: String): Boolean {
        return try {
            val importedCards = json.decodeFromString<List<Flashcard>>(jsonString)
            saveCards(importedCards)
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
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                FlashCardApp(storageManager)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlashCardApp(storageManager: AppStorageManager) {
    val context = LocalContext.current
    var currentMode by remember { mutableStateOf<String?>(null) }
    var cards by remember { mutableStateOf(storageManager.loadCards()) }

    // インポート用のランチャー
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let {
            context.contentResolver.openInputStream(it)?.use { stream ->
                val jsonText = stream.bufferedReader().use { it.readText() }
                if (storageManager.importJson(jsonText)) {
                    cards = storageManager.loadCards()
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
                stream.write(storageManager.getJsonText().toByteArray())
                Toast.makeText(context, "Export Success!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Flash Card App") }) }
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
                    Button(onClick = { currentMode = "register" }) { Text("Reg/Edit Mode") }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(onClick = { currentMode = "study" }) { Text("Flash Mode") }
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { currentMode = "game" },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) { Text("Mini Game Activate") }
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
                                storageManager.saveCards(updatedList)
                            },
                            onImportClick = { importLauncher.launch("application/json") },
                            onExportClick = { exportLauncher.launch("cards.json") }
                        )
                    }
                    "study" -> StudyScreen(
                        cards = cards,
                        onCardUpdated = { index, isWrong ->
                            val newList = cards.toMutableList()
                            newList[index] = newList[index].copy(isWrong = isWrong)
                            cards = newList
                            storageManager.saveCards(newList)
                        }
                    )
                    "game" -> Box(modifier = Modifier.fillMaxSize().weight(1f)) { GameScreen() }
                }
            }
        }
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
    var tagsInput by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }

    val filteredCards = cards.filter {
        it.word.contains(searchQuery, ignoreCase = true) ||
                it.answer.contains(searchQuery, ignoreCase = true) ||
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
                    TextField(value = tagsInput, onValueChange = { tagsInput = it }, label = { Text("Tags (comma separated)") }, modifier = Modifier.fillMaxWidth())
                    Button(
                        onClick = {
                            if (word.isNotBlank() && answer.isNotBlank()) {
                                val tagList = tagsInput.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                onCardsUpdated(cards + Flashcard(word, answer, tagList))
                                word = ""; answer = ""; tagsInput = ""
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp), // 軽い影を追加
                // ここを修正：!card.isWrong の時も LightGray の枠線を引く
                border = BorderStroke(
                    width = if (card.isWrong) 2.dp else 1.dp,
                    color = if (card.isWrong) Color.Red else Color.LightGray
                )
            ) {
                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (card.isWrong) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color.Red, modifier = Modifier.padding(end = 8.dp))
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(card.word, fontWeight = FontWeight.Bold)
                        Text(card.answer, color = Color.Gray)
                    }
                    IconButton(onClick = { onCardsUpdated(cards.filter { it != card }) }) {
                        Icon(Icons.Default.Delete, "Delete", tint = Color.Red)
                    }
                }
            }
        }
    }
}

@Composable
fun StudyScreen(cards: List<Flashcard>, onCardUpdated: (Int, Boolean) -> Unit) {
    var currentIndex by remember { mutableStateOf(0) }
    var showAnswer by remember { mutableStateOf(false) }
    var showOnlyWrong by remember { mutableStateOf(false) }

    // チェックした問題のみに絞り込む
    val displayCards = if (showOnlyWrong) cards.filter { it.isWrong } else cards

    if (displayCards.isEmpty()) {
        Box(Modifier.fillMaxSize(), Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (showOnlyWrong) "Check済みカードはありません" else "カードがありません")
                if (showOnlyWrong) {
                    Button(onClick = { showOnlyWrong = false }) { Text("すべてのカードに戻る") }
                }
            }
        }
        return
    }

    // インデックスが範囲外にならないよう調整
    val safeIndex = currentIndex.coerceIn(0, displayCards.size - 1)
    val currentCard = displayCards[safeIndex]

    Column(Modifier.fillMaxSize(), Arrangement.Center, Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Card ${safeIndex + 1} / ${displayCards.size}")
            Spacer(Modifier.width(8.dp))
            // フィルタ切り替えスイッチ
            FilterChip(
                selected = showOnlyWrong,
                onClick = { showOnlyWrong = !showOnlyWrong; currentIndex = 0 },
                label = { Text("Check Only", fontSize = 10.sp) }
            )
        }

        Card(
            onClick = { showAnswer = !showAnswer },
            modifier = Modifier
                .fillMaxWidth()
                .height(200.dp)
                .padding(16.dp),
            // 影を追加して浮き上がらせる
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
            // 枠線を追加（通常時はライトグレー、チェック時は薄い赤など）
            border = BorderStroke(
                width = 1.dp,
                color = if (currentCard.isWrong) Color.Red else Color.LightGray
            ),
            colors = CardDefaults.cardColors(
                containerColor = if (currentCard.isWrong) Color(0xFFFFEBEE) else MaterialTheme.colorScheme.surface
            )
        ) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text(if (showAnswer) currentCard.answer else currentCard.word, fontSize = 24.sp)
            }
        }

        // 間違えた！ボタン（チェックのトグル）
        IconButton(
            onClick = {
                // 元のリストにおけるこのカードのインデックスを探して更新
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
            Button(onClick = { currentIndex = (safeIndex - 1 + displayCards.size) % displayCards.size; showAnswer = false }) { Text("Back") }
            Spacer(Modifier.width(16.dp))
            Button(onClick = { currentIndex = (safeIndex + 1) % displayCards.size; showAnswer = false }) { Text("Next") }
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