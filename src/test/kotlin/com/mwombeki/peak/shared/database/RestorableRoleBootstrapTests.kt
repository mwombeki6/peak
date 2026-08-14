package com.mwombeki.peak.shared.database

import java.nio.file.Files
import java.nio.file.Path
import kotlin.streams.asSequence
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every role a migration creates must also exist before a restore.
 *
 * Roles are cluster-level; a database dump is not. `pg_dump` of one database emits the objects
 * that role owns and no `CREATE ROLE` for the role itself, so restoring into a fresh cluster
 * fails on an unknown role and takes the whole recovery with it. `ops/production/role-bootstrap.sql`
 * is what runs first and closes that gap.
 *
 * The rule was already documented in a comment inside that file, and I still missed it three
 * times in one branch — `pms_billing_restriction_owner` (V91),
 * `pms_platform_billing_scope_owner` (V93) and `pms_platform_billing_sweep_owner` (V94) all own
 * SECURITY DEFINER functions and none of them was bootstrapped. Nothing caught it until the
 * acceptance drill tried an actual restore, twenty migrations later, and failed with
 * `role "pms_platform_billing_sweep_owner" does not exist`.
 *
 * That is the whole argument for this test being a file comparison rather than a drill: the
 * drill is the only thing that *can* catch it today, it takes twelve minutes, and it runs after
 * everything else has passed. This runs in milliseconds and names the missing role.
 *
 * Stated with no exemptions on purpose. Two roles from `V1` own nothing and are referenced by
 * nothing, so they could have been excluded — but then the rule becomes "roles that own
 * something", which requires knowing what a dump will reference, which is the judgement that
 * failed in the first place. Bootstrapping an unused NOLOGIN role costs nothing.
 */
class RestorableRoleBootstrapTests {

    @Test
    fun everyRoleAMigrationCreatesExistsBeforeARestore() {
        val created = rolesIn(migrationSources())
        val bootstrapped = rolesIn(listOf(Files.readString(BOOTSTRAP)))

        assertTrue(
            created.isNotEmpty(),
            "no migration creates a role, so this test is asserting nothing",
        )

        val unrestorable = created - bootstrapped
        assertTrue(
            unrestorable.isEmpty(),
            "these roles are created by a migration but not by role-bootstrap.sql. A dump " +
                "references them without containing them, so a restore into a fresh cluster " +
                "fails on an unknown role and recovery stops there: $unrestorable",
        )
    }

    /**
     * The inverse. A role bootstrapped into production that no migration creates is either a
     * typo or something whose purpose has been forgotten, and either way production should not
     * be carrying it.
     *
     * Login roles are excluded because they are exactly the ones that exist only in production —
     * migrations grant membership to them but must never create them, since creating a LOGIN
     * role means choosing a password.
     */
    @Test
    fun everyBootstrappedNoLoginRoleIsOneAMigrationActuallyCreates() {
        val bootstrap = Files.readString(BOOTSTRAP)
        val bootstrapped = rolesIn(listOf(bootstrap))
            .filterNot { role -> LOGIN_ROLE.containsMatchIn(bootstrap.roleDeclaration(role)) }
            .toSet()

        val orphaned = bootstrapped - rolesIn(migrationSources())
        assertTrue(
            orphaned.isEmpty(),
            "these no-login roles are created in production but by no migration, so nothing " +
                "in the schema depends on them: $orphaned",
        )
    }

    /** The narrowest slice of the file that declares this role, for reading its options. */
    private fun String.roleDeclaration(role: String): String =
        Regex("""CREATE ROLE $role\b[^;]*;""").find(this)?.value.orEmpty()

    private fun rolesIn(sources: List<String>): Set<String> =
        sources.flatMap { CREATE_ROLE.findAll(it).map { match -> match.groupValues[1] } }.toSet()

    private fun migrationSources(): List<String> =
        Files.walk(Path.of("src/main/resources/db/migration")).use { paths ->
            paths.asSequence()
                .filter { it.toString().endsWith(".sql") }
                .map { Files.readString(it) }
                .toList()
        }

    private companion object {
        val BOOTSTRAP: Path = Path.of("ops/production/role-bootstrap.sql")
        val CREATE_ROLE = Regex("""CREATE ROLE ([a-z_]+)""")
        val LOGIN_ROLE = Regex("""\bLOGIN\b""")
    }
}
