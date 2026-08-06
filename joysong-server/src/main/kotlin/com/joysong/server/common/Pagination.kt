package com.joysong.server.common

import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity

data class ApiPage<T>(
    val items: List<T>,
    val total: Int,
    val offset: Int,
    val limit: Int,
    val hasMore: Boolean
)

object ApiPaginationHeaders {
    const val TOTAL_COUNT = "X-Total-Count"
    const val OFFSET = "X-Offset"
    const val LIMIT = "X-Limit"
    const val HAS_MORE = "X-Has-More"
}

fun <T> List<T>.apiPage(offset: Int, limit: Int): ApiPage<T> {
    require(offset >= 0) { "INVALID_OFFSET: offset must be >= 0 / offset 不能小于 0" }
    require(limit in 1..100) { "INVALID_LIMIT: limit must be between 1 and 100 / limit 必须在 1-100 之间" }
    val items = if (offset >= size) {
        emptyList()
    } else {
        val endExclusive = minOf(size.toLong(), offset.toLong() + limit.toLong()).toInt()
        subList(offset, endExclusive)
    }
    return ApiPage(
        items = items,
        total = size,
        offset = offset,
        limit = limit,
        hasMore = offset.toLong() + items.size.toLong() < size.toLong()
    )
}

/**
 * Keeps the legacy list body consumed by current mobile clients and publishes
 * pagination metadata in response headers for newer clients.
 */
fun <T> ApiPage<T>.toCompatibilityResponse(): ResponseEntity<BaseResponse<List<T>>> {
    val headers = HttpHeaders().apply {
        set(ApiPaginationHeaders.TOTAL_COUNT, total.toString())
        set(ApiPaginationHeaders.OFFSET, offset.toString())
        set(ApiPaginationHeaders.LIMIT, limit.toString())
        set(ApiPaginationHeaders.HAS_MORE, hasMore.toString())
    }
    return ResponseEntity.ok().headers(headers).body(BaseResponse.success(items))
}

fun <T> List<T>.apiSlice(offset: Int, limit: Int): List<T> {
    return apiPage(offset, limit).items
}
