import org.gradle.api.plugins.JavaPluginExtension
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions

// Opt-in only: ordinary test/integrationTest never starts the executable host.
gradle.projectsEvaluated {
    val project = rootProject
    project.tasks.register("exportServiceE2eClasspath") {
        dependsOn(project.tasks.named("testClasses"))
        doLast {
            val output = Path.of(project.providers.gradleProperty("serviceE2eClasspathOutput").get()).normalize()
            require(output.isAbsolute && !Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            require(output.parent.toRealPath() == output.parent)
            val classpath = project.extensions.getByType(JavaPluginExtension::class.java)
                .sourceSets.getByName("test").runtimeClasspath.asPath
            Files.newByteChannel(output, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(classpath.toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
            }
        }
    }
}
