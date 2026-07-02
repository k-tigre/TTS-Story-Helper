package by.tigre.speechhelper.domain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TextParserDialogTest {

  private val katyaDialogOriginal =
      "— Ладно, — хрипло проговорила она, поднимаясь. — Ладно. Осмотр."

  private val katyaDialogShallowMarkup = """
      [voice_main]
      — Ладно, — хрипло проговорила она, поднимаясь. — Ладно. Осмотр.
      [/voice_main]
  """.trimIndent()

  @Test
  fun hasDirectSpeech_detectsEmDashDialogue() {
    assertTrue(TextParser.hasDirectSpeech(katyaDialogOriginal))
  }

  @Test
  fun hasDirectSpeech_ignoresSingleDashInNarrative() {
    assertFalse(TextParser.hasDirectSpeech("Он понял — это был конец долгого дня."))
  }

  @Test
  fun needsDialogVoiceSplit_whenOnlyVoiceMainWrapsDialog() {
    assertTrue(TextParser.needsDialogVoiceSplit(katyaDialogShallowMarkup, katyaDialogOriginal))
  }

  @Test
  fun needsDialogVoiceSplit_falseWhenCharacterVoicePresent() {
    val marked = """
      [voice_actor]
      — Ладно, —
      [/voice_actor]
      [voice_main]
      хрипло проговорила она, поднимаясь.
      [/voice_main]
    """.trimIndent()
    assertFalse(TextParser.needsDialogVoiceSplit(marked, katyaDialogOriginal))
  }
}
