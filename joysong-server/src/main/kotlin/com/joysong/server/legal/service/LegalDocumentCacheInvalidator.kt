package com.joysong.server.legal.service

import com.joysong.server.legal.entity.LegalDocumentLocale
import com.joysong.server.legal.entity.LegalDocumentType
import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Component
class LegalDocumentCacheInvalidator(
    private val cacheManager: CacheManager
) {
    private val generations = ConcurrentHashMap<LegalDocumentType, AtomicLong>()

    fun cacheKey(type: LegalDocumentType, locale: LegalDocumentLocale): String =
        cacheKey(type, locale, generation(type).get())

    fun evictAfterCommit(type: LegalDocumentType) {
        val evict = {
            val previousGeneration = generation(type).getAndIncrement()
            val cache = cacheManager.getCache(CACHE_NAME)
            LegalDocumentLocale.entries.forEach { locale ->
                cache?.evict(cacheKey(type, locale, previousGeneration))
            }
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

    private fun generation(type: LegalDocumentType): AtomicLong =
        generations.computeIfAbsent(type) { AtomicLong() }

    private fun cacheKey(type: LegalDocumentType, locale: LegalDocumentLocale, generation: Long) =
        "${type.name}:$generation:${locale.tag}"

    private companion object {
        const val CACHE_NAME = "legalDocuments"
    }
}
