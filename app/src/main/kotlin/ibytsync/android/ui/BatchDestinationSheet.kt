package ibytsync.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ibytsync.android.ui.theme.liquidGlassPanel
import ibytsync.core.pipeline.AudioFormatChoice
import ibytsync.core.upload.Playlist
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchDestinationSheet(
    sheetState: SheetState,
    initialPlaylistId: String?,
    initialPlaylistName: String?,
    playlists: List<Playlist>,
    defaultFormat: AudioFormatChoice,
    skipDuplicates: Boolean,
    trackCount: Int = 0,
    initialPlaylistIds: List<String> = emptyList(),
    initialPlaylistNames: List<String> = emptyList(),
    onDismiss: () -> Unit,
    onCreatePlaylist: suspend (String) -> Playlist?,
    onConfirm: (
        selectedId: String?,
        selectedName: String?,
        format: AudioFormatChoice,
        skipDups: Boolean,
        setAsDefault: Boolean
    ) -> Unit = { _, _, _, _, _ -> },
    onConfirmMultiple: ((
        selectedId: String?,
        selectedName: String?,
        selectedIds: List<String>,
        selectedNames: List<String>,
        format: AudioFormatChoice,
        skipDups: Boolean,
        setAsDefault: Boolean
    ) -> Unit)? = null
) {
    val coroutineScope = rememberCoroutineScope()

    var selectedId by remember { mutableStateOf(initialPlaylistId) }
    var selectedName by remember { mutableStateOf(initialPlaylistName) }
    var selectedIds by remember {
        mutableStateOf(
            if (initialPlaylistIds.isNotEmpty()) initialPlaylistIds
            else if (initialPlaylistId != null) listOf(initialPlaylistId)
            else emptyList()
        )
    }
    var selectedNames by remember {
        mutableStateOf(
            if (initialPlaylistNames.isNotEmpty()) initialPlaylistNames
            else if (initialPlaylistName != null) listOf(initialPlaylistName)
            else emptyList()
        )
    }
    var chosenFormat by remember { mutableStateOf(defaultFormat) }
    var skipDups by remember { mutableStateOf(skipDuplicates) }
    var setAsDefault by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(initialPlaylistId, initialPlaylistName, initialPlaylistIds, initialPlaylistNames, defaultFormat, skipDuplicates) {
        if (!dirty) {
            selectedId = initialPlaylistId
            selectedName = initialPlaylistName
            selectedIds = if (initialPlaylistIds.isNotEmpty()) initialPlaylistIds
                          else if (initialPlaylistId != null) listOf(initialPlaylistId)
                          else emptyList()
            selectedNames = if (initialPlaylistNames.isNotEmpty()) initialPlaylistNames
                            else if (initialPlaylistName != null) listOf(initialPlaylistName)
                            else emptyList()
            chosenFormat = defaultFormat
            skipDups = skipDuplicates
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF141414),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 4.dp)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(Color(0x33FFFFFF))
            )
        }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .navigationBarsPadding()
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "BATCH DESTINATION",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1DB954),
                        letterSpacing = 1.sp
                    )
                    Text(
                        "Where to upload, and the audio quality to use",
                        fontSize = 12.sp,
                        color = Color(0xFF888888)
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color(0xFFAAAAAA))
                }
            }

            HorizontalDivider(color = Color(0x14FFFFFF))

            LazyColumn(
                Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    PlaylistSelectorView(
                        playlists = playlists,
                        selectedId = selectedId,
                        selectedName = selectedName,
                        selectedIds = selectedIds,
                        selectedNames = selectedNames,
                        batchDefaultName = null,
                        onCreatePlaylist = onCreatePlaylist,
                        onSelectionChanged = { ids, names ->
                            dirty = true
                            selectedIds = ids
                            selectedNames = names
                            selectedId = ids.firstOrNull()
                            selectedName = when {
                                names.size > 1 -> "${names.size} Playlists (${names.joinToString(", ")})"
                                names.size == 1 -> names.first()
                                else -> "Library Only"
                            }
                        },
                        onSelect = { id, name ->
                            dirty = true
                            selectedId = id
                            selectedName = name
                        },
                        maxListHeight = 220.dp
                    )
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "AUDIO FORMAT",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFAAAAAA),
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        "Applies to every track in this batch",
                        fontSize = 10.sp,
                        color = Color(0xFF777777)
                    )
                }

                item {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        for (fmt in AudioFormatChoice.values()) {
                            val isSel = chosenFormat == fmt
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) Color(0x2E1DB954) else Color(0x1AFFFFFF))
                                    .border(
                                        width = if (isSel) 1.5.dp else 1.dp,
                                        color = if (isSel) Color(0xFF1DB954) else Color(0x22FFFFFF),
                                        shape = RoundedCornerShape(6.dp)
                                    )
                                        .clickable {
                                            dirty = true
                                            chosenFormat = fmt
                                        }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    fmt.label,
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) Color(0xFF1DB954) else Color(0xFFCCCCCC)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "BATCH PREFERENCES",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFAAAAAA),
                        letterSpacing = 0.8.sp
                    )
                }

                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .liquidGlassPanel(cornerRadius = 8.dp)
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Skip Library Duplicates", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                Text("Skips tracks already in your iBroadcast library", fontSize = 11.sp, color = Color(0xFF777777))
                            }
                            Switch(
                                checked = skipDups,
                                onCheckedChange = {
                                    dirty = true
                                    skipDups = it
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFF1DB954)
                                )
                            )
                        }

                        HorizontalDivider(color = Color(0x14FFFFFF))

                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Remember as default", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                                Text("Use this destination, format and duplicate setting for every new batch", fontSize = 11.sp, color = Color(0xFF777777))
                            }
                            Switch(
                                checked = setAsDefault,
                                onCheckedChange = { setAsDefault = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.Black,
                                    checkedTrackColor = Color(0xFFFFB300)
                                )
                            )
                        }
                    }
                }
            }

            Button(
                onClick = {
                    if (onConfirmMultiple != null) {
                        onConfirmMultiple(selectedId, selectedName, selectedIds, selectedNames, chosenFormat, skipDups, setAsDefault)
                    } else {
                        onConfirm(selectedId, selectedName, chosenFormat, skipDups, setAsDefault)
                    }
                    onDismiss()
                },
                enabled = trackCount > 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1DB954),
                    disabledContainerColor = Color(0xFF262626),
                    disabledContentColor = Color(0xFF666666)
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = if (trackCount > 0) Color.Black else Color(0xFF666666), modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    "SAVE",
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = if (trackCount > 0) Color.Black else Color(0xFF666666)
                )
            }
        }
    }
}
