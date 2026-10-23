package io.github.dhes.kotlinfhircql

import io.github.dhes.kotlinfhircql.InMemoryRetrieveProvider.ProfileMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.cqframework.cql.shared.BigDecimal
import org.opencds.cqf.cql.engine.runtime.ClassInstance
import org.opencds.cqf.cql.engine.runtime.DateTime
import org.opencds.cqf.cql.engine.runtime.Interval
import org.opencds.cqf.cql.engine.runtime.Value

/** Runs on every target: the parts of the retrieve that CQL compiled from source never reaches. */
class InMemoryRetrieveProviderTest {
    private val resources = RetrieveFixtures.kotlinFhirResources()

    /** The library's default "Measurement Period". */
    private val measurementPeriod =
        Interval(DateTime(BigDecimal(0), 2024, 1, 1, 0, 0, 0, 0), true, DateTime(BigDecimal(0), 2025, 1, 1, 0, 0, 0, 0), false)

    private fun retrieve(
        provider: InMemoryRetrieveProvider,
        dataType: String,
        templateId: String? = null,
        datePath: String? = null,
        dateLowPath: String? = null,
        dateHighPath: String? = null,
        dateRange: Interval? = null,
    ): List<String> =
        provider
            .retrieve("Patient", "subject", "p1", dataType, templateId, null, null, null, datePath, dateLowPath, dateHighPath, dateRange)
            .map { id(it) }

    private fun id(value: Value?): String = MeaslesFixtures.render(value as ClassInstance)!!.substringAfter('/')

    /** The compiler never fills in the low and high date paths, so they are checked against the period date path. */
    @Test
    fun lowAndHighDatePathsAgreeWithThePeriod() {
        val provider = InMemoryRetrieveProvider(resources)
        val byPeriod = retrieve(provider, "Encounter", datePath = "period", dateRange = measurementPeriod)
        assertEquals(listOf("e-inside", "e-inside-planned"), byPeriod)
        assertEquals(
            byPeriod,
            retrieve(provider, "Encounter", dateLowPath = "period.start", dateHighPath = "period.end", dateRange = measurementPeriod),
        )
    }

    @Test
    fun dateRangeWithoutDatePathFails() {
        assertFailsWith<IllegalStateException> { retrieve(InMemoryRetrieveProvider(resources), "Encounter", dateRange = measurementPeriod) }
    }

    @Test
    fun profileModes() {
        val heartRate = "http://hl7.org/fhir/StructureDefinition/heartrate"
        val all = listOf("o-datetime", "o-period", "o-instant", "o-datetime-outside", "o-other-code")
        fun observations(mode: ProfileMode, templateId: String?) =
            retrieve(InMemoryRetrieveProvider(resources, profileMode = mode), "Observation", templateId)

        assertEquals(all, observations(ProfileMode.OFF, heartRate))
        assertEquals(listOf("o-datetime", "o-period"), observations(ProfileMode.DECLARED, heartRate))
        assertEquals(listOf("o-datetime", "o-period", "o-instant", "o-datetime-outside"), observations(ProfileMode.OPTIONAL, heartRate))
        for (mode in ProfileMode.entries) {
            assertEquals(all, observations(mode, "http://hl7.org/fhir/StructureDefinition/Observation"), "base profile, $mode")
            assertEquals(all, observations(mode, "http://hl7.org/fhir/StructureDefinition/Observation|4.0.1"), "versioned base profile, $mode")
            assertEquals(all, observations(mode, null), "no profile, $mode")
        }
    }
}
