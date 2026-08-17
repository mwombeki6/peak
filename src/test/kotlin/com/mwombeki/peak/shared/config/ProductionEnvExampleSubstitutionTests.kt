package com.mwombeki.peak.shared.config

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every placeholder in `.env.example` must be substituted by every workflow that copies it.
 *
 * `ops/production/.env.example` is a template full of `change-me` values, and
 * `validate-production-env.sh` refuses all of them — that refusal is the whole point, because a
 * placeholder credential reaching production is a credential an attacker already knows. CI
 * therefore copies the template and `sed`s each placeholder to a synthetic value before asking
 * the validator whether the contract holds.
 *
 * That leaves three lists which must agree and which nothing forced to agree: the template, the
 * validator's required set, and each workflow's substitution list. Adding a secret means editing
 * all three, and the middle one is the only one the author is thinking about.
 *
 * It has already failed twice. `KEYCLOAK_RECONCILER_SECRET` was fixed reactively in e2c59fa
 * ("Add the reconciler secret to the other two environment builders"), and then
 * `PEAK_COMMUNICATION_PROVIDERS_BEEM_API_KEY` and `..._SECRET_KEY` arrived in 64aabd3 with the
 * same omission and broke both the runtime-contracts job and the resilience soak. Neither break
 * was visible for weeks, because CI itself was blocked on an unrelated billing failure and the
 * job never ran to reach the step.
 *
 * So this is a file comparison rather than a workflow run: the workflow is the only thing that
 * can catch it today, it needs a runner, and it fails late. This runs in milliseconds and names
 * the variable.
 *
 * The placeholder patterns are copied from `reject_placeholder()` in the validator rather than
 * generalised. A superset would demand substitutions the validator does not require and fail for
 * no reason; matching it exactly means the two agree by construction, which is the property that
 * was missing in the first place.
 */
class ProductionEnvExampleSubstitutionTests {

    @Test
    fun everyPlaceholderIsSubstitutedByEveryWorkflowThatCopiesTheTemplate() {
        val placeholders = placeholders()
        val builders = builders()

        assertTrue(
            placeholders.isNotEmpty(),
            "no variable in $TEMPLATE holds a placeholder, so this test is asserting nothing — " +
                "either the template changed shape or the placeholder patterns no longer match " +
                "reject_placeholder() in validate-production-env.sh",
        )
        assertEquals(
            EXPECTED_BUILDERS,
            builders.size,
            "expected $EXPECTED_BUILDERS blocks that copy $TEMPLATE but found ${builders.size}: " +
                "${builders.map { it.name }}. A workflow was added, removed, or restructured; " +
                "update this count deliberately rather than letting the guard cover fewer " +
                "blocks than exist",
        )

        builders.forEach { builder ->
            assertTrue(
                builder.substituted.isNotEmpty(),
                "parsed no substitutions out of '${builder.name}', so this block is not being " +
                    "checked at all. The sed lines were reformatted and SUBSTITUTION no longer " +
                    "matches them",
            )
            val unsubstituted = placeholders - builder.substituted
            assertTrue(
                unsubstituted.isEmpty(),
                "'${builder.name}' copies $TEMPLATE but leaves these placeholders in place: " +
                    "$unsubstituted. validate-production-env.sh rejects each of them, so the " +
                    "job fails on a value that was never meant to be real",
            )
        }
    }

    /**
     * Variables whose template value the validator would reject.
     *
     * The name pattern admits digits deliberately: `PEAK_ENVELOPE_KEY_BASE64` is a placeholder
     * and an `[A-Z_]+` pattern silently skips it, which is a guard that reports success while
     * ignoring a variable.
     */
    private fun placeholders(): Set<String> =
        Files.readAllLines(TEMPLATE)
            .mapNotNull { ASSIGNMENT.matchEntire(it.trim()) }
            .filter { PLACEHOLDER.containsMatchIn(it.groupValues[2]) }
            .map { it.groupValues[1] }
            .toSet()

    /**
     * Each workflow step that copies the template, paired with what it substitutes.
     *
     * A block runs from the `cp` to the start of the next step, which is where its `sed` lives.
     */
    private fun builders(): List<EnvBuilder> =
        WORKFLOWS.flatMap { workflow ->
            val source = Files.readString(workflow)
            COPY_TEMPLATE.findAll(source).map { copy ->
                val rest = source.substring(copy.range.last)
                val block = NEXT_STEP.find(rest)?.let { rest.substring(0, it.range.first) } ?: rest
                EnvBuilder(
                    name = "${workflow.fileName}: ${stepNameBefore(source, copy.range.first)}",
                    substituted = SUBSTITUTION.findAll(block)
                        .map { it.groupValues[1] }
                        .toSet(),
                )
            }
        }

    /** The nearest `- name:` above the copy, so a failure names the step a reader can find. */
    private fun stepNameBefore(source: String, index: Int): String =
        STEP_NAME.findAll(source.substring(0, index)).lastOrNull()?.groupValues?.get(1)?.trim()
            ?: "unnamed step"

    private data class EnvBuilder(val name: String, val substituted: Set<String>)

    private companion object {
        val TEMPLATE: Path = Path.of("ops/production/.env.example")
        val WORKFLOWS = listOf(
            Path.of(".github/workflows/ci.yml"),
            Path.of(".github/workflows/resilience-soak.yml"),
        )

        /**
         * Two in `ci.yml` — the environment contract check and the acceptance stack — and one in
         * `resilience-soak.yml`. Asserted rather than derived so that a workflow losing its
         * substitutions fails here instead of quietly reducing what this test covers.
         */
        const val EXPECTED_BUILDERS = 3

        val ASSIGNMENT = Regex("""([A-Z0-9_]+)=(.*)""")

        /** The four forms `reject_placeholder()` matches, and no others. */
        val PLACEHOLDER = Regex("""CHANGE_ME|change-me|changeme|CHANGE-ME""")

        val COPY_TEMPLATE = Regex("""cp ops/production/\.env\.example""")
        val SUBSTITUTION = Regex("""s\|\^([A-Z0-9_]+)=""")
        val NEXT_STEP = Regex("""\n\s*- name:""")
        val STEP_NAME = Regex("""- name:(.*)""")
    }
}
