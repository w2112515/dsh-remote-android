package dev.dshremote.gate0c.transport

import dev.dshremote.protocol.v1alpha.ModelCatalogFailure as ProtoModelCatalogFailure
import dev.dshremote.protocol.v1alpha.ModelChanged as ProtoModelChanged
import dev.dshremote.protocol.v1alpha.ModelEntry as ProtoModelEntry
import dev.dshremote.protocol.v1alpha.ModelProviderGroup as ProtoModelProviderGroup
import dev.dshremote.protocol.v1alpha.ModelSelection as ProtoModelSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * S-session-admin model catalog/selection mapping: names fall back to stable
 * ids, optional halves stay absent, and catalog bounds mirror the Host carrier.
 */
class ModelMappingTest {
    @Test
    fun mapsCatalogGroupsWithNameFallbackAndPerFieldAbsence() {
        val groups = modelProviderGroupProjections(
            listOf(
                ProtoModelProviderGroup.newBuilder()
                    .setId("deepseek")
                    .setName("DeepSeek")
                    .addModels(
                        ProtoModelEntry.newBuilder()
                            .setId("deepseek-chat")
                            .setName("V4 Flash")
                            .addReasoningEfforts("low")
                            .addReasoningEfforts("high")
                            .setDefaultReasoningEffort("high"),
                    )
                    .addModels(ProtoModelEntry.newBuilder().setId("deepseek-reasoner"))
                    .build(),
            ),
        )

        val group = groups.single()
        assertEquals("deepseek", group.id)
        assertEquals("DeepSeek", group.displayName)
        val full = group.models[0]
        assertEquals("V4 Flash", full.displayName)
        assertEquals(listOf("low", "high"), full.reasoningEfforts)
        assertEquals("high", full.defaultReasoningEffort)
        val bare = group.models[1]
        // The published name falls back to the stable id — never a second identity.
        assertEquals("deepseek-reasoner", bare.displayName)
        assertNull(bare.name)
        assertNull(bare.defaultReasoningEffort)
        assertEquals(emptyList<String>(), bare.reasoningEfforts)
    }

    @Test
    fun boundsCatalogToTheCarrierLimits() {
        val oversized = ProtoModelProviderGroup.newBuilder()
            .setId("p")
            .apply {
                repeat(MAX_MODELS_PER_PROVIDER + 5) { index ->
                    addModels(ProtoModelEntry.newBuilder().setId("m-$index"))
                }
            }
            .build()
        val groups = modelProviderGroupProjections(
            List(MAX_MODEL_PROVIDER_GROUPS + 3) { index ->
                if (index == 0) oversized else ProtoModelProviderGroup.newBuilder().setId("g-$index").build()
            },
        )

        assertEquals(MAX_MODEL_PROVIDER_GROUPS, groups.size)
        assertEquals(MAX_MODELS_PER_PROVIDER, groups[0].models.size)
    }

    @Test
    fun mapsInputModalitiesWithUnknownNeverMeaningTextOnly() {
        // S-blob: an empty modality list means the adapter declared nothing —
        // acceptsImages stays null (unknown), never a text-only claim.
        val groups = modelProviderGroupProjections(
            listOf(
                ProtoModelProviderGroup.newBuilder()
                    .setId("deepseek")
                    .addModels(
                        ProtoModelEntry.newBuilder()
                            .setId("vision")
                            .addInputModalities("text")
                            .addInputModalities("image"),
                    )
                    .addModels(
                        ProtoModelEntry.newBuilder()
                            .setId("text-only-declared")
                            .addInputModalities("text"),
                    )
                    .addModels(ProtoModelEntry.newBuilder().setId("undeclared"))
                    .build(),
            ),
        )

        val models = groups.single().models
        assertEquals(listOf("text", "image"), models[0].inputModalities)
        assertEquals(true, models[0].acceptsImages)
        assertEquals(false, models[1].acceptsImages)
        assertEquals(emptyList<String>(), models[2].inputModalities)
        assertNull(models[2].acceptsImages)
    }

    @Test
    fun mapsCatalogFailuresAsExplicitRows() {
        val failures = modelCatalogFailureProjections(
            listOf(
                ProtoModelCatalogFailure.newBuilder()
                    .setProviderId("anthropic")
                    .setDetail("adapter not configured")
                    .build(),
                ProtoModelCatalogFailure.newBuilder().setProviderId("catalog").build(),
            ),
        )

        assertEquals(
            listOf(
                ModelCatalogFailureProjection("anthropic", "adapter not configured"),
                ModelCatalogFailureProjection("catalog"),
            ),
            failures,
        )
        assertNull(failures[1].detail)
    }

    @Test
    fun mapsSelectionsAndChangesWithOptionalEffortAbsence() {
        val selection = modelSelectionOf(
            ProtoModelSelection.newBuilder()
                .setProvider("deepseek")
                .setModel("deepseek-chat")
                .setReasoningEffort("high")
                .build(),
        )
        assertEquals(ModelSelectionProjection("deepseek", "deepseek-chat", "high"), selection)

        val change = modelSelectionOf(
            ProtoModelChanged.newBuilder()
                .setProvider("deepseek")
                .setModel("deepseek-reasoner")
                .build(),
        )
        assertEquals(ModelSelectionProjection("deepseek", "deepseek-reasoner"), change)
        assertNull(change.reasoningEffort)
    }

    @Test
    fun displayLabelResolvesCatalogNamesAndNeverInventsThem() {
        val catalog = listOf(
            ModelProviderGroupProjection(
                id = "deepseek",
                models = listOf(ModelEntryProjection(id = "deepseek-chat", name = "V4 Flash")),
            ),
        )

        // Known row: published name + effort after a middle dot (prototype chip shape).
        assertEquals(
            "V4 Flash · high",
            modelDisplayLabel(catalog, ModelSelectionProjection("deepseek", "deepseek-chat", "high")),
        )
        // A stale/unknown row falls back to the raw model id — never a fabricated name.
        assertEquals(
            "mystery-model",
            modelDisplayLabel(catalog, ModelSelectionProjection("other", "mystery-model")),
        )
        // No effort half: no trailing separator is invented.
        assertEquals(
            "V4 Flash",
            modelDisplayLabel(catalog, ModelSelectionProjection("deepseek", "deepseek-chat")),
        )
    }
}
