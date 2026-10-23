package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Attachment
import dev.ohs.fhir.model.r4.Code as FhirCode
import dev.ohs.fhir.model.r4.Library
import dev.ohs.fhir.model.r4.Base64Binary
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource
import kotlinx.coroutines.test.runTest
import org.cqframework.cql.cql2elm.CqlCompilerOptions
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.elm.serializing.ElmJsonLibraryWriter
import org.hl7.elm.r1.VersionedIdentifier

/**
 * Step 12, on every target: WHO's test suite with each Library in the repository carrying ELM JSON, compiled on this
 * target with `EnableResultTypes`, besides its CQL. FHIRHelpers is a Library in the repository too. The libraries
 * are served by [WorkflowRepositoryLibrarySourceProvider]; cqframework compiles a library's CQL only when it can't
 * use the ELM.
 */
class ElmFromRepositoryTest {
    private val options = FhirCqlEvaluator.compilerOptions(CqlCompilerOptions.Options.EnableResultTypes)

    @Test
    fun whoSuiteFromElm() = runTest(timeout = 60.minutes) {
        val start = TimeSource.Monotonic.markNow()
        val compiler = FhirCqlEvaluator(WhoSuite.librarySources, MeaslesFixtures.modelInfoXml, ucumService = platformUcumService(), compilerOptions = options)
        val elm =
            WhoSuite.librarySources.map(FhirCqlEvaluator::libraryName).associateWith { name ->
                ElmJsonLibraryWriter().writeAsString(compiler.libraryManager.resolveLibrary(VersionedIdentifier().withId(name)).library!!)
            }
        println("Compiled ${elm.size} libraries to ELM JSON in ${start.elapsedNow()}")

        val libraries = WhoSuite.libraryResources.map { it.withElm(elm.getValue(it.name!!.value!!)) } + fhirHelpersLibrary(elm.getValue("FHIRHelpers"))
        val repository = InMemoryWorkflowRepository(libraries + WhoSuite.valueSets)
        val cql = WorkflowRepositoryCqlEvaluator(repository, MeaslesFixtures.modelInfoXml, emptyList(), platformUcumService(), options)
        cql.libraries.load(FHIR_HELPERS_URL)

        val differences = whoSuiteThroughRepository(repository, cql) { repository.loadPatientData(it) }
        val fromCql = cql.libraries.requests.filter { it.second == LibraryContentType.CQL }.map { it.first }.toSet()
        val fromElm = cql.libraries.requests.filter { it.second == LibraryContentType.JSON }.map { it.first }.toSet()
        println("Libraries the suite needs: ${fromElm.size}; loaded from ELM: ${fromElm.size - fromCql.size}; compiled from CQL: ${fromCql.sorted()}")
        assertTrue(differences.isEmpty(), "${differences.size} scenarios differ from the recorded HAPI outcomes")
        // cqframework 5.4.0 rejects the ELM of libraries with overloaded functions (cqframework#1873); the local build
        // with the fix proposed in #1874 (-PcqlVersion=5.5.0-elm-functions-fb8055df-SNAPSHOT) uses it.
        if (CQL_VERSION.startsWith("5.5.0-elm-functions")) assertEquals(emptySet(), fromCql) else assertEquals(setOf("FHIRHelpers", "IMMZCommon", "WHOCommon"), fromCql)
    }

    private fun Library.withElm(elm: String): Library =
        toBuilder().apply {
            content.add(
                Attachment.Builder().apply {
                    contentType = FhirCode.Builder().apply { value = LibraryContentType.JSON.mimeType() }
                    data = Base64Binary.Builder().apply { value = Base64.encode(elm.encodeToByteArray()) }
                }
            )
        }.build()

    private fun fhirHelpersLibrary(elm: String): Library {
        fun attachment(type: LibraryContentType, text: String) =
            """{"contentType":"${type.mimeType()}","data":"${Base64.encode(text.encodeToByteArray())}"}"""
        val json =
            """{"resourceType":"Library","id":"FHIRHelpers","url":"$FHIR_HELPERS_URL","name":"FHIRHelpers","version":"4.0.1",""" +
                """"status":"active","type":{"coding":[{"system":"http://terminology.hl7.org/CodeSystem/library-type","code":"logic-library"}]},""" +
                """"content":[${attachment(LibraryContentType.CQL, WhoSuite.fhirHelpersSource)},${attachment(LibraryContentType.JSON, elm)}]}"""
        return MeaslesFixtures.json.decodeFromString<dev.ohs.fhir.model.r4.Resource>(json) as Library
    }

    companion object {
        const val FHIR_HELPERS_URL = "http://fhir.org/guides/cqf/common/Library/FHIRHelpers"
    }
}
