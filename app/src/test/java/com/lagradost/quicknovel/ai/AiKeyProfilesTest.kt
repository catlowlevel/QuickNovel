package com.lagradost.quicknovel.ai

import org.junit.Assert.*
import org.junit.Test

class AiKeyProfilesTest {
    @Test
    fun editingAnEmptyProviderAndAddingKeysRetainsExistingCredentials() {
        val empty = AiSettings()
        assertEquals(empty.profile().id, empty.profile().id)
        val first = empty.withConfig(AiProviderConfig("first", "first-model"))
        val second = first.addProfile().withConfig(AiProviderConfig("second", "second-model"))
        assertEquals(2, second.keyProfiles().size)
        assertNotEquals(first.profile().id, second.profile().id)
        assertEquals(first.config(), second.selectProfile(first.profile().id).config())
        assertEquals(AiProviderConfig("second", "second-model"), second.config())
    }

    @Test
    fun addingTheFirstKeyDoesNotKeepAPhantomEmptyKey() {
        assertEquals(1, AiSettings().addProfile().keyProfiles().size)
    }

    @Test
    fun eachProviderRetainsItsOwnKeySelectionAndModels() {
        var settings = AiSettings()
        val selected = mutableMapOf<AiProvider, String>()
        for (provider in AiProvider.entries) {
            settings = settings.copy(provider = provider).withConfig(AiProviderConfig("${provider.name}-first", "first-model"))
                .addProfile().withConfig(AiProviderConfig("${provider.name}-second", "second-model"))
            selected[provider] = settings.profile().id
        }
        for (provider in AiProvider.entries) {
            assertEquals(selected[provider], settings.profile(provider).id)
            assertEquals(AiProviderConfig("${provider.name}-second", "second-model"), settings.config(provider))
        }
    }

    @Test
    fun deletingInactiveKeysDoesNotSwitchTheActiveKeyOrAffectOtherProviders() {
        val first = AiSettings().withConfig(AiProviderConfig("first", "one"))
        val second = first.addProfile().withConfig(AiProviderConfig("second", "two"))
        val other = second.copy(provider = AiProvider.OPENAI).withConfig(AiProviderConfig("openai", "openai-model"))
        val deleted = other.copy(provider = AiProvider.GEMINI).removeProfile(first.profile().id)
        assertEquals(second.profile().id, deleted.profile().id)
        assertEquals(second.config(), deleted.config())
        assertEquals(other.config(AiProvider.OPENAI), deleted.config(AiProvider.OPENAI))
    }

    @Test
    fun deletingTheLastKeyLeavesAnEmptyEditorInsteadOfReusingDeletedCredentials() {
        val previous = AiSettings().withConfig(AiProviderConfig("remove-me", "remove-model"))
        val removed = previous.removeProfile(previous.profile().id)
        assertEquals(AiProviderConfig(), removed.config())
        assertNotEquals(previous.profile().id, removed.profile().id)
        assertEquals(1, removed.keyProfiles().size)
    }

    @Test
    fun renamingAndNormalizingNeverMergeIdenticallyNamedKeys() {
        val first = AiSettings().withConfig(AiProviderConfig(" first ", " models/first-model "))
        val second = first.withProfile(first.profile().copy(name = " Same ")).addProfile()
            .withConfig(AiProviderConfig(" second ", " second-model "))
        val named = second.withProfile(second.profile().copy(name = " Same ")).normalized()
        assertEquals(listOf("Same", "Same"), named.keyProfiles().map { it.name })
        assertEquals(listOf("first", "second"), named.keyProfiles().map { it.config.apiKey })
        assertEquals(listOf("first-model", "second-model"), named.keyProfiles().map { it.config.model })
        assertEquals(second.profile().id, named.profile().id)
    }

    @Test
    fun unknownSelectionsAndDeletionsCannotRemoveOtherKeys() {
        val settings = AiSettings().withConfig(AiProviderConfig("keep", "model"))
        assertEquals(settings, settings.selectProfile("missing"))
        assertEquals(settings, settings.removeProfile("missing"))
    }
}
