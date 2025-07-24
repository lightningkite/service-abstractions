import java.io.File

data class Module(
    val name: String,
    val multiplatform: Boolean = false,
    val parent: Module? = null,
)

val data = """
            database*
                processor
                test*
                mongodb
                jsonfile
                postgres
            email*
                test*
                mailgun
            sms*
                test*
                twilio
            metrics*
                test*
                cloudwatch
            notifications*
                test*
                fcm
            cache*
                test*
                dynamodb
                memcached
                redis
            pubsub*
                test*
                redis
            files*
                test*
                clamav
                azbs
                s3
            exceptions*
                test*
                sentry
            demo
            """.trimIndent()

var lastParent: Module? = null
val modules = data.lines().filter { it.isNotBlank() }.map {
    val text = it.trim()
    val name = text.filter { it.isLetterOrDigit() }
    val multiplatform = text.contains('*')
    if (it.startsWith(" ")) {
        Module(lastParent!!.name + "-" + name, multiplatform, lastParent)
    } else {
        lastParent = Module(name, multiplatform, null)
        lastParent!!
    }
}
println(modules.joinToString("\n"))
println("Working at ${File(".").absolutePath}")
for (module in modules) {
    val moduleRoot = File(module.name)
    if(moduleRoot.exists()) {
        continue
//        moduleRoot.deleteRecursively()
    }
    moduleRoot.mkdirs()
    val mavenPublishing = """
        mavenPublishing {
            // publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)
            signAllPublications()
            coordinates(group.toString(), name, version.toString())
            pom {
                name.set("Service Abstractions - ${'$'}name")
                description.set(description)
                github("lightningkite", "service-abstractions")
                licenses {
                    mit()
                }
                developers {
                    joseph()
                    brady()
                }
            }
        }
    """.trim()
    val buildText = if(module.multiplatform) {
        """
        import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
        

        plugins {
            alias(libs.plugins.kotlinMultiplatform)
            alias(libs.plugins.ksp)
            alias(libs.plugins.serialization)
            alias(libs.plugins.androidLibrary)
            // alias(libs.plugins.dokka)
            id("signing")
            alias(libs.plugins.vanniktechMavenPublish)
        }

        kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
            explicitApi()
            applyDefaultHierarchyTemplate()
            androidTarget {
                compilations.all {
                    kotlinOptions.jvmTarget = "1.8"
                }
            }

            jvm {
                compilations.all {
                    kotlinOptions.jvmTarget = "1.8"
                }
            }
            js(IR) {
                browser()
            }
            iosX64()
            iosArm64()
            iosSimulatorArm64()
            macosX64()
            macosArm64()

            sourceSets {
                val commonMain by getting {
                    dependencies {
                        api(project(path = ":basis"))
                        ${if(module.parent != null) "api(project(path = \":${module.parent.name}\"))" else ""}
                    }
                    kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
                        srcDir(file("build/generated/ksp/common/commonMain/kotlin"))
                    }
                }
                val commonTest by getting {
                    dependencies {
                        implementation(libs.kotlinTest)
                        implementation(libs.coroutinesTesting)
                    }
                    kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
                        srcDir(file("build/generated/ksp/common/commonTest/kotlin"))
                    }
                }
                val jvmMain by getting {
                    dependencies {
                    }
                }
                val jvmTest by getting {
                    dependsOn(commonTest)
                }
            }
        }

        $mavenPublishing

        android {
            namespace = "com.lightningkite.serviceabstractions"
            compileSdk = 34

            defaultConfig {
                minSdk = 21
            }
            compileOptions {
                isCoreLibraryDesugaringEnabled = true
                sourceCompatibility = JavaVersion.VERSION_1_8
                targetCompatibility = JavaVersion.VERSION_1_8
            }
            dependencies {
                coreLibraryDesugaring(libs.androidDesugaring)
            }
        }
        """.trimIndent()
    } else {
        """
        
        import com.lightningkite.deployhelpers.github
        import com.lightningkite.deployhelpers.mit
        import com.lightningkite.deployhelpers.*
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
        

        plugins {
            alias(libs.plugins.kotlinJvm)
            alias(libs.plugins.ksp)
            // alias(libs.plugins.dokka)
            alias(libs.plugins.serialization)
            id("signing")
            alias(libs.plugins.vanniktechMavenPublish)
        }

        dependencies {
            api(project(path = ":basis"))
            ${if(module.parent != null) "api(project(path = \":${module.parent.name}\"))" else ""}
            implementation(libs.kotlinTest)
            testImplementation(libs.coroutinesTesting)
        }

        kotlin {
    compilerOptions {
        optIn.add("kotlin.time.ExperimentalTime")
    }
            explicitApi()
            sourceSets.main {
                kotlin.srcDir("build/generated/ksp/main/kotlin")
            }
            sourceSets.test {
                kotlin.srcDir("build/generated/ksp/test/kotlin")
            }
        }

        tasks.withType<JavaCompile>().configureEach {
            this.targetCompatibility = "17"
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
            kotlinOptions.freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
            kotlinOptions.freeCompilerArgs += "-opt-in=kotlin.RequiresOptIn"
            kotlinOptions.jvmTarget = JavaVersion.VERSION_17.toString()
        }

        $mavenPublishing

        """.trimIndent()
    }
    moduleRoot.resolve("build.gradle.kts").writeText(buildText)
    if (module.multiplatform) {
        moduleRoot.resolve("src/commonMain/kotlin/com/lightningkite/serviceabstractions/${module.name.replace('-', '/')}").mkdirs()
        moduleRoot.resolve("src/commonTest/kotlin/com/lightningkite/serviceabstractions/${module.name.replace('-', '/')}").mkdirs()
    } else {
        moduleRoot.resolve("src/main/kotlin/com/lightningkite/serviceabstractions/${module.name.replace('-', '/')}").mkdirs()
        moduleRoot.resolve("src/test/kotlin/com/lightningkite/serviceabstractions/${module.name.replace('-', '/')}").mkdirs()
    }
}

println(modules.joinToString("\n") { "include(\":${it.name}\")" })