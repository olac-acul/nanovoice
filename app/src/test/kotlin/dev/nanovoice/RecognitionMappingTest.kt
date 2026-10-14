package dev.nanovoice

import android.speech.SpeechRecognizer
import dev.nanovoice.assistant.RecognitionErrors
import dev.nanovoice.voice.SttError
import kotlin.test.Test
import kotlin.test.assertEquals

class RecognitionMappingTest {
    @Test fun everyErrorMapsToAnAndroidRecognizerError() {
        assertEquals(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, RecognitionErrors.toAndroid(SttError.PermissionDenied))
        assertEquals(SpeechRecognizer.ERROR_NO_MATCH, RecognitionErrors.toAndroid(SttError.NoSpeech))
        assertEquals(SpeechRecognizer.ERROR_RECOGNIZER_BUSY, RecognitionErrors.toAndroid(SttError.Busy))
        assertEquals(SpeechRecognizer.ERROR_CLIENT, RecognitionErrors.toAndroid(SttError.NotAvailable))
        assertEquals(SpeechRecognizer.ERROR_CLIENT, RecognitionErrors.toAndroid(SttError.Other))
    }
}
