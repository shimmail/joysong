package com.joysong.server.agent.service

import org.springframework.context.i18n.LocaleContextHolder

object AgentText {
    fun isChinese(): Boolean = LocaleContextHolder.getLocale().language.equals("zh", ignoreCase = true)

    fun value(zh: String, en: String): String =
        if (isChinese()) zh else en

    fun field(code: String): String = when (code) {
        "goals" -> value("改善目标", "improvement goals")
        "city" -> value("所在城市", "city")
        "budget" -> value("预算上限", "budget limit")
        "downtime" -> value("可接受恢复期", "acceptable downtime")
        "pain" -> value("疼痛接受度", "pain tolerance")
        else -> code
    }
}
