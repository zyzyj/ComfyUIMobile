package com.local.comfyuimobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class UrlQueryTest {
    @Test fun appendsWithQuestionMarkWhenNoQuery() {
        assertEquals(
            "https://host/view?preview=webp;90",
            UrlQuery.append("https://host/view", "preview", "webp;90"),
        )
    }

    @Test fun appendsWithAmpersandWhenQueryExists() {
        assertEquals(
            "https://host/view?filename=a.png&preview=webp;90",
            UrlQuery.append("https://host/view?filename=a.png", "preview", "webp;90"),
        )
    }

    @Test fun keepsExistingParametersIntact() {
        assertEquals(
            "https://host/view?filename=a.png&subfolder=s&type=output&preview=webp;90",
            UrlQuery.append("https://host/view?filename=a.png&subfolder=s&type=output", "preview", "webp;90"),
        )
    }

    @Test fun returnsUrlUnchangedWhenBlank() {
        assertEquals("", UrlQuery.append("", "preview", "webp;90"))
    }
}
