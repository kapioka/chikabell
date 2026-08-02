package com.chikabell.app.share

import android.content.Context
import android.content.Intent
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull

data class SharedPlaceEvent(
    val eventId: String,
    val place: ParsedSharedPlace,
)

object SharedIntentNormalizer {
    private const val MAX_CLIP_ITEMS = 16
    private const val ATTACHMENT_TIMEOUT_MS = 1_500L

    fun normalize(intent: Intent): SharedPlaceEvent? = normalizeInternal(intent, emptyList())

    suspend fun normalize(context: Context, intent: Intent): SharedPlaceEvent? {
        val metadataTexts = withTimeoutOrNull(ATTACHMENT_TIMEOUT_MS) {
            runInterruptible(Dispatchers.IO) {
                SharedAttachmentMetadataReader.read(context, intent)
            }
        }.orEmpty()
        return normalizeInternal(intent, metadataTexts)
    }

    private fun normalizeInternal(intent: Intent, metadataTexts: List<String>): SharedPlaceEvent? {
        val isSupportedSend = intent.action == Intent.ACTION_SEND &&
            (
                intent.type.equals("text/plain", true) ||
                    intent.type.equals("text/html", true) ||
                    intent.type.equals("image/png", true)
                )
        val isGeoView = intent.action == Intent.ACTION_VIEW &&
            intent.data?.scheme.equals("geo", true)
        if (!isSupportedSend && !isGeoView) return null

        val texts = buildList {
            intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.let(::add)
            intent.clipData?.let { clipData ->
                repeat(minOf(clipData.itemCount, MAX_CLIP_ITEMS)) { index ->
                    clipData.getItemAt(index).text?.toString()?.let(::add)
                }
            }
        }.distinct()
        val htmlTexts = buildList {
            intent.getStringExtra(Intent.EXTRA_HTML_TEXT)?.let(::add)
            intent.clipData?.let { clipData ->
                repeat(minOf(clipData.itemCount, MAX_CLIP_ITEMS)) { index ->
                    clipData.getItemAt(index).htmlText?.let(::add)
                }
            }
        }.distinct()
        val uris = buildList {
            intent.dataString?.let(::add)
            intent.clipData?.let { clipData ->
                repeat(minOf(clipData.itemCount, MAX_CLIP_ITEMS)) { index ->
                    clipData.getItemAt(index).uri?.toString()?.let(::add)
                }
            }
        }.distinct()
        if (texts.isEmpty() && htmlTexts.isEmpty() && uris.isEmpty() && metadataTexts.isEmpty()) return null

        val eventId = UUID.randomUUID().toString()
        val place = SharedPlaceParser.parse(
            subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)?.toString(),
            texts = texts,
            uris = uris,
            htmlTexts = htmlTexts,
            metadataTexts = metadataTexts,
            rootLineageId = "share-event:$eventId",
        )
        return SharedPlaceEvent(
            eventId = eventId,
            place = place,
        )
    }
}
