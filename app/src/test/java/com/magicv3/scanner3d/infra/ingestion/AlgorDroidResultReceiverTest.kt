package com.magicv3.scanner3d.infra.ingestion

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.verify
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * AlgorDroidResultReceiver sender doğrulaması testleri (B2).
 *
 * API 34+ `getSentFromPackage()` yalnızca sender identity-sharing ettiğinde non-null
 * döner. Birincil güvenlik katmanı manifest'teki signature permission olduğundan:
 *  - null sender → KABUL (signature permission zaten filtrelenmiş).
 *  - non-null ama yanlış paket → REDDET.
 *  - non-null ve engine paketi → KABUL.
 *
 * Robolectric ortamında getSentFromPackage() base implementasyonu null döndürdüğü için
 * senaryolar mockk-spy receiver + mock queue üzerinden doğrulanır.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlgorDroidResultReceiverTest {

    private lateinit var context: Context
    private lateinit var queue: IngestionQueue

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        queue = mockk(relaxed = true)
        // getInstance() @JvmStatic DEĞİL (companion instance metodu) → mockkStatic yerine
        // mockkObject(IngestionQueue.Companion) gerekir; aksi halde AbstractMethodError fırlar.
        mockkObject(IngestionQueue.Companion)
        every { IngestionQueue.getInstance(any()) } returns queue
        every { queue.isKnownSession(any()) } returns true
    }

    @After
    fun tearDown() {
        unmockkObject(IngestionQueue.Companion)
    }

    private fun progressIntent(): Intent =
        Intent(AlgorDroidResultReceiver.ACTION_PROCESSING_PROGRESS).apply {
            putExtra(AlgorDroidResultReceiver.EXTRA_SESSION_ID, UUID.randomUUID().toString())
            putExtra(AlgorDroidResultReceiver.EXTRA_PROGRESS_PERCENT, 42)
        }

    @Test
    fun `nullSender_kabulEdilir_signaturePermissionBirincilKatmandir`() {
        // B2: Engine identity paylaşmazsa (API 34+) getSentFromPackage() null döner —
        // null sender'ı koşulsuz reddetmek yerine signature permission güvenlik
        // kontratını sağladığı için kabul edilir; işlem queue.updateProgress'e ulaşır.
        val receiver = spyk(AlgorDroidResultReceiver())
        every { receiver.getSentFromPackage() } returns null

        val intent = progressIntent()
        receiver.onReceive(context, intent)

        verify { queue.updateProgress(any(), 42) }
    }

    @Test
    fun `yanlisPaketSender_reddedilir`() {
        // B2: Identity paylaşan ama engine paketi OLMAYAN sender reddedilir.
        val receiver = spyk(AlgorDroidResultReceiver())
        every { receiver.getSentFromPackage() } returns "com.evil.app"

        val intent = progressIntent()
        receiver.onReceive(context, intent)

        verify(exactly = 0) { queue.updateProgress(any(), any()) }
    }

    @Test
    fun `enginePaketiSender_kabulEdilir`() {
        // B2: Identity paylaşan ve engine paketiyle eşleşen sender kabul edilir.
        val receiver = spyk(AlgorDroidResultReceiver())
        every { receiver.getSentFromPackage() } returns "com.algordroid.engine"

        val intent = progressIntent()
        receiver.onReceive(context, intent)

        verify { queue.updateProgress(any(), 42) }
    }
}
