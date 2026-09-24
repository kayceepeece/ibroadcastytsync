package ibytsync.android.ui

import ibytsync.android.ui.theme.liquidGlassPanel
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ibytsync.core.upload.Playlist
import ibytsync.core.upload.PlaylistMutations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Unified, reusable playlist viewer & creator component.
 * Reused across Batch Destination Settings and the Choose a Match screen.
 * Optimized for scaling from 1 to 100+ playlists with instant search filtering.
 */
@Composable
fun PlaylistSelectorView(
    playlists: List<Playlist>,
    selectedId: String? = null,
    selectedName: String? = null,
    selectedIds: List<String> = emptyList(),
    selectedNames: List<String> = emptyList(),
    batchDefaultName: String? = null,
    isFollowingDefault: Boolean = false,
    onResetToDefault: (() -> Unit)? = null,
    onCreatePlaylist: (suspend (String) -> Playlist?)? = null,
    onSelect: (id: String?, name: String) -> Unit = { _, _ -> },
    onSelectionChanged: ((ids: List<String>, names: List<String>) -> Unit)? = null,
    modifier: Modifier = Modifier,
    maxListHeight: androidx.compose.ui.unit.Dp = 280.dp
) {
    val coroutineScope = rememberCoroutineScope()
    var localPlaylists by remember { mutableStateOf(playlists) }
    LaunchedEffect(playlists) {
        localPlaylists = playlists
    }

    var searchQuery by remember { mutableStateOf("") }
    val filteredPlaylists = remember(localPlaylists, searchQuery) {
        val list = if (searchQuery.isBlank()) localPlaylists else localPlaylists.filter {
            it.name.contains(searchQuery, ignoreCase = true)
        }
        PlaylistMutations.sortedPlaylists(list)
    }

    var newPlaylistName by remember { mutableStateOf("") }
    var isCreatingPlaylist by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    var currentSelectedIds by remember(selectedId, selectedIds) {
        val initial = if (selectedIds.isNotEmpty()) {
            selectedIds.filter { it.isNotBlank() }
        } else if (!selectedId.isNullOrBlank()) {
            listOf(selectedId)
        } else {
            emptyList()
        }
        mutableStateOf(initial)
    }

    var currentSelectedNames by remember(selectedName, selectedNames) {
        val initial = if (selectedNames.isNotEmpty()) {
            selectedNames.filter { it.isNotBlank() }
        } else if (!selectedName.isNullOrBlank()) {
            listOf(selectedName)
        } else {
            emptyList()
        }
        mutableStateOf(initial)
    }

    LaunchedEffect(selectedId, selectedIds, selectedName, selectedNames) {
        val newIds = if (selectedIds.isNotEmpty()) selectedIds.filter { it.isNotBlank() }
                     else if (!selectedId.isNullOrBlank()) listOf(selectedId)
                     else emptyList()
        val newNames = if (selectedNames.isNotEmpty()) selectedNames.filter { it.isNotBlank() }
                       else if (!selectedName.isNullOrBlank()) listOf(selectedName)
                       else emptyList()
        currentSelectedIds = newIds
        currentSelectedNames = newNames
    }

    val isLibOnly = currentSelectedIds.contains("lib_only") || (currentSelectedNames.contains("Library Only") && currentSelectedIds.isEmpty())

    fun selectLibOnly() {
        val libId = if (batchDefaultName != null) "lib_only" else null
        currentSelectedIds = if (libId != null) listOf(libId) else emptyList()
        currentSelectedNames = listOf("Library Only")
        onSelectionChanged?.invoke(if (libId != null) listOf(libId) else emptyList(), listOf("Library Only"))
        onSelect(libId, "Library Only")
    }

    fun togglePlaylist(pl: Playlist) {
        val newIds = currentSelectedIds.filter { it != "lib_only" }.toMutableList()
        val newNames = currentSelectedNames.filter { it != "Library Only" && !it.startsWith("Batch Default") }.toMutableList()

        if (newIds.contains(pl.id)) {
            newIds.remove(pl.id)
            newNames.remove(pl.name)
        } else {
            newIds.add(pl.id)
            newNames.add(pl.name)
        }

        currentSelectedIds = newIds
        currentSelectedNames = newNames

        if (newIds.isEmpty()) {
            selectLibOnly()
        } else {
            onSelectionChanged?.invoke(newIds, newNames)
            val displayName = if (newNames.size > 1) "${newNames.size} Playlists (${newNames.joinToString(", ")})" else newNames.first()
            onSelect(newIds.first(), displayName)
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "DESTINATION PLAYLIST",
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFAAAAAA),
                letterSpacing = 0.8.sp
            )
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier
                    .background(Color(0x221DB954), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    "${localPlaylists.size}",
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1DB954)
                )
            }
            val activePlaylistCount = currentSelectedIds.filter { it != "lib_only" }.size
            if (activePlaylistCount > 0) {
                Spacer(Modifier.weight(1f))
                Text(
                    "$activePlaylistCount selected",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1DB954)
                )
            }
        }

        // Search Bar - pinned to the top immediately after section header
        if (localPlaylists.size > 2 || searchQuery.isNotEmpty()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Filter ${localPlaylists.size} playlists…", fontSize = 11.sp, color = Color(0xFF666666)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = Color(0xFF888888), modifier = Modifier.size(15.dp)) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear", tint = Color(0xFF888888), modifier = Modifier.size(15.dp))
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF1DB954),
                    unfocusedBorderColor = Color(0x33FFFFFF),
                    focusedContainerColor = Color(0xFF1A1A1A),
                    unfocusedContainerColor = Color(0xFF161616),
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }

        // Only show batch default status and library only when NOT searching
        if (searchQuery.isBlank()) {
            if (batchDefaultName != null && batchDefaultName != "Library Only") {
                if (isFollowingDefault) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlassPanel(cornerRadius = 8.dp)
                            .border(1.5.dp, Color(0xFF1DB954), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFF1DB954), modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Following Batch Default", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White)
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .background(Color(0x2E1DB954), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 5.dp, vertical = 1.dp)
                                ) {
                                    Text("ACTIVE", fontSize = 8.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF1DB954))
                                }
                            }
                            Text("Included by default: $batchDefaultName (tap any playlist below to add or remove)", fontSize = 10.sp, color = Color(0xFF888888))
                        }
                    }
                } else if (onResetToDefault != null) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlassPanel(cornerRadius = 8.dp)
                            .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Custom Playlist Selection", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFCCCCCC))
                            Text("Batch default is \"$batchDefaultName\"", fontSize = 10.sp, color = Color(0xFF777777))
                        }
                        TextButton(
                            onClick = onResetToDefault,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "RESET TO DEFAULT",
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1DB954)
                            )
                        }
                    }
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .liquidGlassPanel(cornerRadius = 8.dp)
                    .border(
                        width = if (isLibOnly) 1.5.dp else 1.dp,
                        color = if (isLibOnly) Color(0xFF1DB954) else Color(0x1FFFFFFF),
                        shape = RoundedCornerShape(8.dp)
                    )
                    .clickable { selectLibOnly() }
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = isLibOnly,
                    onClick = { selectLibOnly() },
                    colors = RadioButtonDefaults.colors(selectedColor = Color(0xFF1DB954))
                )
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFF29B6F6), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("Library Only (No Playlist)", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                    Text("Upload directly to cloud library", fontSize = 10.sp, color = Color(0xFF777777))
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxListHeight),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            items(filteredPlaylists, key = { it.id }) { pl ->
                val isSelected = currentSelectedIds.contains(pl.id)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .liquidGlassPanel(cornerRadius = 8.dp)
                        .border(
                            width = if (isSelected) 1.5.dp else 1.dp,
                            color = if (isSelected) Color(0xFF1DB954) else Color(0x1FFFFFFF),
                            shape = RoundedCornerShape(8.dp)
                        )
                        .clickable { togglePlaylist(pl) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { togglePlaylist(pl) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF1DB954),
                            checkmarkColor = Color.Black,
                            uncheckedColor = Color(0x66FFFFFF)
                        )
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.AutoMirrored.Filled.List,
                        contentDescription = null,
                        tint = if (isSelected) Color(0xFF1DB954) else Color(0xFF888888),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        pl.name,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            if (filteredPlaylists.isEmpty() && searchQuery.isNotBlank()) {
                item {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("No playlists matching \"$searchQuery\"", fontSize = 11.sp, color = Color(0xFF888888))
                        Spacer(Modifier.height(2.dp))
                        TextButton(onClick = { newPlaylistName = searchQuery }) {
                            Text("CREATE WITH THIS NAME", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = Color(0xFF1DB954))
                        }
                    }
                }
            }
        }

        if (onCreatePlaylist != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .liquidGlassPanel(cornerRadius = 8.dp)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "+ CREATE NEW PLAYLIST",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1DB954)
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPlaylistName,
                        onValueChange = { newPlaylistName = it; playlistError = null },
                        placeholder = { Text("New playlist name…", fontSize = 11.sp, color = Color(0xFF666666)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        textStyle = TextStyle(fontSize = 12.sp, color = Color.White),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF1DB954),
                            unfocusedBorderColor = Color(0x33FFFFFF),
                            focusedContainerColor = Color(0xFF1A1A1A),
                            unfocusedContainerColor = Color(0xFF161616)
                        ),
                        shape = RoundedCornerShape(6.dp)
                    )
                    Button(
                        onClick = {
                            val trimmed = newPlaylistName.trim()
                            if (trimmed.isNotBlank()) {
                                isCreatingPlaylist = true
                                playlistError = null
                                coroutineScope.launch {
                                    val created = withContext(Dispatchers.IO) { onCreatePlaylist(trimmed) }
                                    isCreatingPlaylist = false
                                    if (created != null) {
                                        localPlaylists = localPlaylists + created
                                        togglePlaylist(created)
                                        newPlaylistName = ""
                                    } else {
                                        playlistError = "Failed to create playlist on iBroadcast"
                                    }
                                }
                            }
                        },
                        enabled = newPlaylistName.isNotBlank() && !isCreatingPlaylist,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF1DB954),
                            disabledContainerColor = Color(0xFF262626)
                        ),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier.height(46.dp)
                    ) {
                        if (isCreatingPlaylist) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Color.Black, strokeWidth = 2.dp)
                        } else {
                            Text("+ CREATE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                        }
                    }
                }
                if (playlistError != null) {
                    Text(playlistError!!, fontSize = 10.sp, color = Color(0xFFE91429))
                }
            }
        }
    }
}

/**
 * Dedicated Bottom Sheet modal for selecting or creating a playlist.
 * Reusable anywhere in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerBottomSheet(
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    playlists: List<Playlist>,
    selectedId: String? = null,
    selectedName: String? = null,
    selectedIds: List<String> = emptyList(),
    selectedNames: List<String> = emptyList(),
    batchDefaultName: String? = null,
    batchDefaultIds: List<String> = emptyList(),
    batchDefaultNames: List<String> = emptyList(),
    onCreatePlaylist: (suspend (String) -> Playlist?)? = null,
    onSelect: (id: String?, name: String) -> Unit = { _, _ -> },
    onSelectMultiple: ((ids: List<String>, names: List<String>) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    val isInitiallyUsingBatchDefault = batchDefaultName != null &&
        batchDefaultName != "Library Only" &&
        (selectedIds.isEmpty() || selectedName == null || selectedName.startsWith("Batch Default")) &&
        !selectedIds.contains("lib_only") &&
        selectedName != "Library Only"

    val resolvedBatchDefaultIds = remember(playlists, batchDefaultName, batchDefaultIds) {
        if (batchDefaultIds.isNotEmpty()) batchDefaultIds
        else if (batchDefaultName != null && batchDefaultName != "Library Only") {
            listOfNotNull(playlists.firstOrNull { it.name.equals(batchDefaultName, ignoreCase = true) }?.id)
        } else emptyList()
    }

    val resolvedBatchDefaultNames = remember(playlists, batchDefaultName, batchDefaultNames) {
        if (batchDefaultNames.isNotEmpty()) batchDefaultNames
        else if (batchDefaultName != null && batchDefaultName != "Library Only") {
            listOf(batchDefaultName)
        } else emptyList()
    }

    var isFollowingDefault by remember(selectedId, selectedIds, selectedName, selectedNames) {
        mutableStateOf(isInitiallyUsingBatchDefault)
    }

    var stagedIds by remember(selectedId, selectedIds, isInitiallyUsingBatchDefault) {
        val initial = when {
            isInitiallyUsingBatchDefault -> resolvedBatchDefaultIds
            selectedIds.isNotEmpty() -> selectedIds.filter { it.isNotBlank() }
            !selectedId.isNullOrBlank() -> listOf(selectedId)
            else -> emptyList()
        }
        mutableStateOf(initial)
    }

    var stagedNames by remember(selectedName, selectedNames, isInitiallyUsingBatchDefault) {
        val initial = when {
            isInitiallyUsingBatchDefault -> resolvedBatchDefaultNames
            selectedNames.isNotEmpty() -> selectedNames.filter { it.isNotBlank() }
            !selectedName.isNullOrBlank() -> listOf(selectedName)
            else -> emptyList()
        }
        mutableStateOf(initial)
    }

    fun commitAndDismiss() {
        if (isFollowingDefault) {
            val defName = "Batch Default ($batchDefaultName)"
            onSelectMultiple?.invoke(emptyList(), listOf(defName))
            onSelect(null, defName)
            onDismiss()
            return
        }

        val validIds = stagedIds.filter { it != "lib_only" }
        val validNames = stagedNames.filter { it != "Library Only" && !it.startsWith("Batch Default") }

        onSelectMultiple?.invoke(stagedIds, stagedNames)

        val primaryId = if (stagedIds.contains("lib_only")) {
            if (batchDefaultName != null) "lib_only" else null
        } else validIds.firstOrNull()

        val displayName = when {
            stagedIds.contains("lib_only") || stagedNames.contains("Library Only") -> "Library Only"
            validNames.size > 1 -> "${validNames.size} Playlists (${validNames.joinToString(", ")})"
            validNames.size == 1 -> validNames.first()
            else -> "Library Only"
        }
        onSelect(primaryId, displayName)
        onDismiss()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding()
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "DESTINATION PLAYLIST",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1DB954),
                        letterSpacing = 1.sp
                    )
                    val count = stagedIds.filter { it != "lib_only" }.size
                    val subtitle = when {
                        isFollowingDefault -> "Following batch default: $batchDefaultName"
                        stagedIds.contains("lib_only") -> "Library Only (No Playlist)"
                        count > 1 -> "$count playlists selected"
                        count == 1 -> "1 playlist selected (${stagedNames.firstOrNull() ?: ""})"
                        else -> "Select destination for this track"
                    }
                    Text(
                        subtitle,
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
                Button(
                    onClick = { commitAndDismiss() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1DB954)),
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        "DONE",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                }
                Spacer(Modifier.width(4.dp))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Clear, contentDescription = "Close", tint = Color(0xFFAAAAAA))
                }
            }
            HorizontalDivider(color = Color(0x14FFFFFF), modifier = Modifier.padding(vertical = 8.dp))

            PlaylistSelectorView(
                playlists = playlists,
                selectedId = selectedId,
                selectedName = selectedName,
                selectedIds = stagedIds,
                selectedNames = stagedNames,
                batchDefaultName = batchDefaultName,
                isFollowingDefault = isFollowingDefault,
                onResetToDefault = if (batchDefaultName != null && batchDefaultName != "Library Only") {
                    {
                        isFollowingDefault = true
                        stagedIds = resolvedBatchDefaultIds
                        stagedNames = resolvedBatchDefaultNames
                    }
                } else null,
                onCreatePlaylist = onCreatePlaylist,
                onSelectionChanged = { ids, names ->
                    isFollowingDefault = false
                    stagedIds = ids
                    stagedNames = names
                },
                onSelect = { _, _ -> },
                maxListHeight = 360.dp
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}
