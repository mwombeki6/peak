package com.mwombeki.peak

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DatabaseOwnershipArchitectureTests {

    @Test
    fun `every production SQL mutation targets a table owned by its module`() {
        val ownership = loadOwnership()
        val violations = kotlinSources().flatMap { source ->
            val module = source.toString()
                .substringAfter("com/mwombeki/peak/")
                .substringBefore('/')

            MUTATION.findAll(source.readText()).mapNotNull { match ->
                val table = match.groupValues[1].substringAfterLast('.').lowercase()
                val owner = ownership[table]
                when {
                    owner == null -> "$module mutates unregistered table $table (${source.fileName})"
                    owner == "deferred" -> "$module mutates dormant out-of-scope table $table (${source.fileName})"
                    owner != module -> "$module mutates $table owned by $owner (${source.fileName})"
                    else -> null
                }
            }.toList()
        }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                separator = "\n",
                prefix = "Database ownership violations:\n",
            ),
        )
    }

    @Test
    fun `ownership inventory is unique and covers every migrated table`() {
        val rows = ownershipRows()
        val duplicateTables = rows.groupingBy { it.table }.eachCount()
            .filterValues { it != 1 }
        assertTrue(duplicateTables.isEmpty(), "Duplicate table owners: $duplicateTables")

        val ownership = rows.associate { it.table to it.owner }
        val migratedTables = migrationSources().flatMap { migration ->
            CREATE_TABLE.findAll(migration.readText()).map { match ->
                match.groupValues[1].substringAfterLast('.').lowercase()
            }.toList()
        }.toSet()

        assertEquals(
            emptySet(),
            migratedTables - ownership.keys,
            "Every table introduced by V1-current must have a canonical owner",
        )
        assertEquals(
            emptySet(),
            ownership.keys - migratedTables,
            "Ownership inventory contains tables that are not in the migration chain",
        )
    }

    private fun loadOwnership(): Map<String, String> {
        return ownershipRows().associate { it.table to it.owner }
    }

    private fun ownershipRows(): List<OwnershipRow> {
        return Files.readAllLines(OWNERSHIP_FILE)
            .drop(1)
            .filter { it.isNotBlank() }
            .map { line ->
                val columns = line.split(',')
                require(columns.size == 4) { "Invalid ownership row: $line" }
                OwnershipRow(columns[0], columns[1])
            }
    }

    private fun kotlinSources(): List<Path> {
        return sourceFiles(Path.of("src/main/kotlin"), "kt")
    }

    private fun migrationSources(): List<Path> {
        return sourceFiles(Path.of("src/main/resources/db/migration"), "sql")
    }

    private fun sourceFiles(root: Path, extension: String): List<Path> {
        return Files.walk(root).use { paths ->
            paths
                .filter { it.isRegularFile() && it.extension == extension }
                .sorted()
                .toList()
        }
    }

    private data class OwnershipRow(val table: String, val owner: String)

    private companion object {
        val OWNERSHIP_FILE: Path = Path.of("docs/architecture/database-ownership.csv")
        val MUTATION = Regex(
            """\b(?:INSERT\s+INTO|(?<!FOR\s)(?<!DO\s)UPDATE|DELETE\s+FROM|MERGE\s+INTO|TRUNCATE(?:\s+TABLE)?)\s+([a-zA-Z_][a-zA-Z0-9_.]*)""",
            RegexOption.IGNORE_CASE,
        )
        val CREATE_TABLE = Regex(
            """\bCREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+([a-zA-Z_][a-zA-Z0-9_.]*)""",
            RegexOption.IGNORE_CASE,
        )
    }
}
