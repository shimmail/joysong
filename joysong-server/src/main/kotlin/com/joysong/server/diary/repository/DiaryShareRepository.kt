package com.joysong.server.diary.repository

import com.joysong.server.diary.entity.DiaryShareEntity
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface DiaryShareRepository : JpaRepository<DiaryShareEntity, String> {
    fun findByTokenHash(tokenHash: String): DiaryShareEntity?
    fun findByDiaryIdAndRevokedAtIsNull(diaryId: String): DiaryShareEntity?

    @Modifying
    @Query("UPDATE DiaryShareEntity s SET s.revokedAt = :now WHERE s.diaryId = :diaryId AND s.revokedAt IS NULL")
    fun revokeActiveByDiaryId(@Param("diaryId") diaryId: String, @Param("now") now: java.time.LocalDateTime): Int
}
