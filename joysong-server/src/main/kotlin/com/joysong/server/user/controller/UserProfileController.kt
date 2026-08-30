package com.joysong.server.user.controller

import com.joysong.server.common.BaseResponse
import com.joysong.server.diary.repository.DiaryRepository
import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.dto.UserProfileResponse
import com.joysong.server.user.repository.UserRepository
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/users")
class UserProfileController(
    private val userRepository: UserRepository,
    private val diaryRepository: DiaryRepository
) {

    /**
     * 公开用户主页 - 无需认证
     * 返回用户基本信息 + 统计数据
     */
    @GetMapping("/{id}/profile")
    fun getUserProfile(@PathVariable id: String): BaseResponse<*> {
        val user = findPublicUser(id)
            ?: return BaseResponse.error<Any>("用户不存在", 404)

        // 查询用户已发布的日记数量
        val diaryCount = diaryRepository.findByUserId(id).count { it.status == "published" }

        // user_follows 表目前不存在，关注/粉丝数返回 0
        val profile = UserProfileResponse(
            id = user.id,
            nickname = user.nickname,
            avatar = user.avatar.ifBlank { null },
            gender = user.gender.ifBlank { null },
            bio = user.bio.ifBlank { null },
            city = user.city.ifBlank { null },
            birthday = user.birthday,
            diaryCount = diaryCount,
            followingCount = 0,
            followerCount = 0
        )

        return BaseResponse.success(profile)
    }

    /**
     * 获取用户公开发布的日记列表 - 无需认证
     */
    @GetMapping("/{id}/diaries")
    fun getUserDiaries(@PathVariable id: String): BaseResponse<*> {
        if (findPublicUser(id) == null) {
            return BaseResponse.error<Any>("用户不存在", 404)
        }
        val diaries = diaryRepository.findPublishedByUserId(id)
        return BaseResponse.success(diaries)
    }

    private fun findPublicUser(id: String): UserEntity? =
        userRepository.findById(id).orElse(null)
            ?.takeIf { it.accountState == AccountState.ACTIVE && it.deletedAt == null }
}
