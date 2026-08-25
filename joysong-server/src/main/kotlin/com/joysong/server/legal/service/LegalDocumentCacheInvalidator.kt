package com.joysong.server.legal.service

import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentType
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class LegalDocumentCacheInvalidator(
    private val cacheManager: CacheManager
) {
    fun evictAfterCommit(type: LegalDocumentType) {
        val evict = {
            val cache = cacheManager.getCache(CACHE_NAME)
            LegalDocumentLocale.entries.forEach { locale -> cache?.evict(cacheKey(type, locale)) }
        }
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            evict()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() {
                evict()
            }
        })
    }

    private fun cacheKey(type: LegalDocumentType, locale: LegalDocumentLocale) = "${type.name}:${locale.tag}"

    private companion object {
        const val CACHE_NAME = "legalDocuments"
    }
}
