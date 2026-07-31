package com.magicv3.scanner3d.infra.ingestion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Faz 4.1 - AlgorDroid 3D Engine rekonstrüksiyon durumunu dinleyen BroadcastReceiver.
 */
class AlgorDroidResultReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AlgorDroidReceiver"

        const val ACTION_PROCESSING_PROGRESS = "com.algordroid.engine.ACTION_PROCESSING_PROGRESS"
        const val ACTION_PROCESSING_COMPLETE = "com.algordroid.engine.ACTION_PROCESSING_COMPLETE"
        const val ACTION_PROCESSING_ERROR = "com.algordroid.engine.ACTION_PROCESSING_ERROR"

        const val EXTRA_SESSION_ID = "com.algordroid.engine.EXTRA_SESSION_ID"
        const val EXTRA_PROGRESS_PERCENT = "com.algordroid.engine.EXTRA_PROGRESS_PERCENT"
        const val EXTRA_MESH_URI = "com.algordroid.engine.EXTRA_MESH_URI"
        const val EXTRA_ERROR_MESSAGE = "com.algordroid.engine.EXTRA_ERROR_MESSAGE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sessionId = intent.getStringExtra(EXTRA_SESSION_ID) ?: return

        when (intent.action) {
            ACTION_PROCESSING_PROGRESS -> {
                val progress = intent.getIntExtra(EXTRA_PROGRESS_PERCENT, 0)
                Log.i(TAG, "[$sessionId] Processing Progress: %$progress")
                IngestionQueue.getInstance(context).updateProgress(sessionId, progress)
            }

            ACTION_PROCESSING_COMPLETE -> {
                @Suppress("DEPRECATION")
                val meshUri: Uri? = intent.getParcelableExtra(EXTRA_MESH_URI)
                Log.i(TAG, "[$sessionId] Processing Complete! Mesh URI: $meshUri")
                if (meshUri != null) {
                    IngestionQueue.getInstance(context).markComplete(sessionId, meshUri)
                } else {
                    IngestionQueue.getInstance(context).markError(sessionId, "Engine complete intent has null mesh URI.")
                }
            }

            ACTION_PROCESSING_ERROR -> {
                val error = intent.getStringExtra(EXTRA_ERROR_MESSAGE) ?: "Bilinmeyen motor hatası"
                Log.e(TAG, "[$sessionId] Processing Error: $error")
                IngestionQueue.getInstance(context).markError(sessionId, error)
            }
        }
    }
}
