package io.jafar.gradle

import io.jafar.utils.TypeGenerator
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.file.DirectoryProperty

import java.util.function.Predicate

class TypeGeneratorPlugin implements Plugin<Project> {
	@Override
	void apply(Project project) {
		def extension = project.extensions.create('generateJafarTypes', GenerateJafarTypesExtension, project)

		project.afterEvaluate {
			if (project.hasProperty('jafar.input')) {
				extension.inputFile.set(project.file(project.property('jafar.input')))
			}
		}

		// Define the directory for generated sources
		def generatedSourcesDir = project.file("${project.buildDir}/generated/sources/jafar/src/main")

		// Register a task for generating sources
		project.tasks.register('generateJafarTypes') {
			group = "build"
			description = "Generate Jafar type sources into the 'generated/sources' folder"

			// Define inputs and outputs for task up-to-date checks
			inputs.file(extension.inputFile).optional(true)
			outputs.dir(extension.outputDir.orElse(project.layout.buildDirectory.dir("generated/sources/jafar/src/main")))

			doLast {
				if (!extension.inputFile.isPresent()) {
					println "No input file provided. Using runtime provided JFR type definitions."
				} else {
					println "Running TypeGenerator with input file ${extension.inputFile.get()} to generate sources into ${generatedSourcesDir}"
				}

				def input = extension.inputFile.isPresent() ? extension.inputFile.get() : null
				def output = extension.outputDir.orElse(project.layout.buildDirectory.dir("generated/sources/jafar/src/main")).get().asFile
				def overwrite = extension.overwrite.getOrElse(false)
				def targetPackage = extension.targetPackage.getOrElse("io.jafar.parser.api.types")


				// Ensure output directory exists
				output.mkdirs()

				Predicate<String> predicate = null
				if (extension.eventTypeFilter.isPresent()) {
					def filterClosure = extension.eventTypeFilter.get()
					predicate = filterClosure as Predicate<String> // Convert Closure to Predicate
				}

				// Instantiate and execute TypeGenerator
				def generator = new TypeGenerator(input?.toPath(), output.toPath(), targetPackage, overwrite, predicate)
				generator.generate()
			}
		}

		// Add generated sources to the main source set
		project.afterEvaluate {
			project.sourceSets.main.java.srcDir(generatedSourcesDir)
			project.tasks.named('compileJava').configure {
				dependsOn 'generateJafarTypes'
			}
		}
	}

	// Extension to configure the input file
	static class GenerateJafarTypesExtension {
		final Property<File> inputFile
		final DirectoryProperty outputDir
		final Property<String> targetPackage
		final Property<Boolean> overwrite
		final Property<Closure<Boolean>> eventTypeFilter

		GenerateJafarTypesExtension(Project project) {
			inputFile = project.objects.property(File)
			outputDir = project.objects.directoryProperty()
			targetPackage = project.objects.property(String)
			overwrite = project.objects.property(Boolean)
			eventTypeFilter = project.objects.property(Closure)
		}

		void eventTypeFilter(Closure<Boolean> eventTypeFilterClosure) {
			this.eventTypeFilter.set(eventTypeFilterClosure)
		}
	}
}
