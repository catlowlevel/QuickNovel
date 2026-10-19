package com.lagradost.quicknovel.ai

import org.junit.Assert.*
import org.junit.Test

class AiApiUrlsTest {
    @Test
    fun baseUrlsPreserveVersionAndGatewayPathsWithOrWithoutATrailingSlash() {
        assertEquals("https://api.deepseek.com/", aiApiBaseUrlOrNull(" https://api.deepseek.com ").toString())
        for (url in listOf("https://gateway.example/team/openai/v1", "https://gateway.example/team/openai/v1/")) {
            assertEquals("https://gateway.example/team/openai/v1/", aiApiBaseUrlOrNull(url).toString())
        }
        assertEquals("https://gateway.example/gemini/v1beta/", aiApiBaseUrlOrNull("https://gateway.example/gemini/v1beta").toString())
    }

    @Test
    fun selfHostedHttpPortsAndIpv6AddressesAreSupported() {
        assertEquals("http://192.168.1.2:11434/v1/", aiApiBaseUrlOrNull("http://192.168.1.2:11434/v1").toString())
        assertEquals("http://[::1]:8080/api/", aiApiBaseUrlOrNull("http://[::1]:8080/api").toString())
    }

    @Test
    fun nonWebUrlsCredentialsQueriesFragmentsAndControlCharactersAreRejected() {
        for (url in listOf("", "api.deepseek.com", "/v1", "file:///data/private", "javascript:alert(1)",
            "https://user:secret@example.com/v1", "https://example.com/v1?api_key=secret", "https://example.com/#fragment",
            "https://example.com/a b", "https://example.com/a\nb")) {
            assertNull(url, aiApiBaseUrlOrNull(url))
        }
    }

    @Test
    fun differentProfilesKeepTheirOwnEndpointsAndResettingOneDoesNotAffectTheOthers() {
        val first = AiSettings().withConfig(AiProviderConfig("first", "model-a", " https://api.deepseek.com "))
        val second = first.addProfile().withConfig(AiProviderConfig("second", "model-b", "http://localhost:8080/v1"))
        val normalized = second.normalized()
        assertEquals(listOf("https://api.deepseek.com/", "http://localhost:8080/v1/"), normalized.keyProfiles().map { it.config.baseUrl })
        val reset = normalized.withConfig(normalized.config().copy(baseUrl = ""))
        assertEquals("", reset.config().baseUrl)
        assertEquals("https://api.deepseek.com/", reset.selectProfile(first.profile().id).config().baseUrl)
        assertEquals("second", reset.config().apiKey)
        assertEquals("model-b", reset.config().model)
    }

    @Test
    fun diagnosticStringsDoNotEchoSecretsFromAnUnvalidatedUrl() {
        val config = AiProviderConfig("secret-key", "model", "https://user:url-secret@example.com/?token=query-secret")
        for (secret in listOf("secret-key", "url-secret", "query-secret")) assertFalse(config.toString().contains(secret))
    }
}
