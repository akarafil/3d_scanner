package com.magicv3.scanner3d.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.magicv3.scanner3d.domain.model.ScanSession
import com.magicv3.scanner3d.infra.storage.SessionFrameStore
import kotlinx.coroutines.launch

/**
 * Faz 1 — Karşılama Ekranı (Home UI).
 *
 * Cyberpunk temalı iki büyük dev buton içerir.
 */
@Composable
fun HomeScreen(
    store: SessionFrameStore,
    onStartNewScan: (ScanSession) -> Unit,
    onOpenMyScans: () -> Unit,
) {
    var showCreateDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F0F1A)) // Deep space background
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            // Cyberpunk Title
            Text(
                text = "MAGIC 3D SCANNER",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = Color.Cyan,
                textAlign = TextAlign.Center
            )
            Text(
                text = "AuxBypass Reconstruction Engine v2",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(64.dp))

            // Button 1: Yeni Proje Oluştur
            Button(
                onClick = { showCreateDialog = true },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(1.dp, Color.White, RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = "YENİ PROJE OLUŞTUR",
                    fontWeight = FontWeight.Bold,
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Button 2: Taramalarım
            Button(
                onClick = onOpenMyScans,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E1E2F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .border(1.dp, Color.Cyan, RoundedCornerShape(16.dp))
            ) {
                Text(
                    text = "TARAMALARIM",
                    fontWeight = FontWeight.Bold,
                    color = Color.Cyan,
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }

    if (showCreateDialog) {
        ProjectCreateDialog(
            onDismiss = { showCreateDialog = false },
            onConfirm = { name ->
                showCreateDialog = false
                scope.launch {
                    val session = store.createSession(name)
                    onStartNewScan(session)
                }
            }
        )
    }
}
