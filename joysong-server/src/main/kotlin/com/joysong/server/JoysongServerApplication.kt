package com.joysong.server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching

@SpringBootApplication
@EnableCaching
class JoysongServerApplication

fun main(args: Array<String>) {
    runApplication<JoysongServerApplication>(*args)
}
