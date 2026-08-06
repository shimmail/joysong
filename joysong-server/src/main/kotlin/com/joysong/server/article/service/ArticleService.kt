package com.joysong.server.article.service

import com.joysong.server.article.entity.ArticleEntity
import com.joysong.server.article.repository.ArticleRepository
import org.springframework.cache.annotation.CacheEvict
import org.springframework.cache.annotation.Cacheable
import org.springframework.cache.annotation.Caching
import org.springframework.stereotype.Service

@Service
class ArticleService(private val articleRepository: ArticleRepository) {

    @Cacheable(cacheNames = ["articles"], key = "'all'")
    fun findAll(): List<ArticleEntity> = articleRepository.findAll()

    @Cacheable(cacheNames = ["articles"], key = "#id")
    fun findById(id: String): ArticleEntity? = articleRepository.findById(id).orElse(null)

    @Caching(evict = [
        CacheEvict(cacheNames = ["articles"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun save(entity: ArticleEntity): ArticleEntity = articleRepository.save(entity)

    @Caching(evict = [
        CacheEvict(cacheNames = ["articles"], allEntries = true),
        CacheEvict(cacheNames = ["home"], allEntries = true)
    ])
    fun deleteById(id: String) = articleRepository.deleteById(id)

    fun count(): Long = articleRepository.count()

    fun findByTitleContainingOrAuthorNameContaining(title: String, authorName: String): List<ArticleEntity> =
        articleRepository.findByTitleContainingOrAuthorNameContaining(title, authorName)

    @Cacheable(cacheNames = ["articles"], key = "'search:' + #keyword")
    fun searchArticles(keyword: String): List<ArticleEntity> = articleRepository.searchArticles(keyword)
}
