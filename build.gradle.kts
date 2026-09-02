plugins {
    id("com.android.application") version "9.3.2" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy {
            failOnDynamicVersions()
            failOnChangingVersions()
        }
    }
}
