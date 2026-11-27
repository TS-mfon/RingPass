plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ringpass.testfixture"
    compileSdk = 35

    defaultConfig {
        // Match the source package expected by the real listener on the isolated CI emulator.
        applicationId = "com.whatsapp"
        minSdk = 31
        targetSdk = 35
    }
}
