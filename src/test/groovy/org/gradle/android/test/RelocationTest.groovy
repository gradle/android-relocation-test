package org.gradle.android.test

import com.google.common.collect.ImmutableMap
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class RelocationTest extends Specification {

    static final String GRADLE_INSTALLATION_PROPERTY = "org.gradle.android.test.gradle-installation"
    static final String ANDROID_VERSION_PROPERTY = "org.gradle.android.test.android-version"
    static final String SCAN_URL_PROPERTY = "org.gradle.android.test.scan-url"
    static final String SMOKETEST_INITSCRIPT_PROPERTY = "org.gradle.smoketests.init.script"
    static final String PLUGIN_MIRROR_PROPERTY = "org.gradle.internal.plugins.portal.url.override"

    static final String DEFAULT_GRADLE_VERSION = "5.6.1"
    static final String DEFAULT_ANDROID_VERSION = "3.5.0"

    @Rule TemporaryFolder temporaryFolder
    File cacheDir
    String androidPluginVersion
    String scanUrl
    String smokeTestInitScript
    String pluginMirror

    def setup() {
        cacheDir = temporaryFolder.newFolder()

        androidPluginVersion = System.getProperty(ANDROID_VERSION_PROPERTY)
        if (!androidPluginVersion) {
            androidPluginVersion = DEFAULT_ANDROID_VERSION
        }

        scanUrl = System.getProperty(SCAN_URL_PROPERTY)

        pluginMirror = System.getProperty(PLUGIN_MIRROR_PROPERTY)
        smokeTestInitScript = System.getProperty(SMOKETEST_INITSCRIPT_PROPERTY)
    }

    def "santa-tracker can be built relocatably"() {
        def tasksToRun = ["assembleDebug"]

        println "> Using ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}"
        println "> Running on Java VM ${System.getProperty("java.vm.vendor")} ${System.getProperty("java.vm.name")} ${System.getProperty("java.vm.version")}"
        println "> Using Android plugin ${androidPluginVersion}"
        println "> Cache directory: $cacheDir (files: ${cacheDir.listFiles().length})"

        def originalDir = new File(System.getProperty("original.dir"))
        def relocatedDir = new File(System.getProperty("relocated.dir"))

        def expectedResults = expectedResults()

        def scanPluginConfiguration = scanUrl ? """
            plugins.matching({ it.class.name == "com.gradle.scan.plugin.BuildScanPlugin" }).all {
                buildScan {
                    server = "$scanUrl"
                }
            }
        """ : ""

        def initScript = temporaryFolder.newFile("init.gradle") << """
            rootProject { root ->
                buildscript {
                    repositories {
                        maven {
                            url "https://plugins.gradle.org/m2/"
                        }
                    }
                    dependencies {
                        classpath ('com.android.tools.build:gradle:${androidPluginVersion}') { force = true }
                    }
                }

                $scanPluginConfiguration
            }

            settingsEvaluated { settings ->
                settings.buildCache {
                    local(DirectoryBuildCache) {
                        directory = "${cacheDir.toURI()}"
                    }
                }
            }
        """

        def defaultArgs = [
            "--build-cache",
            "--scan",
            "--init-script", initScript.absolutePath,
            "--stacktrace",
        ]

        if (smokeTestInitScript) {
            defaultArgs += ['--init-script', smokeTestInitScript]
        }

        if (pluginMirror) {
            defaultArgs += ["-D${PLUGIN_MIRROR_PROPERTY}=${pluginMirror}".toString()]
        }

        cleanCheckout(originalDir, defaultArgs)
        cleanCheckout(relocatedDir, defaultArgs)

        when:
        createGradleRunner()
            .withProjectDir(originalDir)
            .withArguments(*tasksToRun, *defaultArgs)
            .build()
        then:
        noExceptionThrown()

        when:
        def relocatedResult = createGradleRunner()
            .withProjectDir(relocatedDir)
            .withArguments(*tasksToRun, *defaultArgs)
            .build()
        then:
        expectedResults.verify(relocatedResult)
    }

    private void cleanCheckout(File dir, List<String> defaultArgs) {
        def args = ["clean", *defaultArgs, "--no-build-cache", "--no-scan"]
        createGradleRunner()
            .withProjectDir(dir)
            .withArguments(args)
            .build()
        new File(dir, ".gradle").deleteDir()
    }

    static class ExpectedResults {
        private final Map<String, TaskOutcome> outcomes

        ExpectedResults(Map<String, TaskOutcome> outcomes) {
            this.outcomes = outcomes
        }

        boolean verify(BuildResult result) {
            println "> Expecting ${outcomes.values().count(FROM_CACHE)} tasks out of ${outcomes.size()} to be cached"

            def outcomesWithMatchingTasks = outcomes.findAll { result.task(it.key) }
            def hasMatchingTasks = outcomesWithMatchingTasks.size() == outcomes.size() && outcomesWithMatchingTasks.size() == result.tasks.size()
            if (!hasMatchingTasks) {
                println "> Tasks missing:    " + (outcomes.findAll { !outcomesWithMatchingTasks.keySet().contains(it.key) })
                println "> Tasks in surplus: " + (result.tasks.findAll { !outcomesWithMatchingTasks.keySet().contains(it.path) })
                println "> Updated definitions:"
                result.tasks
                    .toSorted { a, b -> a.path <=> b.path }
                    .forEach { task ->
                        println "builder.put('${task.path}', ${task.outcome})"
                    }
            }

            boolean allOutcomesMatched = true
            outcomesWithMatchingTasks.each { taskName, expectedOutcome ->
                def taskOutcome = result.task(taskName)?.outcome
                if (taskOutcome != expectedOutcome) {
                    println "> Task '$taskName' was $taskOutcome but should have been $expectedOutcome"
                    allOutcomesMatched = false
                }
            }
            return hasMatchingTasks && allOutcomesMatched
        }
    }

    def expectedResults() {
        def builder = ImmutableMap.<String, TaskOutcome>builder()
        builder.put(':cityquiz:assembleDebug', SUCCESS)
        builder.put(':cityquiz:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':cityquiz:checkDebugManifest', SUCCESS)
        builder.put(':cityquiz:compileDebugAidl', NO_SOURCE)
        builder.put(':cityquiz:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':cityquiz:compileDebugKotlin', FROM_CACHE)
        builder.put(':cityquiz:compileDebugRenderscript', NO_SOURCE)
        builder.put(':cityquiz:compileDebugShaders', FROM_CACHE)
        builder.put(':cityquiz:compileDebugSources', UP_TO_DATE)
        builder.put(':cityquiz:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':cityquiz:featureDebugWriter', SUCCESS)
        builder.put(':cityquiz:generateDebugAssets', UP_TO_DATE)
        builder.put(':cityquiz:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':cityquiz:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':cityquiz:generateDebugResValues', FROM_CACHE)
        builder.put(':cityquiz:generateDebugResources', UP_TO_DATE)
        builder.put(':cityquiz:javaPreCompileDebug', FROM_CACHE)
        builder.put(':cityquiz:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':cityquiz:mergeDebugAssets', FROM_CACHE)
        builder.put(':cityquiz:mergeDebugJavaResource', SUCCESS)
        builder.put(':cityquiz:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':cityquiz:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':cityquiz:mergeDebugResources', FROM_CACHE)
        builder.put(':cityquiz:mergeDebugShaders', FROM_CACHE)
        builder.put(':cityquiz:mergeExtDexDebug', FROM_CACHE)
        builder.put(':cityquiz:mergeLibDexDebug', FROM_CACHE)
        builder.put(':cityquiz:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':cityquiz:packageDebug', SUCCESS)
        builder.put(':cityquiz:preBuild', UP_TO_DATE)
        builder.put(':cityquiz:preDebugBuild', UP_TO_DATE)
        builder.put(':cityquiz:processDebugJavaRes', NO_SOURCE)
        builder.put(':cityquiz:processDebugManifest', FROM_CACHE)
        builder.put(':cityquiz:processDebugResources', FROM_CACHE)
        builder.put(':cityquiz:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':cityquiz:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':common:assembleDebug', SUCCESS)
        builder.put(':common:bundleDebugAar', SUCCESS)
        builder.put(':common:bundleLibCompileDebug', SUCCESS)
        builder.put(':common:bundleLibResDebug', SUCCESS)
        builder.put(':common:bundleLibRuntimeDebug', SUCCESS)
        builder.put(':common:checkDebugManifest', SUCCESS)
        builder.put(':common:compileDebugAidl', NO_SOURCE)
        builder.put(':common:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':common:compileDebugKotlin', FROM_CACHE)
        builder.put(':common:compileDebugRenderscript', NO_SOURCE)
        builder.put(':common:compileDebugShaders', FROM_CACHE)
        builder.put(':common:compileDebugSources', UP_TO_DATE)
        builder.put(':common:createFullJarDebug', FROM_CACHE)
        builder.put(':common:extractDebugAnnotations', FROM_CACHE)
        builder.put(':common:generateDebugAssets', UP_TO_DATE)
        builder.put(':common:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':common:generateDebugRFile', FROM_CACHE)
        builder.put(':common:generateDebugResValues', FROM_CACHE)
        builder.put(':common:generateDebugResources', UP_TO_DATE)
        builder.put(':common:javaPreCompileDebug', FROM_CACHE)
        builder.put(':common:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':common:mergeDebugGeneratedProguardFiles', SUCCESS)
        builder.put(':common:mergeDebugJavaResource', SUCCESS)
        builder.put(':common:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':common:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':common:mergeDebugShaders', FROM_CACHE)
        builder.put(':common:packageDebugAssets', FROM_CACHE)
        builder.put(':common:packageDebugRenderscript', NO_SOURCE)
        builder.put(':common:packageDebugResources', FROM_CACHE)
        builder.put(':common:parseDebugLibraryResources', FROM_CACHE)
        builder.put(':common:preBuild', UP_TO_DATE)
        builder.put(':common:preDebugBuild', UP_TO_DATE)
        builder.put(':common:prepareLintJarForPublish', SUCCESS)
        builder.put(':common:processDebugJavaRes', NO_SOURCE)
        builder.put(':common:processDebugManifest', FROM_CACHE)
        builder.put(':common:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':common:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':common:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':common:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':dasherdancer:assembleDebug', SUCCESS)
        builder.put(':dasherdancer:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':dasherdancer:checkDebugManifest', SUCCESS)
        builder.put(':dasherdancer:compileDebugAidl', NO_SOURCE)
        builder.put(':dasherdancer:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugKotlin', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugRenderscript', NO_SOURCE)
        builder.put(':dasherdancer:compileDebugShaders', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugSources', UP_TO_DATE)
        builder.put(':dasherdancer:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':dasherdancer:featureDebugWriter', SUCCESS)
        builder.put(':dasherdancer:generateDebugAssets', UP_TO_DATE)
        builder.put(':dasherdancer:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugResValues', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugResources', UP_TO_DATE)
        builder.put(':dasherdancer:javaPreCompileDebug', FROM_CACHE)
        builder.put(':dasherdancer:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugAssets', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugJavaResource', SUCCESS)
        builder.put(':dasherdancer:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugResources', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugShaders', FROM_CACHE)
        builder.put(':dasherdancer:mergeExtDexDebug', FROM_CACHE)
        builder.put(':dasherdancer:mergeLibDexDebug', FROM_CACHE)
        builder.put(':dasherdancer:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':dasherdancer:packageDebug', SUCCESS)
        builder.put(':dasherdancer:preBuild', UP_TO_DATE)
        builder.put(':dasherdancer:preDebugBuild', UP_TO_DATE)
        builder.put(':dasherdancer:processDebugJavaRes', NO_SOURCE)
        builder.put(':dasherdancer:processDebugManifest', FROM_CACHE)
        builder.put(':dasherdancer:processDebugResources', FROM_CACHE)
        builder.put(':dasherdancer:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':dasherdancer:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':doodles-lib:assembleDebug', SUCCESS)
        builder.put(':doodles-lib:bundleDebugAar', SUCCESS)
        builder.put(':doodles-lib:bundleLibCompileDebug', SUCCESS)
        builder.put(':doodles-lib:bundleLibResDebug', SUCCESS)
        builder.put(':doodles-lib:bundleLibRuntimeDebug', SUCCESS)
        builder.put(':doodles-lib:checkDebugManifest', SUCCESS)
        builder.put(':doodles-lib:compileDebugAidl', NO_SOURCE)
        builder.put(':doodles-lib:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':doodles-lib:compileDebugRenderscript', NO_SOURCE)
        builder.put(':doodles-lib:compileDebugShaders', FROM_CACHE)
        builder.put(':doodles-lib:compileDebugSources', UP_TO_DATE)
        builder.put(':doodles-lib:createFullJarDebug', FROM_CACHE)
        builder.put(':doodles-lib:extractDebugAnnotations', FROM_CACHE)
        builder.put(':doodles-lib:generateDebugAssets', UP_TO_DATE)
        builder.put(':doodles-lib:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':doodles-lib:generateDebugRFile', FROM_CACHE)
        builder.put(':doodles-lib:generateDebugResValues', FROM_CACHE)
        builder.put(':doodles-lib:generateDebugResources', UP_TO_DATE)
        builder.put(':doodles-lib:javaPreCompileDebug', FROM_CACHE)
        builder.put(':doodles-lib:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':doodles-lib:mergeDebugGeneratedProguardFiles', SUCCESS)
        builder.put(':doodles-lib:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':doodles-lib:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':doodles-lib:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':doodles-lib:mergeDebugShaders', FROM_CACHE)
        builder.put(':doodles-lib:packageDebugAssets', FROM_CACHE)
        builder.put(':doodles-lib:packageDebugRenderscript', NO_SOURCE)
        builder.put(':doodles-lib:packageDebugResources', FROM_CACHE)
        builder.put(':doodles-lib:parseDebugLibraryResources', FROM_CACHE)
        builder.put(':doodles-lib:preBuild', UP_TO_DATE)
        builder.put(':doodles-lib:preDebugBuild', UP_TO_DATE)
        builder.put(':doodles-lib:prepareLintJarForPublish', SUCCESS)
        builder.put(':doodles-lib:processDebugJavaRes', NO_SOURCE)
        builder.put(':doodles-lib:processDebugManifest', FROM_CACHE)
        builder.put(':doodles-lib:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':doodles-lib:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':doodles-lib:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':doodles-lib:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':gumball:assembleDebug', SUCCESS)
        builder.put(':gumball:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':gumball:checkDebugManifest', SUCCESS)
        builder.put(':gumball:compileDebugAidl', NO_SOURCE)
        builder.put(':gumball:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':gumball:compileDebugRenderscript', NO_SOURCE)
        builder.put(':gumball:compileDebugShaders', FROM_CACHE)
        builder.put(':gumball:compileDebugSources', UP_TO_DATE)
        builder.put(':gumball:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':gumball:featureDebugWriter', SUCCESS)
        builder.put(':gumball:generateDebugAssets', UP_TO_DATE)
        builder.put(':gumball:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':gumball:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':gumball:generateDebugResValues', FROM_CACHE)
        builder.put(':gumball:generateDebugResources', UP_TO_DATE)
        builder.put(':gumball:javaPreCompileDebug', FROM_CACHE)
        builder.put(':gumball:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':gumball:mergeDebugAssets', FROM_CACHE)
        builder.put(':gumball:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':gumball:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':gumball:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':gumball:mergeDebugResources', FROM_CACHE)
        builder.put(':gumball:mergeDebugShaders', FROM_CACHE)
        builder.put(':gumball:mergeExtDexDebug', FROM_CACHE)
        builder.put(':gumball:mergeLibDexDebug', FROM_CACHE)
        builder.put(':gumball:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':gumball:packageDebug', SUCCESS)
        builder.put(':gumball:preBuild', UP_TO_DATE)
        builder.put(':gumball:preDebugBuild', UP_TO_DATE)
        builder.put(':gumball:processDebugJavaRes', NO_SOURCE)
        builder.put(':gumball:processDebugManifest', FROM_CACHE)
        builder.put(':gumball:processDebugResources', FROM_CACHE)
        builder.put(':gumball:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':gumball:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':jetpack:assembleDebug', SUCCESS)
        builder.put(':jetpack:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':jetpack:checkDebugManifest', SUCCESS)
        builder.put(':jetpack:compileDebugAidl', NO_SOURCE)
        builder.put(':jetpack:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':jetpack:compileDebugKotlin', FROM_CACHE)
        builder.put(':jetpack:compileDebugRenderscript', NO_SOURCE)
        builder.put(':jetpack:compileDebugShaders', FROM_CACHE)
        builder.put(':jetpack:compileDebugSources', UP_TO_DATE)
        builder.put(':jetpack:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':jetpack:featureDebugWriter', SUCCESS)
        builder.put(':jetpack:generateDebugAssets', UP_TO_DATE)
        builder.put(':jetpack:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':jetpack:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':jetpack:generateDebugResValues', FROM_CACHE)
        builder.put(':jetpack:generateDebugResources', UP_TO_DATE)
        builder.put(':jetpack:javaPreCompileDebug', FROM_CACHE)
        builder.put(':jetpack:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':jetpack:mergeDebugAssets', FROM_CACHE)
        builder.put(':jetpack:mergeDebugJavaResource', SUCCESS)
        builder.put(':jetpack:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':jetpack:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':jetpack:mergeDebugResources', FROM_CACHE)
        builder.put(':jetpack:mergeDebugShaders', FROM_CACHE)
        builder.put(':jetpack:mergeExtDexDebug', FROM_CACHE)
        builder.put(':jetpack:mergeLibDexDebug', FROM_CACHE)
        builder.put(':jetpack:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':jetpack:packageDebug', SUCCESS)
        builder.put(':jetpack:preBuild', UP_TO_DATE)
        builder.put(':jetpack:preDebugBuild', UP_TO_DATE)
        builder.put(':jetpack:processDebugJavaRes', NO_SOURCE)
        builder.put(':jetpack:processDebugManifest', FROM_CACHE)
        builder.put(':jetpack:processDebugResources', FROM_CACHE)
        builder.put(':jetpack:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':jetpack:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':memory:assembleDebug', SUCCESS)
        builder.put(':memory:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':memory:checkDebugManifest', SUCCESS)
        builder.put(':memory:compileDebugAidl', NO_SOURCE)
        builder.put(':memory:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':memory:compileDebugRenderscript', NO_SOURCE)
        builder.put(':memory:compileDebugShaders', FROM_CACHE)
        builder.put(':memory:compileDebugSources', UP_TO_DATE)
        builder.put(':memory:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':memory:featureDebugWriter', SUCCESS)
        builder.put(':memory:generateDebugAssets', UP_TO_DATE)
        builder.put(':memory:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':memory:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':memory:generateDebugResValues', FROM_CACHE)
        builder.put(':memory:generateDebugResources', UP_TO_DATE)
        builder.put(':memory:javaPreCompileDebug', FROM_CACHE)
        builder.put(':memory:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':memory:mergeDebugAssets', FROM_CACHE)
        builder.put(':memory:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':memory:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':memory:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':memory:mergeDebugResources', FROM_CACHE)
        builder.put(':memory:mergeDebugShaders', FROM_CACHE)
        builder.put(':memory:mergeExtDexDebug', FROM_CACHE)
        builder.put(':memory:mergeLibDexDebug', FROM_CACHE)
        builder.put(':memory:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':memory:packageDebug', SUCCESS)
        builder.put(':memory:preBuild', UP_TO_DATE)
        builder.put(':memory:preDebugBuild', UP_TO_DATE)
        builder.put(':memory:processDebugJavaRes', NO_SOURCE)
        builder.put(':memory:processDebugManifest', FROM_CACHE)
        builder.put(':memory:processDebugResources', FROM_CACHE)
        builder.put(':memory:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':memory:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':penguinswim:assembleDebug', SUCCESS)
        builder.put(':penguinswim:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':penguinswim:checkDebugManifest', SUCCESS)
        builder.put(':penguinswim:compileDebugAidl', NO_SOURCE)
        builder.put(':penguinswim:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':penguinswim:compileDebugRenderscript', NO_SOURCE)
        builder.put(':penguinswim:compileDebugShaders', FROM_CACHE)
        builder.put(':penguinswim:compileDebugSources', UP_TO_DATE)
        builder.put(':penguinswim:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':penguinswim:featureDebugWriter', SUCCESS)
        builder.put(':penguinswim:generateDebugAssets', UP_TO_DATE)
        builder.put(':penguinswim:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':penguinswim:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':penguinswim:generateDebugResValues', FROM_CACHE)
        builder.put(':penguinswim:generateDebugResources', UP_TO_DATE)
        builder.put(':penguinswim:javaPreCompileDebug', FROM_CACHE)
        builder.put(':penguinswim:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugAssets', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugResources', FROM_CACHE)
        builder.put(':penguinswim:mergeDebugShaders', FROM_CACHE)
        builder.put(':penguinswim:mergeExtDexDebug', FROM_CACHE)
        builder.put(':penguinswim:mergeLibDexDebug', FROM_CACHE)
        builder.put(':penguinswim:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':penguinswim:packageDebug', SUCCESS)
        builder.put(':penguinswim:preBuild', UP_TO_DATE)
        builder.put(':penguinswim:preDebugBuild', UP_TO_DATE)
        builder.put(':penguinswim:processDebugJavaRes', NO_SOURCE)
        builder.put(':penguinswim:processDebugManifest', FROM_CACHE)
        builder.put(':penguinswim:processDebugResources', FROM_CACHE)
        builder.put(':penguinswim:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':penguinswim:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':playgames:assembleDebug', SUCCESS)
        builder.put(':playgames:bundleDebugAar', SUCCESS)
        builder.put(':playgames:bundleLibCompileDebug', SUCCESS)
        builder.put(':playgames:bundleLibResDebug', SUCCESS)
        builder.put(':playgames:bundleLibRuntimeDebug', SUCCESS)
        builder.put(':playgames:checkDebugManifest', SUCCESS)
        builder.put(':playgames:compileDebugAidl', NO_SOURCE)
        builder.put(':playgames:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':playgames:compileDebugRenderscript', NO_SOURCE)
        builder.put(':playgames:compileDebugShaders', FROM_CACHE)
        builder.put(':playgames:compileDebugSources', UP_TO_DATE)
        builder.put(':playgames:createFullJarDebug', FROM_CACHE)
        builder.put(':playgames:extractDebugAnnotations', FROM_CACHE)
        builder.put(':playgames:generateDebugAssets', UP_TO_DATE)
        builder.put(':playgames:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':playgames:generateDebugRFile', FROM_CACHE)
        builder.put(':playgames:generateDebugResValues', FROM_CACHE)
        builder.put(':playgames:generateDebugResources', UP_TO_DATE)
        builder.put(':playgames:javaPreCompileDebug', FROM_CACHE)
        builder.put(':playgames:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':playgames:mergeDebugGeneratedProguardFiles', SUCCESS)
        builder.put(':playgames:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':playgames:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':playgames:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':playgames:mergeDebugShaders', FROM_CACHE)
        builder.put(':playgames:packageDebugAssets', FROM_CACHE)
        builder.put(':playgames:packageDebugRenderscript', NO_SOURCE)
        builder.put(':playgames:packageDebugResources', FROM_CACHE)
        builder.put(':playgames:parseDebugLibraryResources', FROM_CACHE)
        builder.put(':playgames:preBuild', UP_TO_DATE)
        builder.put(':playgames:preDebugBuild', UP_TO_DATE)
        builder.put(':playgames:prepareLintJarForPublish', SUCCESS)
        builder.put(':playgames:processDebugJavaRes', NO_SOURCE)
        builder.put(':playgames:processDebugManifest', FROM_CACHE)
        builder.put(':playgames:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':playgames:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':playgames:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':playgames:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':presenttoss:assembleDebug', SUCCESS)
        builder.put(':presenttoss:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':presenttoss:checkDebugManifest', SUCCESS)
        builder.put(':presenttoss:compileDebugAidl', NO_SOURCE)
        builder.put(':presenttoss:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':presenttoss:compileDebugRenderscript', NO_SOURCE)
        builder.put(':presenttoss:compileDebugShaders', FROM_CACHE)
        builder.put(':presenttoss:compileDebugSources', UP_TO_DATE)
        builder.put(':presenttoss:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':presenttoss:featureDebugWriter', SUCCESS)
        builder.put(':presenttoss:generateDebugAssets', UP_TO_DATE)
        builder.put(':presenttoss:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':presenttoss:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':presenttoss:generateDebugResValues', FROM_CACHE)
        builder.put(':presenttoss:generateDebugResources', UP_TO_DATE)
        builder.put(':presenttoss:javaPreCompileDebug', FROM_CACHE)
        builder.put(':presenttoss:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugAssets', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugResources', FROM_CACHE)
        builder.put(':presenttoss:mergeDebugShaders', FROM_CACHE)
        builder.put(':presenttoss:mergeExtDexDebug', FROM_CACHE)
        builder.put(':presenttoss:mergeLibDexDebug', FROM_CACHE)
        builder.put(':presenttoss:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':presenttoss:packageDebug', SUCCESS)
        builder.put(':presenttoss:preBuild', UP_TO_DATE)
        builder.put(':presenttoss:preDebugBuild', UP_TO_DATE)
        builder.put(':presenttoss:processDebugJavaRes', NO_SOURCE)
        builder.put(':presenttoss:processDebugManifest', FROM_CACHE)
        builder.put(':presenttoss:processDebugResources', FROM_CACHE)
        builder.put(':presenttoss:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':presenttoss:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':rocketsleigh:assembleDebug', SUCCESS)
        builder.put(':rocketsleigh:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':rocketsleigh:checkDebugManifest', SUCCESS)
        builder.put(':rocketsleigh:compileDebugAidl', NO_SOURCE)
        builder.put(':rocketsleigh:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugKotlin', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugRenderscript', NO_SOURCE)
        builder.put(':rocketsleigh:compileDebugShaders', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugSources', UP_TO_DATE)
        builder.put(':rocketsleigh:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':rocketsleigh:featureDebugWriter', SUCCESS)
        builder.put(':rocketsleigh:generateDebugAssets', UP_TO_DATE)
        builder.put(':rocketsleigh:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugResValues', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugResources', UP_TO_DATE)
        builder.put(':rocketsleigh:javaPreCompileDebug', FROM_CACHE)
        builder.put(':rocketsleigh:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugAssets', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugJavaResource', SUCCESS)
        builder.put(':rocketsleigh:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugResources', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugShaders', FROM_CACHE)
        builder.put(':rocketsleigh:mergeExtDexDebug', FROM_CACHE)
        builder.put(':rocketsleigh:mergeLibDexDebug', FROM_CACHE)
        builder.put(':rocketsleigh:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':rocketsleigh:packageDebug', SUCCESS)
        builder.put(':rocketsleigh:preBuild', UP_TO_DATE)
        builder.put(':rocketsleigh:preDebugBuild', UP_TO_DATE)
        builder.put(':rocketsleigh:processDebugJavaRes', NO_SOURCE)
        builder.put(':rocketsleigh:processDebugManifest', FROM_CACHE)
        builder.put(':rocketsleigh:processDebugResources', FROM_CACHE)
        builder.put(':rocketsleigh:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':rocketsleigh:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':santa-tracker:assembleDebug', SUCCESS)
        builder.put(':santa-tracker:bundleDebugClasses', SUCCESS)
        builder.put(':santa-tracker:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':santa-tracker:checkDebugLibraries', FROM_CACHE)
        builder.put(':santa-tracker:checkDebugManifest', SUCCESS)
        builder.put(':santa-tracker:compileDebugAidl', NO_SOURCE)
        builder.put(':santa-tracker:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':santa-tracker:compileDebugKotlin', FROM_CACHE)
        builder.put(':santa-tracker:compileDebugRenderscript', NO_SOURCE)
        builder.put(':santa-tracker:compileDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:compileDebugSources', UP_TO_DATE)
        builder.put(':santa-tracker:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':santa-tracker:generateDebugAssets', UP_TO_DATE)
        builder.put(':santa-tracker:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':santa-tracker:generateDebugFeatureMetadata', SUCCESS)
        builder.put(':santa-tracker:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':santa-tracker:generateDebugResValues', FROM_CACHE)
        builder.put(':santa-tracker:generateDebugResources', SUCCESS)
        builder.put(':santa-tracker:generateLicenses', SUCCESS)
        builder.put(':santa-tracker:getDependencies', SUCCESS)
        builder.put(':santa-tracker:handleDebugMicroApk', SUCCESS)
        builder.put(':santa-tracker:javaPreCompileDebug', FROM_CACHE)
        builder.put(':santa-tracker:kaptDebugKotlin', FROM_CACHE)
        builder.put(':santa-tracker:kaptGenerateStubsDebugKotlin', FROM_CACHE)
        builder.put(':santa-tracker:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':santa-tracker:mergeDebugAssets', FROM_CACHE)
        builder.put(':santa-tracker:mergeDebugJavaResource', SUCCESS)
        builder.put(':santa-tracker:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':santa-tracker:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':santa-tracker:mergeDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:mergeDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:mergeExtDexDebug', FROM_CACHE)
        builder.put(':santa-tracker:mergeLibDexDebug', FROM_CACHE)
        builder.put(':santa-tracker:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':santa-tracker:packageDebug', SUCCESS)
        builder.put(':santa-tracker:preBuild', UP_TO_DATE)
        builder.put(':santa-tracker:preDebugBuild', FROM_CACHE)
        builder.put(':santa-tracker:processDebugGoogleServices', SUCCESS)
        builder.put(':santa-tracker:processDebugJavaRes', NO_SOURCE)
        builder.put(':santa-tracker:processDebugManifest', FROM_CACHE)
        builder.put(':santa-tracker:processDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:signingConfigWriterDebug', FROM_CACHE)
        builder.put(':santa-tracker:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':santa-tracker:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':santa-tracker:validateSigningDebug', FROM_CACHE)
        builder.put(':santa-tracker:writeDebugApplicationId', SUCCESS)
        builder.put(':santa-tracker:writeDebugModuleMetadata', SUCCESS)
        builder.put(':snowballrun:assembleDebug', SUCCESS)
        builder.put(':snowballrun:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':snowballrun:checkDebugManifest', SUCCESS)
        builder.put(':snowballrun:compileDebugAidl', NO_SOURCE)
        builder.put(':snowballrun:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':snowballrun:compileDebugRenderscript', NO_SOURCE)
        builder.put(':snowballrun:compileDebugShaders', FROM_CACHE)
        builder.put(':snowballrun:compileDebugSources', UP_TO_DATE)
        builder.put(':snowballrun:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':snowballrun:featureDebugWriter', SUCCESS)
        builder.put(':snowballrun:generateDebugAssets', UP_TO_DATE)
        builder.put(':snowballrun:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':snowballrun:generateDebugFeatureTransitiveDeps', FROM_CACHE)
        builder.put(':snowballrun:generateDebugResValues', FROM_CACHE)
        builder.put(':snowballrun:generateDebugResources', UP_TO_DATE)
        builder.put(':snowballrun:javaPreCompileDebug', FROM_CACHE)
        builder.put(':snowballrun:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugAssets', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugJavaResource', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugResources', FROM_CACHE)
        builder.put(':snowballrun:mergeDebugShaders', FROM_CACHE)
        builder.put(':snowballrun:mergeExtDexDebug', FROM_CACHE)
        builder.put(':snowballrun:mergeLibDexDebug', FROM_CACHE)
        builder.put(':snowballrun:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':snowballrun:packageDebug', SUCCESS)
        builder.put(':snowballrun:preBuild', UP_TO_DATE)
        builder.put(':snowballrun:preDebugBuild', UP_TO_DATE)
        builder.put(':snowballrun:processDebugJavaRes', NO_SOURCE)
        builder.put(':snowballrun:processDebugManifest', FROM_CACHE)
        builder.put(':snowballrun:processDebugResources', FROM_CACHE)
        builder.put(':snowballrun:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':snowballrun:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':tracker:assembleDebug', SUCCESS)
        builder.put(':tracker:bundleDebugAar', SUCCESS)
        builder.put(':tracker:bundleLibCompileDebug', SUCCESS)
        builder.put(':tracker:bundleLibResDebug', SUCCESS)
        builder.put(':tracker:bundleLibRuntimeDebug', SUCCESS)
        builder.put(':tracker:checkDebugManifest', SUCCESS)
        builder.put(':tracker:compileDebugAidl', NO_SOURCE)
        builder.put(':tracker:compileDebugJavaWithJavac', SUCCESS)
        builder.put(':tracker:compileDebugKotlin', FROM_CACHE)
        builder.put(':tracker:compileDebugRenderscript', NO_SOURCE)
        builder.put(':tracker:compileDebugShaders', FROM_CACHE)
        builder.put(':tracker:compileDebugSources', SUCCESS)
        builder.put(':tracker:createFullJarDebug', FROM_CACHE)
        builder.put(':tracker:extractDebugAnnotations', FROM_CACHE)
        builder.put(':tracker:generateDebugAssets', UP_TO_DATE)
        builder.put(':tracker:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':tracker:generateDebugRFile', FROM_CACHE)
        builder.put(':tracker:generateDebugResValues', FROM_CACHE)
        builder.put(':tracker:generateDebugResources', UP_TO_DATE)
        builder.put(':tracker:javaPreCompileDebug', FROM_CACHE)
        builder.put(':tracker:kaptDebugKotlin', SUCCESS)
        builder.put(':tracker:kaptGenerateStubsDebugKotlin', SUCCESS)
        builder.put(':tracker:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':tracker:mergeDebugGeneratedProguardFiles', SUCCESS)
        builder.put(':tracker:mergeDebugJavaResource', SUCCESS)
        builder.put(':tracker:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':tracker:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':tracker:mergeDebugResources', FROM_CACHE)
        builder.put(':tracker:mergeDebugShaders', FROM_CACHE)
        builder.put(':tracker:packageDebugAssets', FROM_CACHE)
        builder.put(':tracker:packageDebugRenderscript', NO_SOURCE)
        builder.put(':tracker:packageDebugResources', FROM_CACHE)
        builder.put(':tracker:parseDebugLibraryResources', FROM_CACHE)
        builder.put(':tracker:preBuild', UP_TO_DATE)
        builder.put(':tracker:preDebugBuild', UP_TO_DATE)
        builder.put(':tracker:prepareLintJarForPublish', SUCCESS)
        builder.put(':tracker:processDebugJavaRes', NO_SOURCE)
        builder.put(':tracker:processDebugManifest', FROM_CACHE)
        builder.put(':tracker:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':tracker:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':tracker:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':tracker:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':wearable:assembleDebug', SUCCESS)
        builder.put(':wearable:checkDebugDuplicateClasses', FROM_CACHE)
        builder.put(':wearable:checkDebugManifest', SUCCESS)
        builder.put(':wearable:compileDebugAidl', NO_SOURCE)
        builder.put(':wearable:compileDebugJavaWithJavac', FROM_CACHE)
        builder.put(':wearable:compileDebugKotlin', FROM_CACHE)
        builder.put(':wearable:compileDebugRenderscript', NO_SOURCE)
        builder.put(':wearable:compileDebugShaders', FROM_CACHE)
        builder.put(':wearable:compileDebugSources', UP_TO_DATE)
        builder.put(':wearable:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':wearable:generateDebugAssets', UP_TO_DATE)
        builder.put(':wearable:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':wearable:generateDebugResValues', FROM_CACHE)
        builder.put(':wearable:generateDebugResources', UP_TO_DATE)
        builder.put(':wearable:javaPreCompileDebug', FROM_CACHE)
        builder.put(':wearable:kaptDebugKotlin', FROM_CACHE)
        builder.put(':wearable:kaptGenerateStubsDebugKotlin', FROM_CACHE)
        builder.put(':wearable:mainApkListPersistenceDebug', FROM_CACHE)
        builder.put(':wearable:mergeDebugAssets', FROM_CACHE)
        builder.put(':wearable:mergeDebugJavaResource', SUCCESS)
        builder.put(':wearable:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':wearable:mergeDebugNativeLibs', FROM_CACHE)
        builder.put(':wearable:mergeDebugResources', FROM_CACHE)
        builder.put(':wearable:mergeDebugShaders', FROM_CACHE)
        builder.put(':wearable:mergeExtDexDebug', FROM_CACHE)
        builder.put(':wearable:mergeLibDexDebug', FROM_CACHE)
        builder.put(':wearable:mergeProjectDexDebug', FROM_CACHE)
        builder.put(':wearable:packageDebug', SUCCESS)
        builder.put(':wearable:preBuild', UP_TO_DATE)
        builder.put(':wearable:preDebugBuild', UP_TO_DATE)
        builder.put(':wearable:processDebugJavaRes', NO_SOURCE)
        builder.put(':wearable:processDebugManifest', FROM_CACHE)
        builder.put(':wearable:processDebugResources', FROM_CACHE)
        builder.put(':wearable:signingConfigWriterDebug', FROM_CACHE)
        builder.put(':wearable:stripDebugDebugSymbols', FROM_CACHE)
        builder.put(':wearable:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':wearable:validateSigningDebug', FROM_CACHE)
        return new ExpectedResults(builder.build())
    }

    GradleRunner createGradleRunner() {
        def gradleRunner = GradleRunner.create()

        def gradleInstallation = System.getProperty(GRADLE_INSTALLATION_PROPERTY)
        if (gradleInstallation) {
            gradleRunner.withGradleInstallation(new File(gradleInstallation))
            println "> Running with Gradle installation in $gradleInstallation"
        } else {
            def gradleVersion = DEFAULT_GRADLE_VERSION
            gradleRunner.withGradleVersion(gradleVersion)
            println "> Running with Gradle version $gradleVersion"
        }

        gradleRunner.forwardOutput()

        return gradleRunner
    }
}
