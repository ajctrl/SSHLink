plugins {
    id("com.android.application") version "9.5.1" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
