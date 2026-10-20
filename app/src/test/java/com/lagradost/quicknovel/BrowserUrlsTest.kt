package com.lagradost.quicknovel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BrowserUrlsTest {
    @Test
    fun webPagesKeepTheirPathQueryAndFragment() {
        assertEquals(
            "https://example.com/novel/chapter?language=en#text",
            browserUrlOrNull("  https://example.com/novel/chapter?language=en#text  "),
        )
        assertEquals("http://localhost:8080/page", browserUrlOrNull("http://localhost:8080/page"))
    }

    @Test
    fun scriptsFilesAppIntentsAndRelativeLinksCannotBeOpenedAsPages() {
        for (value in
            listOf(
                "javascript:alert(1)",
                "file:///data/local/private",
                "content://books/1",
                "intent://example/#Intent;scheme=https;end",
                "tel:12345",
                "data:text/html,test",
                "/chapter",
                "//example.com",
            )) {
            assertNull(value, browserUrlOrNull(value))
        }
    }

    @Test
    fun invalidOrCredentialBearingUrlsAreRejected() {
        for (value in
            listOf(null, "", "   ", "https://", "https://user:secret@example.com/", "https://example.com/a\nb")) {
            assertNull(value, browserUrlOrNull(value))
        }
    }
}
