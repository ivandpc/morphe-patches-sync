import com.android.build.api.dsl.ApplicationExtension

dependencies {
    compileOnly(libs.morphe.extensions.library)
    compileOnly(project(":extensions:shared-youtube:library"))
    compileOnly(project(":extensions:shared:library"))
    compileOnly(project(":extensions:youtube:stub"))
    compileOnly(libs.annotation)
    // Provided by host YT Music app at runtime; compileOnly to avoid bundling.
    compileOnly(libs.okhttp)
}

configure<ApplicationExtension> {
    defaultConfig {
        minSdk = 26
    }
}
