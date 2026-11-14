package app.stickersearch.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Kotlin initialises properties and init blocks in the order they're written. A stored property
 * declared below MainViewModel's `init {}` is still null while init runs, and using it there crashes
 * the app on launch (1.1.4-1.2.0 did exactly that). Keep every stored property above init.
 */
class ViewModelInitOrderTest {
    @Test fun noStoredPropertyIsDeclaredAfterInit() {
        val src = listOf("src/main/java", "app/src/main/java")
            .map { File(it, "app/stickersearch/ui/MainViewModel.kt") }.first { it.exists() }.readLines()
        val classStart = src.indexOfFirst { it.startsWith("class MainViewModel(") }
        val initLine = src.withIndex().first { (i, l) -> i > classStart && l.trim() == "init {" }.index
        // Class-level (4-space indented) stored properties: `val x = …` / `var x = …`, not getters or lazies.
        val stored = Regex("""^ {4}(?:@Volatile\s+)?(?:private |internal |protected )?(?:val|var) \w+(?::[^=]+)?\s*=(?!=)""")
        val late = src.withIndex()
            .filter { (i, l) -> i > initLine && stored.containsMatchIn(l) && !l.contains("by lazy") }
            .map { (i, l) -> "line ${i + 1}: ${l.trim()}" }
        assertTrue("Stored properties declared after init (move them above it, or make them getters):\n" + late.joinToString("\n"), late.isEmpty())
    }
}
