@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.clipforge.app.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.clipforge.app.*
import kotlinx.coroutines.launch

private val Red = Color(0xFFFF3B3B)
private val Scheme = darkColorScheme(
    primary = Red, onPrimary = Color.White, secondary = Color(0xFFFF6A3D),
    background = Color(0xFF0F0F10), surface = Color(0xFF1B1B1E), surfaceVariant = Color(0xFF26262B),
    onSurface = Color(0xFFF1F1F1), onSurfaceVariant = Color(0xFFA0A0A8), outline = Color(0xFF3A3A40),
)

@Composable
fun ClipForgeUi(vm: AppViewModel) {
    MaterialTheme(colorScheme = Scheme) {
        val snack = remember { SnackbarHostState() }
        LaunchedEffect(vm.error) {
            val e = vm.error
            if (e != null) {
                snack.showSnackbar(e, withDismissAction = true)
                vm.error = null
            }
        }
        BackHandler(enabled = vm.screen != Screen.HOME) {
            vm.screen = if (vm.screen == Screen.RESULTS) Screen.CLIPS else Screen.HOME
        }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snack) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(color = Red, shape = RoundedCornerShape(6.dp)) {
                                Icon(Icons.Default.PlayArrow, null, tint = Color.White,
                                    modifier = Modifier.padding(horizontal = 4.dp).size(18.dp))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("ClipForge", fontWeight = FontWeight.Bold)
                        }
                    },
                    navigationIcon = {
                        if (vm.screen != Screen.HOME) IconButton(onClick = {
                            vm.screen = if (vm.screen == Screen.RESULTS) Screen.CLIPS else Screen.HOME
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                )
            },
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (vm.screen) {
                    Screen.HOME -> Home(vm)
                    Screen.CLIPS -> Clips(vm)
                    Screen.RESULTS -> Results(vm)
                }
                vm.busy?.let { msg ->
                    Surface(Modifier.fillMaxSize(), color = Color(0xCC000000)) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(16.dp))
                            Text(msg)
                        }
                    }
                }
            }
        }
    }
}

// =================================================================== HOME

@Composable
private fun Home(vm: AppViewModel) {
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = vm.tab, containerColor = MaterialTheme.colorScheme.background) {
            Tab(vm.tab == 0, { vm.tab = 0 }, text = { Text("Paste link") })
            Tab(vm.tab == 1, { vm.tab = 1 }, text = { Text("Popular in my town") })
        }
        if (vm.tab == 0) PasteLink(vm) else TownSearch(vm)
    }
}

@Composable
private fun PasteLink(vm: AppViewModel) {
    Column(Modifier.padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Turn a YouTube video into captioned clips", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Paste a link, or tap Share → ClipForge in the YouTube app. We'll suggest the moments people replay most.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            vm.url, { vm.url = it }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("https://youtube.com/watch?v=…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { vm.loadVideo() }),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(vm.clipLen, { vm.clipLen = it.filter(Char::isDigit).take(3) }, Modifier.weight(1f),
                label = { Text("Clip length (sec)") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(vm.count, { vm.count = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                label = { Text("How many clips") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }
        Button({ vm.loadVideo() }, Modifier.fillMaxWidth().height(52.dp)) { Text("Find clips", fontSize = 16.sp) }
    }
}

@Composable
private fun TownSearch(vm: AppViewModel) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    fun locate() = scope.launch {
        vm.busy = "Finding your town…"
        val t = runCatching { Town.current(ctx) }.getOrNull()
        vm.busy = null
        if (t == null) {
            vm.error = "Couldn't find your location. Type your town instead."
        } else {
            vm.town = t
            vm.searchTown()
        }
    }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) {
            locate()
        } else {
            vm.error = "Location permission denied. Type your town instead."
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Videos about your town that are getting the most views right now. Tap one to make clips.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(vm.town, { vm.town = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("Town or city") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { vm.searchTown() }))
                Spacer(Modifier.width(8.dp))
                FilledTonalIconButton({ perm.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                    Icon(Icons.Default.LocationOn, "Use my location")
                }
            }
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Yt.Range.entries.forEach { r ->
                    FilterChip(vm.range == r, { vm.range = r }, label = { Text(r.label) })
                }
            }
        }
        item {
            Button({ vm.searchTown() }, Modifier.fillMaxWidth().height(50.dp)) {
                Icon(Icons.Default.Search, null); Spacer(Modifier.width(8.dp)); Text("Show popular videos")
            }
        }
        if (vm.hits.isNotEmpty()) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rank by", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                TownSort.entries.forEach { o ->
                    FilterChip(vm.townSort == o, { vm.townSort = o; vm.applyTownSort() }, label = { Text(o.label) },
                        modifier = Modifier.padding(end = 8.dp))
                }
            }
        }
        if (vm.searched && vm.hits.isEmpty()) item {
            Text("Nothing found for that time range. Try “This month” or a bigger nearby city.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        itemsIndexed(vm.hits) { i, h -> HitCard(i + 1, h) { vm.loadVideo(h.url) } }
        if (vm.hits.isNotEmpty()) item {
            Text("Only repost clips you have rights to — reposting other people's videos can get your channel a copyright strike.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HitCard(place: Int, h: SearchHit, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(10.dp)) {
            Box {
                AsyncImage(h.thumb, null, Modifier.width(140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop)
                Surface(color = Red, shape = RoundedCornerShape(4.dp), modifier = Modifier.align(Alignment.TopStart).padding(4.dp)) {
                    Text("#$place", fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 4.dp))
                }
                if (h.duration > 0) Surface(color = Color(0xCC000000), shape = RoundedCornerShape(4.dp),
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)) {
                    Text(fmtTime(h.duration), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp))
                }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(h.title, maxLines = 3, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(listOf(h.channel, fmtViews(h.views)).filter { it.isNotBlank() }.joinToString(" · "),
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

// =================================================================== CLIPS

@Composable
private fun Clips(vm: AppViewModel) {
    val v = vm.info ?: return
    val ctx = LocalContext.current
    val n = vm.clips.count { it.selected }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage("https://i.ytimg.com/vi/${v.id}/hqdefault.jpg", null,
                        Modifier.width(110.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(v.title, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${v.channel} · ${fmtTime(v.duration)}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (v.captionLang == null) Text("No captions on YouTube for this video", fontSize = 12.sp, color = Color(0xFFFFB74D))
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pick & rank clips", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton({ vm.addCustomClip() }) { Text("+ Custom") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip({ vm.sortBest() }, label = { Text("🔥 Best first") })
                    AssistChip({ vm.sortTimeline() }, label = { Text("In video order") })
                }
                Text("Top of the list = #1. Use the arrows to change the ranking.", fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            itemsIndexed(vm.clips) { i, c ->
                ClipCard(c, vm.rankOf(i), { vm.updateClip(i, it) }, { vm.moveClip(i, -1) }, { vm.moveClip(i, 1) }) {
                    Share.watchAt(ctx, v.id, c.startSec ?: 0.0)
                }
            }
            item { Options(vm) }
        }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Button({ vm.makeClips() }, Modifier.fillMaxWidth().padding(16.dp).height(52.dp), enabled = n > 0) {
                val label = when {
                    vm.mode == OutputMode.RANKING -> "Make Top $n video"
                    n == 1 -> "Make 1 clip"
                    else -> "Make $n clips"
                }
                Text(label, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun ClipCard(c: ClipRow, rank: Int?, onChange: (ClipRow) -> Unit, onUp: () -> Unit, onDown: () -> Unit, onWatch: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        border = if (c.selected) BorderStroke(2.dp, Red) else null,
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(c.selected, { onChange(c.copy(selected = it)) })
                if (rank != null) Surface(color = Red, shape = RoundedCornerShape(6.dp)) {
                    Text("#$rank", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Spacer(Modifier.width(6.dp))
                Column(Modifier.weight(1f)) {
                    if (c.score >= 0) Text("🔥 Hype ${c.score}/100", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(c.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                IconButton(onUp, Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                IconButton(onDown, Modifier.size(36.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                TextButton(onWatch) { Text("Watch") }
            }
            OutlinedTextField(c.title, { onChange(c.copy(title = it)) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Title") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(c.start, { onChange(c.copy(start = it)) }, Modifier.weight(1f), singleLine = true, label = { Text("Start") },
                    isError = c.startSec == null)
                OutlinedTextField(c.end, { onChange(c.copy(end = it)) }, Modifier.weight(1f), singleLine = true, label = { Text("End") },
                    isError = !c.valid)
                val d = if (c.valid) "${(c.endSec!! - c.startSec!!).toInt()}s" else "–"
                Text(d, Modifier.width(44.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Options(vm: AppViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.height(8.dp))
        Text("Format", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Layout.entries.forEach { l -> FilterChip(vm.layout == l, { vm.layout = l }, label = { Text(l.label) }) }
        }
        if (vm.layout == Layout.SPLIT) Text(
            "Clip on top, Block Runner parkour below — keeps viewers watching. Captions sit on the middle line.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Output", style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutputMode.entries.forEach { m -> FilterChip(vm.mode == m, { vm.mode = m }, label = { Text(m.label) }) }
        }
        if (vm.mode == OutputMode.RANKING) {
            OutlinedTextField(vm.rankingTitle, { vm.rankingTitle = it.take(100) }, Modifier.fillMaxWidth(),
                singleLine = true, label = { Text("Ranking video title") })
            Text("One video counting down from #${maxOf(2, vm.clips.count { it.selected })} to #1, with a rank badge on each part.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Captions", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(vm.captionsOn, { vm.captionsOn = it })
        }
        if (vm.captionsOn) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CaptionStyle.entries.forEach { s -> FilterChip(vm.style == s, { vm.style = s }, label = { Text(s.label) }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(vm.uppercase, { vm.uppercase = it }); Text("ALL CAPS")
                Spacer(Modifier.width(12.dp))
                Checkbox(vm.highlight, { vm.highlight = it }); Text("Highlight spoken word")
            }
        }
    }
}

// =================================================================== RESULTS

@Composable
private fun Results(vm: AppViewModel) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Your clips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(if (vm.working) "Keep the app open while clips are made." else "Clips are in your gallery under Movies/ClipForge.",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (vm.working) OutlinedButton({ vm.stop() }) { Text("Stop") }
            }
        }
        itemsIndexed(vm.outputs) { i, o -> OutputCard(o, ctx, { t, d -> vm.editOutput(i, t, d) }) }
    }
}

@Composable
private fun OutputCard(o: Output, ctx: Context, onEdit: (String?, String?) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(o.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(o.status, fontSize = 13.sp, color = if (o.failed) Color(0xFFFF8080) else MaterialTheme.colorScheme.onSurfaceVariant)
            if (!o.done && !o.failed) {
                val p = o.progress
                if (p != null) LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            val uri = o.uri
            if (o.done && uri != null) {
                OutlinedTextField(o.title, { onEdit(it, null) }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
                OutlinedTextField(o.description, { onEdit(null, it) }, Modifier.fillMaxWidth().heightIn(min = 90.dp),
                    label = { Text("Description") })
                Button({ copy(ctx, o); Share.toYouTube(ctx, uri, o.title, o.description) }, Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Post with YouTube app")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton({ Share.play(ctx, uri) }, Modifier.weight(1f)) { Text("Watch") }
                    OutlinedButton({ copy(ctx, o) }, Modifier.weight(1f)) { Text("Copy text") }
                    OutlinedButton({ Share.anywhere(ctx, uri, o.title, o.description) }, Modifier.weight(1f)) {
                        Icon(Icons.Default.Share, "Share")
                    }
                }
                Text("Title and description are copied when you tap Post — paste them into YouTube's upload screen.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun copy(ctx: Context, o: Output) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("ClipForge", o.title + "\n\n" + o.description))
    Toast.makeText(ctx, "Title & description copied", Toast.LENGTH_SHORT).show()
}
