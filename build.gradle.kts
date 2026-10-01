plugins {
    java
}

group = "cn.mod.autoop"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://libraries.minecraft.net/")
}

dependencies {
    // compileOnly = 只用来编译，不打进 jar（服务端自己提供 Paper API）
    compileOnly("io.papermc.paper:paper-api:26.2.build.129-stable")
}

java {
    // Paper 26.2 跑在 Java 25 上；release 21 的字节码在 Java 21 与 25 服务端都能加载。
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.jar {
    archiveBaseName.set("AutoOp")
    archiveClassifier.set("")
    // 普通 jar：只有插件自己的类 + plugin.yml / config.yml
}
