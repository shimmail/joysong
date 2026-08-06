package com.joysong.server.common

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PaginationTest {

    @Test
    fun `compatibility response keeps list body and adds complete metadata`() {
        val response = listOf("a", "b", "c", "d").apiPage(offset = 1, limit = 2)
            .toCompatibilityResponse()

        assertEquals(listOf("b", "c"), response.body?.data)
        assertEquals("4", response.headers.getFirst(ApiPaginationHeaders.TOTAL_COUNT))
        assertEquals("1", response.headers.getFirst(ApiPaginationHeaders.OFFSET))
        assertEquals("2", response.headers.getFirst(ApiPaginationHeaders.LIMIT))
        assertEquals("true", response.headers.getFirst(ApiPaginationHeaders.HAS_MORE))
    }

    @Test
    fun `page past end is empty and has no next page`() {
        val page = listOf(1, 2).apiPage(offset = 3, limit = 10)

        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
        assertEquals(2, page.total)
    }

    @Test
    fun `invalid pagination exposes stable bilingual error code`() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            listOf(1).apiPage(offset = -1, limit = 10)
        }

        assertTrue(error.message.orEmpty().contains("INVALID_OFFSET"))
        assertTrue(error.message.orEmpty().contains("offset 不能小于 0"))
    }
}
