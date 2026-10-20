package com.lagradost.quicknovel

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import com.lagradost.quicknovel.DataStore.getKey
import java.util.Locale

/** A short-lived engine independent of the book's speech queue, progress and bookmarks. */
internal class AndroidTTSPreviewOutput(context: Context, private val speed: Float, private val pitch: Float) :
    TTSPreviewOutput {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val audio = this.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var closed = false
    private var completion: ((Boolean) -> Unit)? = null
    private var utterance = 0
    private var registered = false
    private var ownsFocus = false
    private val focusListener =
        AudioManager.OnAudioFocusChangeListener { change -> if (change < 0) main.post { finish(false) } }
    private val focusRequest =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener, main)
                .build()
        } else null
    private val noisy =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                finish(false)
            }
        }

    override val maxInputLength: Int
        get() = TextToSpeech.getMaxSpeechInputLength()

    override fun prepare(ready: (Boolean) -> Unit) {
        tts =
            TextToSpeech(context) { status ->
                // Posting also handles engines that initialize before the constructor returns.
                main.post {
                    if (closed) return@post
                    val engine = tts ?: return@post
                    if (status != TextToSpeech.SUCCESS) {
                        ready(false)
                        return@post
                    }
                    val localeName = context.getKey<String>(EPUB_LANG)
                    val voiceName = context.getKey<String>(EPUB_VOICE)
                    val language =
                        engine.setLanguage(
                            engine.availableLanguages?.firstOrNull { it.displayName == localeName } ?: Locale.US
                        )
                    if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
                        ready(false)
                        return@post
                    }
                    engine.voice = engine.voices?.firstOrNull { it.name == voiceName } ?: engine.defaultVoice
                    engine.setSpeechRate(speed)
                    engine.setPitch(pitch)
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    engine.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) = Unit

                            override fun onDone(utteranceId: String?) = completed(utteranceId, true)

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String?) = completed(utteranceId, false)

                            override fun onError(utteranceId: String?, errorCode: Int) = completed(utteranceId, false)
                        }
                    )
                    @Suppress("DEPRECATION")
                    val focus =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            audio.requestAudioFocus(requireNotNull(focusRequest))
                        } else
                            audio.requestAudioFocus(
                                focusListener,
                                AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
                            )
                    ownsFocus = focus == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
                    if (ownsFocus) {
                        ContextCompat.registerReceiver(
                            context,
                            noisy,
                            IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                            ContextCompat.RECEIVER_NOT_EXPORTED,
                        )
                        registered = true
                    }
                    ready(ownsFocus)
                }
            }
    }

    override fun speak(text: String, finished: (Boolean) -> Unit) {
        if (closed) return
        completion = finished
        val id = (++utterance).toString()
        if (tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) finish(false)
    }

    private fun completed(id: String?, success: Boolean) {
        main.post { if (id == utterance.toString()) finish(success) }
    }

    private fun finish(success: Boolean) {
        if (closed) return
        val callback = completion
        completion = null
        callback?.invoke(success)
    }

    override fun close() {
        if (closed) return
        closed = true
        completion = null
        tts?.stop()
        tts?.setOnUtteranceProgressListener(null)
        tts?.shutdown()
        tts = null
        if (registered) context.unregisterReceiver(noisy)
        registered = false
        if (ownsFocus) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audio.abandonAudioFocusRequest(requireNotNull(focusRequest))
            } else audio.abandonAudioFocus(focusListener)
        }
        ownsFocus = false
    }
}
