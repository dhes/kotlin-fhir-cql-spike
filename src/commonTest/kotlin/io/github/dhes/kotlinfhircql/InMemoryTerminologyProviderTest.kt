package io.github.dhes.kotlinfhircql

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.opencds.cqf.cql.engine.runtime.Code
import org.opencds.cqf.cql.engine.terminology.CodeSystemInfo

/** Runs on every target: what `lookup` returns, which CQL sees only as found or not found. */
class InMemoryTerminologyProviderTest {
    private val colors = "http://example.org/fhir/CodeSystem/colors"
    private val provider = InMemoryTerminologyProvider(TerminologyFixtures.valueSets, TerminologyFixtures.codeSystems)

    private fun lookup(code: String, system: String = colors, version: String? = null, codeSystemVersion: String? = "1.0.0") =
        provider.lookup(Code().withCode(code).withSystem(system).withVersion(version), CodeSystemInfo().withId(system).withVersion(codeSystemVersion))

    @Test
    fun lookupFillsTheDisplayFromACompleteCodeSystem() {
        assertEquals("$colors|red|1.0.0 'Red'", lookup("red")?.let(TerminologyFixtures::render))
        assertEquals("$colors|light-green|1.0.0 'Light green'", lookup("light-green", codeSystemVersion = null)?.let(TerminologyFixtures::render))
        assertNull(lookup("purple"))
    }

    @Test
    fun lookupWithoutACompleteCodeSystemChecksSystemAndVersionOnly() {
        val notHeld = "http://example.org/fhir/CodeSystem/not-held"
        assertEquals("$notHeld|anything", lookup("anything", system = notHeld, codeSystemVersion = null)?.let(TerminologyFixtures::render))
        val fragment = "http://example.org/fhir/CodeSystem/fragment"
        assertEquals("$fragment|unlisted", lookup("unlisted", system = fragment, codeSystemVersion = null)?.let(TerminologyFixtures::render))
        assertNull(lookup("red", version = "2.0.0"))
        assertNull(provider.lookup(Code().withCode("red"), CodeSystemInfo().withId(colors)))
    }
}
