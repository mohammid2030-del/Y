package com.nova.iptv.data.subs

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/** On-device machine translation (Google ML Kit). The language model (~30 MB) downloads once. */
object SubtitleTranslator {
    suspend fun toArabic(cues: List<SrtCue>, sourceLang: String, onProgress: (Int) -> Unit): List<SrtCue> {
        val source = TranslateLanguage.fromLanguageTag(sourceLang.take(2)) ?: TranslateLanguage.ENGLISH
        val translator = Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(source).setTargetLanguage(TranslateLanguage.ARABIC).build()
        )
        try {
            onProgress(0)
            translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            val out = ArrayList<SrtCue>(cues.size)
            cues.forEachIndexed { i, c ->
                val plain = Srt.plain(c.text)
                val translated = if (plain.isBlank()) "" else runCatching { translator.translate(plain).await() }.getOrDefault(plain)
                out += c.copy(text = translated)
                if (i % 15 == 0) onProgress(i * 100 / cues.size.coerceAtLeast(1))
            }
            onProgress(100)
            return out
        } finally {
            translator.close()
        }
    }
}
