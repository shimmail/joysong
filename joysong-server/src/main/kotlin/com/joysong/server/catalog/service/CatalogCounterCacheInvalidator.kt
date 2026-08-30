package com.joysong.server.catalog.service

import org.springframework.cache.CacheManager
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

@Component
class CatalogCounterCacheInvalidator(
    private val cacheManager: CacheManager
) {
    fun evictDoctorCountersAfterCommit() = evictAfterCommit(DOCTOR_COUNTER_CACHES)

    fun evictCaseCountersAfterCommit() = evictAfterCommit(CASE_COUNTER_CACHES)

    private fun evictAfterCommit(cacheNames: Set<String>) {
        val evict = { cacheNames.forEach { cacheManager.getCache(it)?.clear() } }
        if (!TransactionSynchronizationManager.isSynchronizationActive() ||
            !TransactionSynchronizationManager.isActualTransactionActive()
        ) {
            evict()
            return
        }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = evict()
        })
    }

    private companion object {
        val DOCTOR_COUNTER_CACHES = setOf("doctors", "discover")
        val CASE_COUNTER_CACHES = setOf("institutions", "projects", "home", "discover")
    }
}
