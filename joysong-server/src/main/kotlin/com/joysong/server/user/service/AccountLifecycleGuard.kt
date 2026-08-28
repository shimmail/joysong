package com.joysong.server.user.service

import com.joysong.server.user.entity.AccountState
import com.joysong.server.user.entity.UserEntity
import com.joysong.server.user.repository.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

@Service
class AccountLifecycleGuard(
    private val userRepository: UserRepository,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun requireActiveForWrite(userId: String): UserEntity {
        val user = userRepository.findByIdForUpdate(userId)
            ?: throw IllegalArgumentException("用户不存在")
        check(user.accountState == AccountState.ACTIVE) { "账号不可用" }
        return user
    }
}
