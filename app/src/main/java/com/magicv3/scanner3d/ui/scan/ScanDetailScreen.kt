package com.magicv3.scanner3d.ui.scan

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.magicv3.scanner3d.domain.model.ScanFrame
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.domain.model.ScanStatus
import com.magicv3.scanner3d.infra.ingestion.IngestionQueue
import com.magicv3.scanner3d.infra.ingestion.IngestionState
import com.magicv3.scanner3d.infra.storage.MeshRepository
import com.magicv3.scanner3d.infra.storage.ZipExporter

/**
 * Phase 2.4 & 4.3 — Tek bir projenin (scan) detay ekranı.
 *
 * Canlı Ingestion durum kartlarını ve 3D model yerel önizleme butonunu barındırır.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanDetailScreen(
    session: ScanSession,
    onClose: () -> Unit,
    onShareZip: (ScanSession) -> Unit,
    onResumeCapture: () -> Unit,
    onStart3DRender: () -> Unit,
) {
    val context = LocalContext.current
    val ingestionQueue = remember { IngestionQueue.getInstance(context) }
    val ingestionState by ingestionQueue.queueState.collectAsStateWithLifecycle()

    val meshRepository = remember { MeshRepository(context) }
    val hasMesh = remember(session, ingestionState) { meshRepository.hasMesh(session) }

    val currentSessionActive = remember(session, ingestionState) {
        val activeId = when (val s = ingestionState) {
            is IngestionState.Queued -> s.sessionId
            is IngestionState.Validating -> s.sessionId
            is IngestionState.Packaging -> s.sessionId
            is IngestionState.Transferring -> s.sessionId
            is IngestionState.Delivered -> s.sessionId
            is IngestionState.Reconstructing -> s.sessionId
            is IngestionState.Reconstructed -> s.sessionId
            is IngestionState.Failed -> s.sessionId
            else -> null
        }
        activeId == session.sessionId.toString()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = session.projectName.uppercase(),
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
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
                actions = {
                    IconButton(onClick = { onShareZip(session) }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = "ZIP olarak paylaş",
                            tint = MaterialTheme.colorScheme.primary
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Önce meta kartı: 2 kolonu span'leyen header item
            item(span = { GridItemSpan(2) }) { SessionMetaCard(session, onResumeCapture, onStart3DRender) }

            // Faz 4.3 — Ingestion/Reconstruction durum HUD'ı
            val showProgress = currentSessionActive && when (ingestionState) {
                is IngestionState.Idle,
                is IngestionState.Delivered,
                is IngestionState.Reconstructed,
                is IngestionState.Failed -> false
                else -> true
            }

            if (showProgress) {
                item(span = { GridItemSpan(2) }) { IngestionProgressCard(ingestionState) }
            }

            // Faz 4.3 — Yerel 3D Model Görüntüleme Butonu
            if (hasMesh) {
                item(span = { GridItemSpan(2) }) {
                    val meshFile = remember(session) { meshRepository.getMeshFile(session) }
                    MeshPreviewCard(session, meshFile)
                }
            }

            items(session.frames, key = { it.file.absolutePath }) { frame ->
                FrameThumbCard(frame = frame)
            }
        }
    }
}

@Composable
private fun SessionMetaCard(
    session: ScanSession,
    onResumeCapture: () -> Unit,
    onStart3DRender: () -> Unit
) {
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
            .padding(16.dp)
    ) {
        Column {
            Text(
                text = "${session.dateFormatted} • ${session.frameCount} kare • ${session.sizeFormatted}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1. Çekime Devam Et Butonu
                OutlinedButton(
                    onClick = onResumeCapture,
                    enabled = session.status == ScanStatus.DRAFT,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Çekime Devam Et", style = MaterialTheme.typography.labelMedium)
                }

                // 2. 3D Render'ı Başlat (AlgorDroid)
                Button(
                    onClick = onStart3DRender,
                    enabled = session.frameCount > 0 && session.status == ScanStatus.DRAFT,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Render'ı Başlat", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun IngestionProgressCard(state: IngestionState) {
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
            .padding(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title: String
            val desc: String
            val progress: Float?

            when (state) {
                is IngestionState.Queued -> {
                    title = "Kuyrukta Bekliyor"
                    desc = "İşlem sırası bekleniyor..."
                    progress = null
                }
                is IngestionState.Validating -> {
                    title = "EXIF Doğrulanıyor"
                    desc = "Karelerin metadata bütünlüğü kontrol ediliyor..."
                    progress = null
                }
                is IngestionState.Packaging -> {
                    title = "M3SP Paketi Hazırlanıyor"
                    desc = "${state.progress} / ${state.total} kare paketleniyor..."
                    progress = if (state.total > 0) state.progress.toFloat() / state.total else 0f
                }
                is IngestionState.Transferring -> {
                    title = "AlgorDroid'e Gönderiliyor"
                    desc = "M3SP paketi transfer ediliyor..."
                    progress = null
                }
                is IngestionState.Reconstructing -> {
                    title = "3D Model Oluşturuluyor"
                    desc = "Yüzey ağ yapısı hesaplanıyor: %${state.progress}"
                    progress = state.progress.toFloat() / 100f
                }
                else -> {
                    title = "İşleniyor"
                    desc = "Arka plan işlemleri yürütülüyor..."
                    progress = null
                }
            }

            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (progress != null) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun MeshPreviewCard(session: ScanSession, meshFile: java.io.File) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp)
            )
            .clickable {
                runCatching {
                    // Open GLB mesh model in default 3D viewer (ACTION_VIEW + FileProvider)
                    val uri = ZipExporter.frameUri(context, meshFile)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "model/gltf-binary")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(Intent.createChooser(intent, "3D Modeli Görüntüle"))
                }
            }
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("3D", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "3D MODEL HAZIR",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "Yerel 3D görüntüleyici ile incelemek için dokunun.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun FrameThumbCard(frame: ScanFrame) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable {
                runCatching {
                    val uri = ZipExporter.frameUri(context, frame.file)
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "image/jpeg")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(intent, "Kareyi görüntüle"))
                }
            }
    ) {
        Column {
            AsyncImage(
                model = frame.file,
                contentDescription = "Kare ${frame.lensId}",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .background(MaterialTheme.colorScheme.surface)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = frame.file.nameWithoutExtension,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
    }
}
