package io.ethers.abigen.plugin

import io.ethers.abigen.plugin.source.FoundrySourceProvider
import io.ethers.abigen.plugin.task.EthersAbigenTask
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.ProjectConfigurationException
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.testfixtures.ProjectBuilder
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.intellij.lang.annotations.Language
import java.io.File

class EthersAbigenPluginTest : FunSpec({
    test("plugin is successfully applied") {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("io.kriptal.ethers.abigen-plugin")

        (project.plugins.getPlugin(EthersAbigenPlugin::class.java) is EthersAbigenPlugin) shouldBe true
    }

    test("plugin applies defaults to tasks") {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        project.plugins.apply("io.kriptal.ethers.abigen-plugin")

        val ext = project.extensions.getByType(EthersAbigenExtension::class.java)

        project.tasks.withType(EthersAbigenTask::class.java).forEach {
            it.sourceProviders.get() shouldBe ext.sourceProviders.get()
            it.outputDir.get() shouldBe ext.outputDir.get()
        }
    }

    test("fail to apply plugin if no kotlin plugin is applied to project") {
        val project = ProjectBuilder.builder().build()
        project.plugins.apply("io.kriptal.ethers.abigen-plugin")

        shouldThrow<ProjectConfigurationException> {
            (project as ProjectInternal).evaluate()
        }
    }

    test("foundrySourceProvider initializes contractGlobFilters as ListProperty") {
        val project = ProjectBuilder.builder().build()
        val provider = project.objects.newInstance(FoundrySourceProvider::class.java)
        provider.contractGlobFilters.get() shouldBe emptyList()
        provider.contractGlobFilters.set(listOf("erc/ERC20.sol", "erc/ERC721.sol"))
        provider.contractGlobFilters.get() shouldBe listOf("erc/ERC20.sol", "erc/ERC721.sol")
        provider.contractGlobFilters.add("erc/ERC1155.sol")
        provider.contractGlobFilters.get() shouldBe listOf("erc/ERC20.sol", "erc/ERC721.sol", "erc/ERC1155.sol")
    }

    context("foundry source execution") {
        val project = ProjectBuilder.builder().build()
        val foundryRoot = project.layout.projectDirectory.dir("src/main/solidity").asFile
        val foundryBin = project.layout.projectDirectory.dir("fake-foundry-bin").asFile
        val localBuildCacheDir = project.layout.projectDirectory.dir("local-build-cache").asFile

        foundryRoot.mkdirs()
        foundryBin.mkdirs()
        File(foundryRoot, "foundry.toml").writeText(
            """
                [profile.default]
                src = "src"
                out = "out"
            """.trimIndent(),
        )
        File(foundryRoot, "src/Counter.sol").apply {
            parentFile.mkdirs()
            writeText(
                """
                    // SPDX-License-Identifier: MIT
                    pragma solidity ^0.8.24;
                    contract Counter {}
                """.trimIndent(),
            )
        }

        File(foundryBin, "forge").apply {
            writeText(
                """
                    #!/bin/sh
                    if [ "$FOUNDRY_PROFILE" != "ci" ]; then
                      echo "wrong profile: $FOUNDRY_PROFILE" >&2
                      exit 2
                    fi
                    echo "$FOUNDRY_PROFILE" > "$PWD/forge-profile.txt"
                    mkdir -p "$PWD/out/Counter.sol"
                    cat > "$PWD/out/Counter.sol/Counter.json" <<'JSON'
                    {
                      "abi": [{"type":"function","name":"count","stateMutability":"view","inputs":[],"outputs":[{"type":"uint256"}]}],
                      "metadata": {
                        "settings": {
                          "compilationTarget": {
                            "src/Counter.sol": "Counter"
                          }
                        }
                      }
                    }
                    JSON
                """.trimIndent(),
            )
            setExecutable(true)
        }

        @Language("gradle")
        val settingsFile = """
            rootProject.name = 'ethers-abigen-plugin-foundry-test'
            
            buildCache {
                local {
                    directory '${localBuildCacheDir.toURI()}'
                }
            }
        """.trimIndent()

        @Language("gradle")
        val buildFile = """
            plugins {
                id 'base'
                id 'org.jetbrains.kotlin.jvm'
                id 'io.kriptal.ethers.abigen-plugin'
            }
            
            ethersAbigen {
                sourceProviders.set([])
                foundrySource('io.ethers.contracts') {
                    foundryRoot = 'src/main/solidity'
                    foundryProfile = 'ci'
                    contractGlobFilters.add('Counter.sol')
                }
            }
        """.trimIndent()

        project.layout.projectDirectory.file("settings.gradle").asFile.writeText(settingsFile)
        project.layout.projectDirectory.file("build.gradle").asFile.writeText(buildFile)

        val runner = GradleRunner.create()
            .withProjectDir(project.layout.projectDirectory.asFile)
            .withPluginClasspath()
            .withGradleVersion("9.5")
            .withEnvironment(
                mapOf(
                    "PATH" to "${foundryBin.absolutePath}:${System.getenv("PATH") ?: ""}",
                ),
            )
            .withDebug(true)
            .forwardOutput()

        test("task runs with foundry source provider on Gradle 9.5 and reuses configuration cache") {
            val firstRun = runner.withArguments("ethersAbigen", "--configuration-cache", "--build-cache", "--info").build()
            firstRun.tasks.filter { it.path.endsWith("ethersAbigen") }.forEach { it.outcome shouldBe TaskOutcome.SUCCESS }

            val profileFile = File(foundryRoot, "forge-profile.txt")
            profileFile.readText().trim() shouldBe "ci"

            val generatedFiles = project.layout.buildDirectory.dir("generated/source/ethers/main/kotlin").get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()
            generatedFiles.isNotEmpty() shouldBe true

            val secondRun = runner.withArguments("ethersAbigen", "--configuration-cache", "--build-cache", "--info").build()
            secondRun.output shouldContain "Reusing configuration cache."
        }
    }

    context("task execution") {
        val project = ProjectBuilder.builder().build()

        val taskName = "ethersAbigen"
        val customAbiPath = "src/main/abi-custom-folder"
        val customOutputDir = "generated/source/ethers-custom/main/kotlin"
        val abiDir = project.layout.projectDirectory.dir(customAbiPath).asFile
        val localBuildCacheDir = project.layout.projectDirectory.dir("local-build-cache").asFile

        // Copy the ABI files from test resources to a custom directory in gradle test project
        File(EthersAbigenPlugin::class.java.getResource("/abi")!!.toURI()).copyRecursively(abiDir, true)

        @Language("gradle")
        val settingsFile = """
            rootProject.name = 'ethers-abigen-plugin-test'
            
            // custom cache dir so it's a fresh location each test run
            buildCache {
                local {
                    directory '${localBuildCacheDir.toURI()}'
                }
            }
        """.trimIndent()

        @Language("gradle")
        val buildFile = """
            plugins {
                id 'base'
                id 'org.jetbrains.kotlin.jvm'
                id 'io.kriptal.ethers.abigen-plugin'
            }
            
            ethersAbigen {
                directorySource('$customAbiPath')
                outputDir = '$customOutputDir'
            }
        """.trimIndent()

        project.layout.projectDirectory.file("settings.gradle").asFile.writeText(settingsFile)
        project.layout.projectDirectory.file("build.gradle").asFile.writeText(buildFile)

        val runner = GradleRunner.create()
            .withProjectDir(project.layout.projectDirectory.asFile)
            .withPluginClasspath()
            .withDebug(true)
            .forwardOutput()

        test("task generates contract wrappers") {
            val result = runner.withArguments(taskName, "--build-cache", "--info").build()
            result.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.SUCCESS }

            val generatedFiles = project.layout.buildDirectory.dir(customOutputDir).get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()

            generatedFiles.size shouldBe 3
        }

        test("task results are loaded from cache on second run with same inputs") {
            val result = runner.withArguments("clean", taskName, "--build-cache", "--info").build()
            result.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.FROM_CACHE }
        }

        test("task results are cached even if project is moved to a different location (relocatability)") {
            val newProject = ProjectBuilder.builder().build()

            runner.projectDir.copyRecursively(newProject.layout.projectDirectory.asFile, true)
            val newRunner = GradleRunner.create()
                .withProjectDir(project.layout.projectDirectory.asFile)
                .withPluginClasspath()
                .withDebug(true)
                .forwardOutput()

            newProject.layout.projectDirectory.dir("local-build-cache").asFile.deleteRecursively()

            val result = newRunner.withArguments("clean", taskName, "--build-cache", "--info").build()
            result.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.FROM_CACHE }
        }

        test("changing task inputs invalidates cache") {
            val newOutputDir = "generated/source/ethers-updated/main/kotlin"

            @Language("gradle")
            val newBuildFile = """
                plugins {
                    id 'base'
                    id 'org.jetbrains.kotlin.jvm'
                    id 'io.kriptal.ethers.abigen-plugin'
                }
                
                ethersAbigen {
                    directorySource('$customAbiPath')
                    outputDir = '$newOutputDir'
                }
            """.trimIndent()

            project.layout.projectDirectory.file("build.gradle").asFile.writeText(newBuildFile)

            val result = runner.withArguments("clean", taskName, "--build-cache", "--info").build()
            result.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.SUCCESS }

            val previousGeneratedFiles = project.layout.buildDirectory.dir(customOutputDir).get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()

            val generatedFiles = project.layout.buildDirectory.dir(newOutputDir).get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()

            previousGeneratedFiles.size shouldBe 0
            generatedFiles.size shouldBe 3
        }

        test("changing task inputs removes previous outputs and generates new ones") {
            @Language("gradle")
            val newBuildFile = """
                plugins {
                    id 'base'
                    id 'org.jetbrains.kotlin.jvm'
                    id 'io.kriptal.ethers.abigen-plugin'
                }
                
                ethersAbigen {
                    directorySource('$customAbiPath')
                    outputDir = '$customOutputDir'
                }
            """.trimIndent()

            project.layout.projectDirectory.file("build.gradle").asFile.writeText(newBuildFile)

            // old task results are loaded from cache
            val oldResult = runner.withArguments(taskName, "--build-cache", "--info").build()
            oldResult.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.FROM_CACHE }

            val oldGeneratedFiles = project.layout.buildDirectory.dir(customOutputDir).get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()

            oldGeneratedFiles.size shouldBe 3
            oldGeneratedFiles.filter { it.path.contains("io/ethers/contracts") }.size shouldBe 2

            // delete previous ABI files, and copy new ones, keeping the wrappers in build folder
            abiDir.deleteRecursively()
            File(EthersAbigenPlugin::class.java.getResource("/abi-alt-package")!!.toURI()).copyRecursively(abiDir, true)

            // new task results are not from cache, and the old output files are deleted because inputs changed
            val result = runner.withArguments(taskName, "--build-cache", "--info").build()
            result.tasks.filter { it.path.endsWith(taskName) }.forEach { it.outcome shouldBe TaskOutcome.SUCCESS }

            val generatedFiles = project.layout.buildDirectory.dir(customOutputDir).get().asFile
                .walkTopDown()
                .filter(File::isFile)
                .toList()

            generatedFiles.size shouldBe 3
            generatedFiles.filter { it.path.contains("io/ethers/moved") }.size shouldBe 2
        }
    }
})
