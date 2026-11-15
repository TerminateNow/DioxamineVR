package io.github.rhythmcache.dioxamine.adb.builtin.logcat

import io.github.rhythmcache.dioxamine.ui.tv.Button
import io.github.rhythmcache.dioxamine.ui.tv.FilterChip
import io.github.rhythmcache.dioxamine.ui.tv.IconButton
import io.github.rhythmcache.dioxamine.ui.tv.OutlinedButton
import io.github.rhythmcache.dioxamine.ui.tv.OutlinedTextField
import io.github.rhythmcache.dioxamine.ui.tv.TextButton
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.rhythmcache.dioxamine.R
import io.github.rhythmcache.dioxamine.adb.AdbViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogcatScreen(
    vm: AdbViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val clipboardManager = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    val coroutineScope = rememberCoroutineScope()
    val client = vm.activeClient()

    val entries = remember { mutableStateListOf<LogcatEntry>() }
    var isStreaming by remember { mutableStateOf(true) }
    var isAutoScroll by remember { mutableStateOf(true) }

    var searchQuery by remember { mutableStateOf("") }
    var debouncedSearchQuery by remember { mutableStateOf("") }
    var isRegex by remember { mutableStateOf(false) }
    var isCaseSensitive by remember { mutableStateOf(false) }
    var selectedLevels by remember { mutableStateOf(emptySet<LogLevel>()) }
    var tagFilter by remember { mutableStateOf("") }
    var pidFilter by remember { mutableStateOf("") }
    var showAdvancedFilters by remember { mutableStateOf(false) }
    var isSearchFilterVisible by remember { mutableStateOf(true) }

    var selectedEntry by remember { mutableStateOf<LogcatEntry?>(null) }
    var showClearDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Debounce search query to avoid heavy re-filtering and regex compilation on every keystroke
    LaunchedEffect(searchQuery) {
        if (searchQuery.isEmpty()) {
            debouncedSearchQuery = ""
        } else {
            delay(150)
            debouncedSearchQuery = searchQuery
        }
    }

    // Streaming Coroutine
    LaunchedEffect(client, isStreaming) {
        if (client == null || !isStreaming) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            runCatching {
                val stream = client.open("exec:logcat -v threadtime")
                val buffer = StringBuilder()
                val byteBuf = ByteArray(16384)
                val batch = ArrayList<LogcatEntry>(64)
                var lastFlushTime = System.currentTimeMillis()
                var idCounter = System.currentTimeMillis()
                var lastLevel = LogLevel.VERBOSE
                var lastTag = ""

                suspend fun flushBatch() {
                    if (batch.isNotEmpty()) {
                        val toAdd = ArrayList(batch)
                        batch.clear()
                        withContext(Dispatchers.Main) {
                            entries.addAll(toAdd)
                            if (entries.size > 3000) {
                                val excess = entries.size - 2500
                                entries.subList(0, excess).clear()
                            }
                        }
                    }
                    lastFlushTime = System.currentTimeMillis()
                }

                try {
                    while (isActive) {
                        val n = stream.read(byteBuf)
                        if (n <= 0) break
                        buffer.append(String(byteBuf, 0, n, Charsets.UTF_8))

                        var lineEnd = buffer.indexOf('\n')
                        while (lineEnd != -1) {
                            val line = buffer.substring(0, lineEnd).trimEnd('\r')
                            buffer.delete(0, lineEnd + 1)
                            if (line.isNotEmpty()) {
                                val entry = LogcatParser.parse(line, ++idCounter, lastLevel, lastTag)
                                lastLevel = entry.level
                                lastTag = entry.tag
                                batch.add(entry)
                            }
                            lineEnd = buffer.indexOf('\n')
                        }

                        val now = System.currentTimeMillis()
                        if (batch.size >= 40 || (now - lastFlushTime >= 80)) {
                            flushBatch()
                        }
                    }
                } finally {
                    flushBatch()
                    runCatching { stream.close() }
                }
            }
        }
    }

    // Filter computation
    val filteredEntries by remember(entries, debouncedSearchQuery, isRegex, isCaseSensitive, selectedLevels, tagFilter, pidFilter) {
        derivedStateOf {
            val query = debouncedSearchQuery
            val compiledRegex = if (isRegex && query.isNotBlank()) {
                val opts = if (isCaseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
                runCatching { query.toRegex(opts) }.getOrNull()
            } else null

            entries.filter { entry ->
                // Level filter: multi-select matching (empty set means ALL)
                if (selectedLevels.isNotEmpty() && entry.level !in selectedLevels) {
                    return@filter false
                }

                // Tag filter
                if (tagFilter.isNotBlank() && !entry.tag.contains(tagFilter.trim(), ignoreCase = true)) {
                    return@filter false
                }

                // PID filter
                if (pidFilter.isNotBlank() && !entry.pid.contains(pidFilter.trim())) {
                    return@filter false
                }

                // Text / Regex Search Query
                if (query.isNotBlank()) {
                    if (compiledRegex != null) {
                        if (!compiledRegex.containsMatchIn(entry.message) &&
                            !compiledRegex.containsMatchIn(entry.tag) &&
                            !compiledRegex.containsMatchIn(entry.raw)
                        ) return@filter false
                    } else {
                        val ignoreCase = !isCaseSensitive
                        if (!entry.message.contains(query, ignoreCase = ignoreCase) &&
                            !entry.tag.contains(query, ignoreCase = ignoreCase)
                        ) return@filter false
                    }
                }

                true
            }
        }
    }

    // Auto-scroll to bottom
    LaunchedEffect(filteredEntries.size, isAutoScroll) {
        if (isAutoScroll && filteredEntries.isNotEmpty()) {
            listState.scrollToItem(filteredEntries.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- Top Bar ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.btn_back)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.adb_logcat_tile_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = if (isStreaming) stringResource(R.string.logcat_status_streaming)
                           else stringResource(R.string.logcat_status_paused),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            // Stream / Pause Button
            IconButton(onClick = { isStreaming = !isStreaming }) {
                Icon(
                    imageVector = if (isStreaming) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (isStreaming) stringResource(R.string.logcat_btn_pause)
                                         else stringResource(R.string.logcat_btn_resume),
                    tint = if (isStreaming) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
            }

            // Copy Filtered Logs
            IconButton(
                onClick = {
                    val logsText = filteredEntries.joinToString("\n") { it.raw }
                    val clip = ClipData.newPlainText("logcat", logsText)
                    clipboardManager?.setPrimaryClip(clip)
                    Toast.makeText(
                        context,
                        context.getString(R.string.logcat_logs_copied, filteredEntries.size),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.ContentCopy,
                    contentDescription = stringResource(R.string.logcat_btn_copy)
                )
            }

            // Clear Menu Button
            IconButton(onClick = { showClearDialog = true }) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.logcat_btn_clear)
                )
            }

            // Toggle Search & Filters Visibility (Drop down arrow)
            val hasActiveFilters = searchQuery.isNotEmpty() || selectedLevels.isNotEmpty() || tagFilter.isNotEmpty() || pidFilter.isNotEmpty()
            IconButton(onClick = { isSearchFilterVisible = !isSearchFilterVisible }) {
                Icon(
                    imageVector = if (isSearchFilterVisible) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.logcat_btn_toggle_filters),
                    tint = if (hasActiveFilters && !isSearchFilterVisible) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // --- Search & Filter Bar (Collapsible) ---
        AnimatedVisibility(visible = isSearchFilterVisible) {
            Column {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    stringResource(R.string.logcat_search_hint),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            leadingIcon = {
                                Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (searchQuery.isNotEmpty()) {
                                        IconButton(onClick = { searchQuery = "" }) {
                                            Icon(Icons.Filled.Clear, contentDescription = null, modifier = Modifier.size(18.dp))
                                        }
                                    }
                                    IconButton(
                                        onClick = { showAdvancedFilters = !showAdvancedFilters }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Tune,
                                            contentDescription = null,
                                            tint = if (showAdvancedFilters || tagFilter.isNotEmpty() || pidFilter.isNotEmpty()) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            }
                                        )
                                    }
                                }
                            }
                        )

                        // Advanced filters (Tag & PID)
                        AnimatedVisibility(visible = showAdvancedFilters) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = tagFilter,
                                    onValueChange = { tagFilter = it },
                                    modifier = Modifier.weight(1f),
                                    label = { Text(stringResource(R.string.logcat_filter_tag_hint)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                OutlinedTextField(
                                    value = pidFilter,
                                    onValueChange = { pidFilter = it },
                                    modifier = Modifier.weight(0.7f),
                                    label = { Text(stringResource(R.string.logcat_filter_pid_hint)) },
                                    singleLine = true,
                                    shape = RoundedCornerShape(12.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // --- Chips Row (Search modifiers + Log Level chips) ---
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Regex Toggle Chip
                            FilterChip(
                                selected = isRegex,
                                onClick = { isRegex = !isRegex },
                                label = { Text(".*", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )

                            // Case-Sensitive Toggle Chip
                            FilterChip(
                                selected = isCaseSensitive,
                                onClick = { isCaseSensitive = !isCaseSensitive },
                                label = { Text("Aa", fontWeight = FontWeight.Bold, fontSize = 12.sp) },
                                shape = RoundedCornerShape(8.dp)
                            )

                            Box(
                                modifier = Modifier
                                    .height(20.dp)
                                    .width(1.dp)
                                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            )

                            // Log Level Chips (Multi-select)
                            FilterChip(
                                selected = selectedLevels.isEmpty(),
                                onClick = { selectedLevels = emptySet() },
                                label = { Text("ALL", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                                shape = RoundedCornerShape(8.dp)
                            )

                            LogLevel.values().forEach { level ->
                                val isSelected = level in selectedLevels
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        selectedLevels = if (isSelected) {
                                            selectedLevels - level
                                        } else {
                                            selectedLevels + level
                                        }
                                    },
                                    label = {
                                        Text(
                                            level.label,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else level.color
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = level.color
                                    ),
                                    shape = RoundedCornerShape(8.dp)
                                )
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }

        // --- Log Output LazyColumn ---
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
        ) {
            if (filteredEntries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (entries.isEmpty()) stringResource(R.string.logcat_empty_waiting)
                               else stringResource(R.string.logcat_empty_filtered),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(filteredEntries, key = { it.id }) { entry ->
                        LogcatRow(
                            entry = entry,
                            onClick = { selectedEntry = entry }
                        )
                    }
                }
            }

            // Auto-scroll toggle badge
            FloatingActionButton(
                onClick = {
                    isAutoScroll = !isAutoScroll
                    if (isAutoScroll && filteredEntries.isNotEmpty()) {
                        coroutineScope.launch {
                            listState.scrollToItem(filteredEntries.size - 1)
                        }
                    }
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(40.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = if (isAutoScroll) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.logcat_btn_autoscroll),
                    tint = if (isAutoScroll) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // --- Entry Detail Dialog ---
    selectedEntry?.let { entry ->
        AlertDialog(
            shape = RoundedCornerShape(16.dp),
            onDismissRequest = { selectedEntry = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = entry.level.color,
                        shape = RoundedCornerShape(4.dp),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Text(
                            text = " ${entry.level.name} ",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = entry.tag.ifEmpty { "Logcat" },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    if (entry.timestamp.isNotEmpty()) {
                        Text(
                            text = "Time: ${entry.timestamp}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (entry.pid.isNotEmpty()) {
                        Text(
                            text = "PID: ${entry.pid}  TID: ${entry.tid}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider()
                    Spacer(modifier = Modifier.height(8.dp))
                    SelectionContainerText(text = entry.message)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val clip = ClipData.newPlainText("logcat_entry", entry.raw)
                        clipboardManager?.setPrimaryClip(clip)
                        Toast.makeText(context, context.getString(R.string.logcat_logs_copied, 1), Toast.LENGTH_SHORT).show()
                        selectedEntry = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.logcat_btn_copy))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { selectedEntry = null },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.cd_close))
                }
            }
        )
    }

    // --- Clear Dialog ---
    if (showClearDialog) {
        AlertDialog(
            shape = RoundedCornerShape(16.dp),
            onDismissRequest = { showClearDialog = false },
            title = { Text(stringResource(R.string.logcat_btn_clear)) },
            text = {
                Text(stringResource(R.string.logcat_clear_dialog_msg))
            },
            confirmButton = {
                Button(
                    onClick = {
                        entries.clear()
                        showClearDialog = false
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.logcat_clear_screen))
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        entries.clear()
                        coroutineScope.launch(Dispatchers.IO) {
                            runCatching {
                                client?.open("exec:logcat -c")?.close()
                            }
                        }
                        showClearDialog = false
                        Toast.makeText(context, context.getString(R.string.logcat_device_buffer_cleared), Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(stringResource(R.string.logcat_clear_device_buffer))
                }
            }
        )
    }
}

@Composable
fun LogcatRow(
    entry: LogcatEntry,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        // Level Pill
        Surface(
            color = entry.level.color,
            shape = RoundedCornerShape(3.dp),
            modifier = Modifier
                .padding(top = 2.dp, end = 6.dp)
                .width(18.dp)
        ) {
            Text(
                text = entry.level.label,
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally)
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            if (entry.tag.isNotEmpty() || entry.timestamp.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (entry.tag.isNotEmpty()) {
                        Text(
                            text = entry.tag,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = entry.level.color,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    if (entry.pid.isNotEmpty()) {
                        Text(
                            text = "${entry.pid}/${entry.tid}",
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    }
                }
            }

            Text(
                text = entry.message,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                color = if (entry.level == LogLevel.ERROR || entry.level == LogLevel.FATAL) entry.level.color
                        else MaterialTheme.colorScheme.onSurface,
                lineHeight = 14.sp
            )
        }
    }
}

@Composable
fun SelectionContainerText(text: String) {
    androidx.compose.foundation.text.selection.SelectionContainer {
        Text(
            text = text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
