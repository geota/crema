package coffee.crema.history

import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #310 A1, N3: Kotlin never opens the SQLite history's files through the
 * framework. Two SQLite copies in one process may open different files, never
 * the same one: closing it in one drops the other's POSIX locks (SPEC §11).
 * So the main sources are scanned for every framework open (`openDatabase`,
 * `openOrCreateDatabase`, `getDatabasePath`, `deleteDatabase`, an
 * `SQLiteOpenHelper`, Room) whose arguments name a `history*.db` file or the
 * `history-sqlite/` directory, and for a `history*.db` name anywhere but
 * [HistoryIndex] and `HistoryStage2.kt` (#310 A5), which hand them to the core. The scanner is checked on
 * planted offenders, so a scan that finds nothing isn't a scan that can't.
 */
class HistoryIndexFrameworkAccessTest {
    private val main = listOf(File("src/main/java"), File("src/main/kotlin")).filter { it.isDirectory }

    @Test fun `no framework open of a history db in the main sources`() {
        assertTrue(main.isNotEmpty(), "the main sources are found from ${File(".").absolutePath}")
        val files = main.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue(files.size > 100, "the scan reads the app's sources (${files.size} files)")
        assertTrue(files.any { it.name == "HistoryIndex.kt" } && files.any { it.name == "HistoryStage2.kt" })
        val found = files.flatMap { FrameworkOpens.offenders(it.name, it.readText()) }
        assertEquals(emptyList(), found)
    }

    @Test fun `the scan finds planted offenders`() {
        val planted = mapOf(
            "A.kt" to "val db = SQLiteDatabase.openDatabase(File(dir, HistoryIndex.FILE_NAME).path, null, 0)",
            "B.kt" to "fun f(c: Context) = c.openOrCreateDatabase(\"history.db\", 0, null)",
            "C.kt" to "class Index(c: Context) : SQLiteOpenHelper(c, \"history-index.db\", null, 1)",
            "D.kt" to "val f = File(filesDir, \"history-3.db\")",
            "E.kt" to "fun g() = context.getDatabasePath(\n    SQLITE_DIR + \"/x\"\n)",
            "F.kt" to "val b = Room.databaseBuilder(c, X::class.java, File(c.filesDir, \"history-sqlite/history.db\").path)",
        )
        for ((name, code) in planted) {
            assertTrue(FrameworkOpens.offenders(name, code).isNotEmpty(), "$name: $code")
        }
    }

    @Test fun `comments and the store's own file don't count`() {
        val fine = """
            // SQLiteDatabase.openDatabase("history.db") in a comment
            /* context.openOrCreateDatabase("history-index.db", 0, null) */
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            class HistoryDb(c: Context, name: String) : SQLiteOpenHelper(c, name, null, 1)
            val url = "https://example.com/history.db"
        """.trimIndent()
        assertEquals(emptyList(), FrameworkOpens.offenders("Z.kt", fine))
        assertEquals(emptyList(), FrameworkOpens.offenders("HistoryIndex.kt", "const val FILE_NAME = \"history-index.db\""))
    }
}

/** The scanner: framework opens whose arguments name the SQLite history, and stray `history*.db` names. */
internal object FrameworkOpens {
    private val apis = Regex("""\b(openDatabase|openOrCreateDatabase|getDatabasePath|deleteDatabase|databaseBuilder|inMemoryDatabaseBuilder|SQLiteOpenHelper|SQLiteDatabase\.create)\s*\(""")
    private val history = Regex("""history[\w.-]*\.db|history-sqlite|HistoryIndex|SQLITE_DIR|SQLITE_DONE_NAME""")
    private val dbName = Regex("""history[\w-]*\.db""")

    /** The files that may name the SQLite history's files: they pass the names to the core. */
    private val OWNERS = setOf("HistoryIndex.kt", "HistoryStage2.kt")

    fun offenders(name: String, source: String): List<String> {
        val (code, strings) = lex(source)
        val out = ArrayList<String>()
        for (m in apis.findAll(code)) {
            val args = argsAt(code, m.range.last)
            if (history.containsMatchIn(args)) out += "$name: ${m.groupValues[1]}(${args.trim()})"
        }
        if (name !in OWNERS) {
            for (s in strings) if (dbName.containsMatchIn(s) && !s.contains("://")) out += "$name: the name \"$s\""
        }
        return out
    }

    /** The text inside the parenthesis that opens at [open], with string contents put back. */
    private fun argsAt(code: String, open: Int): String {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return code.substring(open + 1, i)
            }
        }
        return code.substring(open + 1)
    }

    /**
     * [source] with comments blanked out, string literals kept in place, and
     * the literals' contents on their own: a comment that names a file never
     * counts, a literal always does.
     */
    private fun lex(source: String): Pair<String, List<String>> {
        val code = StringBuilder(source.length)
        val strings = ArrayList<String>()
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    val end = source.indexOf("*/", i + 2).let { if (it < 0) source.length else it + 2 }
                    for (k in i until end) if (source[k] == '\n') code.append('\n')
                    i = end
                }
                source.startsWith("\"\"\"", i) -> {
                    val end = source.indexOf("\"\"\"", i + 3).let { if (it < 0) source.length else it + 3 }
                    val body = source.substring(i, end)
                    strings += body.removePrefix("\"\"\"").removeSuffix("\"\"\"")
                    code.append(body)
                    i = end
                }
                c == '"' -> {
                    var k = i + 1
                    while (k < source.length && source[k] != '"' && source[k] != '\n') k += if (source[k] == '\\') 2 else 1
                    val end = minOf(k + 1, source.length)
                    strings += source.substring(i + 1, minOf(k, source.length))
                    code.append(source, i, end)
                    i = end
                }
                c == '\'' && i + 2 < source.length && (source[i + 1] == '\\' || source[i + 2] == '\'') -> {
                    // A char literal: '(' or ')' must not count as a bracket.
                    val end = if (source[i + 1] == '\\') source.indexOf('\'', i + 3).let { if (it < 0) source.length else it + 1 } else i + 3
                    code.append(" ".repeat(end - i))
                    i = end
                }
                else -> {
                    code.append(c)
                    i++
                }
            }
        }
        return code.toString() to strings
    }
}
