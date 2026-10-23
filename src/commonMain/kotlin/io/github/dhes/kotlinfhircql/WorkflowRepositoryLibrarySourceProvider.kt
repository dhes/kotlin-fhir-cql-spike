package io.github.dhes.kotlinfhircql

import dev.ohs.fhir.model.r4.Library
import dev.ohs.fhir.model.r4.terminologies.RelatedArtifactType
import dev.ohs.fhir.workflow.WorkflowRepository
import kotlin.io.encoding.Base64
import kotlinx.io.Source
import org.cqframework.cql.cql2elm.LibraryContentType
import org.cqframework.cql.cql2elm.LibrarySourceProvider
import org.cqframework.cql.cql2elm.utils.asSource
import org.hl7.elm.r1.VersionedIdentifier

/**
 * A [LibrarySourceProvider] serving the CQL, ELM JSON and ELM XML attachments of FHIR Library resources read from a
 * [WorkflowRepository]. [load] reads them first, because cqframework asks for library content synchronously while
 * the repository is read with `suspend` calls.
 */
class WorkflowRepositoryLibrarySourceProvider(private val repository: WorkflowRepository) : LibrarySourceProvider {
    private val libraries = mutableMapOf<String, MutableList<Library>>()
    private val loaded = mutableMapOf<String, List<VersionedIdentifier>>()

    /** The ids of the libraries whose content was asked for, by content type, in order. */
    val requests: MutableList<Pair<String, LibraryContentType>> = mutableListOf()

    /**
     * Reads the Library with canonical [url] and, through its `depends-on` related artifacts, every Library it
     * depends on, directly or not; returns their identifiers (the Library's `name` and `version`).
     */
    suspend fun load(url: String): List<VersionedIdentifier> {
        loaded[url]?.let { return it }
        val identifiers = mutableListOf<VersionedIdentifier>()
        val seen = mutableSetOf<String>()
        suspend fun visit(canonical: String) {
            val libraryUrl = canonical.substringBefore("|")
            if (!seen.add(libraryUrl)) return
            val library =
                repository.searchByUri("Library", "url", libraryUrl).singleOrNull() as? Library
                    ?: error("No single Library with url $libraryUrl")
            val name = library.name?.value ?: error("Library $libraryUrl has no name")
            val versions = libraries.getOrPut(name) { mutableListOf() }
            if (versions.none { it.version?.value == library.version?.value }) versions += library
            identifiers += VersionedIdentifier().withId(name).withVersion(library.version?.value)
            library.relatedArtifact
                .filter { it.type.value == RelatedArtifactType.Depends_On }
                .mapNotNull { it.resource?.value }
                .filter { "/Library/" in it }
                .forEach { visit(it) }
        }
        visit(url)
        return identifiers.also { loaded[url] = it }
    }

    override fun getLibrarySource(libraryIdentifier: VersionedIdentifier): Source? =
        getLibraryContent(libraryIdentifier, LibraryContentType.CQL)

    /** The attachment of the loaded Library named by [libraryIdentifier] whose content type is [type]'s. */
    override fun getLibraryContent(libraryIdentifier: VersionedIdentifier, type: LibraryContentType): Source? {
        val versions = libraries[libraryIdentifier.id] ?: return null
        val library =
            if (libraryIdentifier.version == null) versions.singleOrNull()
            else versions.firstOrNull { it.version?.value == null || it.version?.value == libraryIdentifier.version }
        val data = library?.content?.firstOrNull { it.contentType?.value == type.mimeType() }?.data?.value ?: return null
        requests += libraryIdentifier.id!! to type
        return Base64.decode(data).decodeToString().asSource()
    }
}
