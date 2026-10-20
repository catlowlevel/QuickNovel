package com.lagradost.quicknovel

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Specifications for intended sentence boundaries, not snapshots of the current parser.
 *
 * A unit is a complete sentence or an unjoined paragraph fragment. Internal colons/semicolons,
 * abbreviations and dialogue attribution stay together. Quotes belong to their source sentence.
 * Assert source slices rather than spoken strings: speech cleanup is a separate concern.
 */
@RunWith(Parameterized::class)
class TTSSentenceSplittingTest(private val example: Example) {
    data class Example(val name: String, val text: String, val sentences: List<String>) {
        override fun toString(): String = name
    }

    @Test(timeout = 2_000)
    fun splitsIntoCompleteSentencesWithExactSourceOffsets() {
        val chapter = 7
        val lines = TTSHelper.ttsParseText(example.text, chapter)
        var previousEnd = 0
        for (line in lines) {
            assertEquals("Chapter identity", chapter, line.index)
            assertTrue("Ordered, non-overlapping offsets", line.startChar >= previousEnd)
            assertTrue("Nonempty source range", line.endChar > line.startChar)
            assertTrue("Offset within source", line.endChar <= example.text.length)
            assertTrue("Nonblank speech", line.speakOutMsg.isNotBlank())
            previousEnd = line.endChar
        }
        val sourceSlices = lines.map { example.text.substring(it.startChar, it.endChar) }
        assertEquals("Sentence count. Actual source slices:\n${sourceSlices.joinToString("\n") { "[$it]" }}",
            example.sentences.size, lines.size)
        var searchFrom = 0
        example.sentences.zip(lines).forEachIndexed { index, (sentence, line) ->
            assertEquals("Sentence $index source slice", sentence, sourceSlices[index])
            val expectedStart = example.text.indexOf(sentence, searchFrom)
            assertTrue("Expected sentence exists in fixture", expectedStart >= 0)
            assertEquals("Sentence start", expectedStart, line.startChar)
            assertEquals("Sentence end", expectedStart + sentence.length, line.endChar)
            searchFrom = line.endChar
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun examples(): List<Array<Any>> {
            val examples = ArrayList<Example>()
            fun example(name: String, text: String, vararg sentences: String) {
                examples += Example(name, text, sentences.toList())
            }

            example("empty input", "")
            example("whitespace has no speech units", " \t\r\n\n ")
            example("punctuation alone has no speech units", "... … ?! —")
            example("one complete sentence", "He left.", "He left.")
            example("unfinished final sentence", "He walked toward the gate", "He walked toward the gate")
            example("successive periods", "He left. She stayed.", "He left.", "She stayed.")
            example("exclamation ends a sentence", "Stop! Run.", "Stop!", "Run.")
            example("question ends a sentence", "Ready? Go.", "Ready?", "Go.")
            example("mixed repeated terminal punctuation", "Really?! Yes!! Go.", "Really?!", "Yes!!", "Go.")
            example("terminal punctuation without spaces", "Stop!Run?Yes.", "Stop!", "Run?", "Yes.")
            example("missing space after a full sentence", "He left.She stayed.", "He left.", "She stayed.")
            example("lowercase next sentence", "He left. she stayed.", "He left.", "she stayed.")
            example("commas do not split", "He turned, waved, and left.", "He turned, waved, and left.")
            example("semicolon stays inside a sentence", "He stopped; she continued. Then it rained.",
                "He stopped; she continued.", "Then it rained.")
            example("colon stays inside a sentence", "He said: wait here. Then left.", "He said: wait here.", "Then left.")
            example("unjoined line break", "He looked at\nher.", "He looked at", "her.")
            example("blank paragraphs do not produce empty units", "First.\n\n\nSecond.", "First.", "Second.")
            example("Windows paragraph breaks", "First.\r\n\r\nSecond.", "First.", "Second.")
            example("leading and trailing whitespace is outside sentence offsets", " \t First.  \n\tSecond. \t",
                "First.", "Second.")

            // Titles are distinct cases so one unsupported honorific cannot hide the others.
            for (title in listOf("Mr.", "Mrs.", "Dr.", "Ms.", "Prof.", "Capt.", "Lt.", "Gen.", "Rev.", "Sgt.", "St.")) {
                example("$title before a name", "$title Smith arrived. They waited.",
                    "$title Smith arrived.", "They waited.")
            }
            example("title inside a sentence", "He greeted Mr. Smith. They talked.", "He greeted Mr. Smith.", "They talked.")
            example("lowercase surname particle after Dr.", "He met Dr. van Helsing. They talked.",
                "He met Dr. van Helsing.", "They talked.")
            example("lowercase surname particle after Mr.", "He met Mr. de Vries. They talked.",
                "He met Mr. de Vries.", "They talked.")
            example("uppercase title", "DR. SMITH arrived. They waited.", "DR. SMITH arrived.", "They waited.")
            example("lowercase title", "He met dr. Smith. They talked.", "He met dr. Smith.", "They talked.")
            example("tab between title and name", "He met Mr.\tSmith. They talked.", "He met Mr.\tSmith.", "They talked.")
            example("multiple spaces after title", "He met Mrs.  Smith. They talked.", "He met Mrs.  Smith.", "They talked.")
            example("title within parentheses", "He met the doctor (Dr. Smith). They talked.",
                "He met the doctor (Dr. Smith).", "They talked.")
            example("title within quotation marks", "He met “Dr. Smith”. They talked.",
                "He met “Dr. Smith”.", "They talked.")
            example("several titles in one sentence", "Mr. Smith and Dr. Jones met Mrs. Green. They talked.",
                "Mr. Smith and Dr. Jones met Mrs. Green.", "They talked.")
            example("Jr. inside a sentence", "He met John Smith Jr. yesterday. They talked.",
                "He met John Smith Jr. yesterday.", "They talked.")
            example("Sr. inside a sentence", "John Smith Sr. arrived. They talked.", "John Smith Sr. arrived.", "They talked.")
            example("Jr. also ends the sentence", "He met John Smith Jr. He left.", "He met John Smith Jr.", "He left.")
            example("title does not swallow a paragraph break", "He called for Dr.\nSmith arrived.",
                "He called for Dr.", "Smith arrived.")

            example("A.I. before a lowercase continuation", "The A.I. watched him. He waved.",
                "The A.I. watched him.", "He waved.")
            example("A.I. before a capitalized noun", "The A.I. System started. He waited.",
                "The A.I. System started.", "He waited.")
            example("A.I. also ends the sentence", "He built an A.I. It worked.", "He built an A.I.", "It worked.")
            example("A.I. at end of input", "He built an A.I.", "He built an A.I.")
            example("U.S.A. within a sentence", "The U.S.A. team arrived. They won.", "The U.S.A. team arrived.", "They won.")
            example("U.S. before a capitalized noun", "The U.S. Army arrived. It advanced.",
                "The U.S. Army arrived.", "It advanced.")
            example("U.S. also ends the sentence", "He moved to the U.S. He stayed.", "He moved to the U.S.", "He stayed.")
            example("U.K. within a sentence", "The U.K. delegation arrived. It spoke.",
                "The U.K. delegation arrived.", "It spoke.")
            example("F.B.I. within a sentence", "F.B.I. agents arrived. We waited.", "F.B.I. agents arrived.", "We waited.")
            example("single personal initial", "A. Smith arrived. He waited.", "A. Smith arrived.", "He waited.")
            example("several spaced personal initials", "J. R. R. Tolkien wrote it. He published it.",
                "J. R. R. Tolkien wrote it.", "He published it.")
            example("several compact personal initials", "J.R.R. Tolkien wrote it. He published it.",
                "J.R.R. Tolkien wrote it.", "He published it.")
            example("initials inside a sentence", "I met A. B. Carter. We spoke.", "I met A. B. Carter.", "We spoke.")
            example("initials and acronyms together", "I met J. Smith and A.I. researchers. We talked.",
                "I met J. Smith and A.I. researchers.", "We talked.")

            example("e.g. introduces examples", "Bring supplies, e.g. food and water. Then leave.",
                "Bring supplies, e.g. food and water.", "Then leave.")
            example("i.e. introduces clarification", "Choose one, i.e. the red door. Then enter.",
                "Choose one, i.e. the red door.", "Then enter.")
            example("etc. inside a sentence", "Bring food, water, etc. before leaving. Then go.",
                "Bring food, water, etc. before leaving.", "Then go.")
            example("etc. also ends the sentence", "Bring food, water, etc. Then leave.",
                "Bring food, water, etc.", "Then leave.")
            example("vs. between opponents", "It was light vs. darkness. Light won.", "It was light vs. darkness.", "Light won.")
            example("No. before a number", "Read No. 5 first. Then stop.", "Read No. 5 first.", "Then stop.")
            example("Ch. before a chapter number", "Read Ch. 12 first. Then stop.", "Read Ch. 12 first.", "Then stop.")
            example("abbreviated month in a date", "It happened on Jan. 5, 2026. Snow fell.",
                "It happened on Jan. 5, 2026.", "Snow fell.")
            example("p.m. inside a sentence", "At 5 p.m. sharp, he left. She stayed.",
                "At 5 p.m. sharp, he left.", "She stayed.")
            example("p.m. also ends the sentence", "He left at 5 p.m. He returned later.",
                "He left at 5 p.m.", "He returned later.")
            example("a.m. and p.m. with clock times", "At 12:30 p.m., we left. We returned at 1:05 a.m.",
                "At 12:30 p.m., we left.", "We returned at 1:05 a.m.")

            example("decimal numbers", "It cost 3.14 coins. He paid 2.50.", "It cost 3.14 coins.", "He paid 2.50.")
            example("decimal before sentence-ending period", "The value was 3.14. Next.", "The value was 3.14.", "Next.")
            example("negative decimal", "It was -3.5 degrees. We froze.", "It was -3.5 degrees.", "We froze.")
            example("decimal without a leading zero", "Use .5 liters. Then stir.", "Use .5 liters.", "Then stir.")
            example("thousands separators", "He paid 1,234.56 coins. Then left.", "He paid 1,234.56 coins.", "Then left.")
            example("clock time", "At 12:30, he left. She stayed.", "At 12:30, he left.", "She stayed.")
            example("version number", "Version 1.2.3 works. Restart now.", "Version 1.2.3 works.", "Restart now.")
            example("IP address", "Connect to 192.168.0.1. Then wait.", "Connect to 192.168.0.1.", "Then wait.")
            example("email address", "Email hero@example.com. Then wait.", "Email hero@example.com.", "Then wait.")
            example("URL protocol and domain", "Open https://example.com/path. Then wait.",
                "Open https://example.com/path.", "Then wait.")
            example("file extension", "Open chapter.txt. Then continue.", "Open chapter.txt.", "Then continue.")

            example("ASCII ellipsis inside a sentence", "He hesitated... then continued. She waited.",
                "He hesitated... then continued.", "She waited.")
            example("ASCII ellipsis ends a sentence", "He hesitated... Then he spoke.", "He hesitated...", "Then he spoke.")
            example("Unicode ellipsis inside a sentence", "He hesitated… then continued. She waited.",
                "He hesitated… then continued.", "She waited.")
            example("Unicode ellipsis ends a sentence", "He hesitated… Then he spoke.", "He hesitated…", "Then he spoke.")
            example("em dash does not create a new sentence", "He hesitated—then left. She followed.",
                "He hesitated—then left.", "She followed.")
            example("straight quotation marks stay with their sentence", "\"Stop!\" She ran.", "\"Stop!\"", "She ran.")
            example("curly quotation marks stay with their sentence", "“Stop!” She ran.", "“Stop!”", "She ran.")
            example("exclamation with dialogue attribution", "“Stop!” he shouted. She ran.", "“Stop!” he shouted.", "She ran.")
            example("question with dialogue attribution", "\"Are you sure?\" she asked. He nodded.",
                "\"Are you sure?\" she asked.", "He nodded.")
            example("nested closing quotation marks", "“She said ‘go!’” He nodded.", "“She said ‘go!’”", "He nodded.")
            example("parenthetical punctuation inside a sentence", "He shouted (quietly!). Then left.",
                "He shouted (quietly!).", "Then left.")
            example("parenthesized complete sentence", "(Are you sure?) He nodded.", "(Are you sure?)", "He nodded.")
            example("UTF-16 offsets after emoji", "😀 Mr. Smith arrived. He waved 👋! Then left.",
                "😀 Mr. Smith arrived.", "He waved 👋!", "Then left.")
            example("Chinese punctuation and non-Latin speech", "你好。再见！真的吗？", "你好。", "再见！", "真的吗？")
            example("Arabic question mark and non-Latin speech", "مرحبا؟ نعم!", "مرحبا؟", "نعم!")

            example("named dialogue attribution", "“Go!” Sunny shouted. He ran.", "“Go!” Sunny shouted.", "He ran.")
            example("named action after dialogue is a new sentence", "“Go!” Sunny nodded. He ran.",
                "“Go!”", "Sunny nodded.", "He ran.")
            example("embedded quotation continues the outer sentence", "He said “Go!” and left. She stayed.",
                "He said “Go!” and left.", "She stayed.")
            example("parenthetical interruption", "He (really?!) kept walking. She followed.",
                "He (really?!) kept walking.", "She followed.")
            example("hyphenated surname", "He met Mr. Smith-Jones. They talked.",
                "He met Mr. Smith-Jones.", "They talked.")
            example("missing space before a title", "Done.Dr. Smith arrived.", "Done.", "Dr. Smith arrived.")
            example("acronym followed by comma", "A.I., robotics, and science interested him. He read on.",
                "A.I., robotics, and science interested him.", "He read on.")
            example("acronym inside a quotation", "“A.I.” was the name. He laughed.", "“A.I.” was the name.", "He laughed.")
            example("acronym in a hyphenated compound", "The A.I.-powered drone stopped. It waited.",
                "The A.I.-powered drone stopped.", "It waited.")
            example("title does not swallow an independent following sentence", "He needed a Dr. He waited.",
                "He needed a Dr.", "He waited.")
            example("pronoun I is not always a personal initial", "He was I. He left.", "He was I.", "He left.")
            example("I can also be a personal initial", "A. I. Smith arrived. We waited.", "A. I. Smith arrived.", "We waited.")
            example("URL with query punctuation", "Visit https://example.com/search?q=one.two&x=3. Then wait.",
                "Visit https://example.com/search?q=one.two&x=3.", "Then wait.")
            example("email address with initials and uppercase domain", "Write to A.Smith@example.COM. Then wait.",
                "Write to A.Smith@example.COM.", "Then wait.")
            example("supplementary Unicode letters retain UTF-16 positions", "𠀀。𠀁！", "𠀀。", "𠀁！")
            example("nonbreaking spaces around sentences", "\u00a0First.\u00a0Second.\u00a0", "First.", "Second.")
            example("ellipsis with quotation and following sentence", "He waited… then whispered, “go.” She left.",
                "He waited… then whispered, “go.”", "She left.")
            example("quoted paragraphs stay separate", "“First.”\n“Second!”", "“First.”", "“Second!”")
            example("emoji and punctuation alone have no speech units", "😀!!! …")
            example("ellipsis and question punctuation cluster", "Wait…?! Really?!", "Wait…?!", "Really?!")
            example("surname He after Mr. is not a new sentence", "Mr. He arrived. They waited.",
                "Mr. He arrived.", "They waited.")
            example("surname He after Dr. is not a new sentence", "Dr. He spoke. They listened.",
                "Dr. He spoke.", "They listened.")

            return examples.map { arrayOf<Any>(it) }
        }
    }
}
