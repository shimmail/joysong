pluginManagement {
    repositories {
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "joysong-server"
