package by.tigre.speechhelper.data

import by.tigre.speechhelper.domain.AutoMarkupMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AutoMarkupParagraphPlannerDialogTest {

  private val dialogOriginal =
      "— Ладно, — хрипло проговорила она, поднимаясь. — Ладно. Осмотр."

  private val dialogShallowMarkup = """
      [voice_main]
      — Ладно, — хрипло проговорила она, поднимаясь. — Ладно. Осмотр.
      [/voice_main]
  """.trimIndent()

  @Test
  fun fillMissing_includesDialogUnsplitParagraph() {
    val rows = listOf(
      StoredChapterParagraph(0, "Narration only.", "[voice_main]\nText.\n[/voice_main]"),
      StoredChapterParagraph(1, dialogOriginal, dialogShallowMarkup),
    )
    val indices = AutoMarkupParagraphPlanner.paragraphIndicesToProcess(AutoMarkupMode.FillMissing, rows)
    assertEquals(listOf(1), indices)
  }

  @Test
  fun sourceTextForAi_fullRemarkUsesOriginalNotShallowMarkup() {
    val source = AutoMarkupParagraphPlanner.sourceTextForAi(
      dialogOriginal,
      dialogShallowMarkup,
      AutoMarkupMode.FullRemark,
    )
    assertTrue(!source.contains("[voice_main]"))
    assertTrue(source.contains("— Ладно"))
  }
}
