import java.util.ServiceLoader

fun main() {
    println("Testing ServiceLoader for CompilerPluginRegistrar...")
    val loader = ServiceLoader.load(
        Class.forName("org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar"),
        Thread.currentThread().contextClassLoader
    )
    println("Providers found:")
    loader.forEach {
        println("  - ${it::class.qualifiedName}")
    }
    
    println("\nTesting ServiceLoader for CommandLineProcessor...")
    val loader2 = ServiceLoader.load(
        Class.forName("org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor"),
        Thread.currentThread().contextClassLoader
    )
    println("Providers found:")
    loader2.forEach {
        println("  - ${it::class.qualifiedName}")
    }
}
