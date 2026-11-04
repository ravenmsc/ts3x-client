plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
dependencies {
    api("org.bouncycastle:bcprov-jdk15on:1.67")
    api("commons-lang:commons-lang:2.6")
    api("dnsjava:dnsjava:2.1.8")
    api("org.ini4j:ini4j:0.5.1")
    testImplementation("junit:junit:4.13.2")
}
