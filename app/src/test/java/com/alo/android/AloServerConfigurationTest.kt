package com.alo.android

import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AloServerConfigurationTest {
    @Test
    fun generatedEndpointsMatchTheActiveBuildEnvironment() {
        val appLinkHost = URL(AloServerConfiguration.appLinkBaseUrl).host

        if (BuildConfig.DEBUG && BuildConfig.ALO_BASE_URL.isBlank()) {
            // No debug server configured: the development Mac's LAN address.
            assertTrue(AloServerConfiguration.apiBaseUrl.startsWith("http://"))
            assertEquals(
                AloServerConfiguration.debugLocalDevelopmentHost,
                URL(AloServerConfiguration.apiBaseUrl).host
            )
        } else if (BuildConfig.DEBUG) {
            // A debug build pointed at a server such as the test VPS (gradle.properties: aloDebugBaseUrl).
            assertEquals(BuildConfig.ALO_BASE_URL.trimEnd('/') + "/", AloServerConfiguration.apiBaseUrl)
        } else {
            assertTrue(AloServerConfiguration.apiBaseUrl.startsWith("https://"))
        }
        assertTrue(AloServerConfiguration.appLinkBaseUrl.startsWith("https://"))
        assertEquals(appLinkHost, AloServerConfiguration.appLinkHost)
        assertEquals(
            "${AloServerConfiguration.appLinkBaseUrl}p/42",
            AloServerConfiguration.appLink("p", "42")
        )
    }

    @Test
    fun relativeApiPathsStayBoundToTheConfiguredApiOrigin() {
        val expectedOrigin = URL(AloServerConfiguration.apiBaseUrl)
        val resolved = URL(AloServerConfiguration.apiUrl("api/health"))

        assertEquals(expectedOrigin.protocol, resolved.protocol)
        assertEquals(expectedOrigin.host, resolved.host)
        assertEquals("/api/health", resolved.path)
    }
}
