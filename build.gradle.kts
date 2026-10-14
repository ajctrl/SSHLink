plugins {
    id("com.android.application") version "9.4.1" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
