package com.eloquick.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import com.eloquick.tts.sampleTextForRequest

/**
 * Answers the platform's `ACTION_GET_SAMPLE_TEXT` ("listen to an example"
 * in system TTS settings): returns this engine's own per-language sample
 * in `EXTRA_SAMPLE_TEXT` and finishes without ever showing UI
 * (`Theme.NoDisplay` - nothing here for TalkBack to land on). Without this
 * activity the system falls back to a generic English line even when the
 * engine is set to Hindi or German, despite the per-language samples
 * already existing for the in-app voice preview. The locale arrives as the
 * plain `language`/`country`/`variant` extras the platform documents (this
 * engine's own `onGetLanguage()` output echoed back - 3-letter codes like
 * "eng"/"USA"), resolved by [sampleTextForRequest], which always returns
 * something speakable rather than CANCELED.
 */
class GetSampleTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = sampleTextForRequest(
            intent?.getStringExtra("language"),
            intent?.getStringExtra("country"),
        )
        setResult(RESULT_OK, Intent().putExtra(TextToSpeech.Engine.EXTRA_SAMPLE_TEXT, text))
        finish()
    }
}
