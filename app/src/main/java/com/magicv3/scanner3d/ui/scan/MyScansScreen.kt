package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import kotlinx.coroutines.launch

/**
 * Phase 2.3 — "Taramalarım" (My Scans) Screen.
 *
 * lists, deletes, and renames projects inside a cyberpunk themed dark container.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyScansScreen(
    store: SessionFrameStore,
    onClose: () -> Unit,
    onOpen: (ScanSession) -> Unit,
) {
    val sessions by store.sessions.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<ScanSession?>(null) }

    LaunchedEffect(Unit) {
        store.refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = "TARAMALARIM", 
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Geri",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                ),
                modifier = Modifier.border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background.copy(alpha = 0.95f)
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Henüz kayıtlı tarama yok.\nİlk projenizi oluşturmak için\nçekim butonuna basın.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(sessions, key = { it.sessionId }) { session ->
                    ProjectRow(
                        session = session,
                        onOpen = { onOpen(session) },
                        onDelete = { pendingDelete = session },
                        onRename = { newName ->
                            scope.launch { store.renameSession(session.sessionId, newName) }
                        },
                    )
                }
            }
        }
    }

    // Silme onay diyaloğu
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Projeyi sil?") },
            text = {
                Text("İsim: '${target.projectName}'\nKare sayısı: ${target.frameCount} kare\nBoyut: ${target.sizeFormatted}\n\nBu proje klasörü ve içerdiği fotoğraflar kalıcı olarak silinecektir.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            store.deleteSession(target.sessionId)
                            pendingDelete = null
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Sil") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Vazgeç") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProjectRow(
    session: ScanSession,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onRename: (String) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf(session.projectName) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable(enabled = !renaming) { onOpen() }
            .padding(16.dp)
    ) {
        Column {
            if (renaming) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Proje Adı") },
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        TextButton(
                            onClick = { 
                                renaming = false
                                onRename(newName) 
                            }
                        ) {
                            Text("Kaydet")
                        }
                    }
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = session.projectName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { renaming = true; newName = session.projectName }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Yeniden adlandır",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Sil",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "${session.dateFormatted}  •  ${session.frameCount} kare  •  ${session.sizeFormatted}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (session.status == ScanStatus.RENDERING) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "3D MODEL OLUŞTURULUYOR...",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else if (session.status == ScanStatus.COMPLETED) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "✓ 3D MODEL HAZIR",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Lens rozetleri (rozet listesi)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                session.lensesUsed.forEach { lens ->
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .border(
                                width = 0.5.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                shape = RoundedCornerShape(6.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = lens,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}
