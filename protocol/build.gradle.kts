plugins { `java-library` }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation(project(":ts3j"))
}

tasks.test {
    // Integration tests are opt-in; they never contact public servers.
    systemProperty("ts3x.test.port", System.getProperty("ts3x.test.port", ""))
    systemProperty("ts3x.test.generation", System.getProperty("ts3x.test.generation", ""))
    for (name in listOf("token", "servergroup", "channelgroup", "password")) {
        System.getProperty("ts3x.test.$name")?.let { systemProperty("ts3x.test.$name", it) }
    }
}
