package mihon.gradle.configurations

import mihon.gradle.extensions.mihonx
import org.gradle.api.Project
import tapmoc.configureJavaCompatibility

fun Project.configureKotlin() {
    configureJavaCompatibility(mihonx.versions.java.get().toInt())
}
