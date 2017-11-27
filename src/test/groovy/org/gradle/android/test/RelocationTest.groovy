package org.gradle.android.test

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

    static final String DEFAULT_GRADLE_VERSION = "4.4-rc-1"
    static final String DEFAULT_ANDROID_VERSION = "3.1.0-alpha04"

    @Rule TemporaryFolder temporaryFolder
    File cacheDir

    def setup() {
        cacheDir = temporaryFolder.newFolder()
    }

    def "santa-tracker can be built relocatably"() {
        def tasksToRun = ["assembleDebug"]
        def androidPluginVersion = System.getProperty(ANDROID_VERSION_PROPERTY)
        if (!androidPluginVersion) {
            androidPluginVersion = DEFAULT_ANDROID_VERSION
        }

        println "> Using Android plugin ${androidPluginVersion}"

        def originalDir = new File(System.getProperty("original.dir"))
        def relocatedDir = new File(System.getProperty("relocated.dir"))

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

                plugins.matching({ it.class.name == "com.gradle.scan.plugin.BuildScanPlugin" }).all {
                    root.buildScan {
                        server = "https://e.grdev.net"
                    }
                }
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
            "-Dorg.gradle.android.cache-fix.ignoreVersionCheck=true",
        ]

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
        createGradleRunner()
            .withProjectDir(dir)
            .withArguments("clean", *defaultArgs, "--no-build-cache")
            .build()
        new File(dir, ".gradle").deleteDir()
    }

    static class ExpectedResults {
        private final Map<String, TaskOutcome> outcomes

        ExpectedResults(Map<String, TaskOutcome> outcomes) {
            this.outcomes = outcomes
        }

        boolean verify(BuildResult result) {
            println "Expecting ${outcomes.values().count(FROM_CACHE)} tasks out of ${outcomes.size()} to be cached"

            def outcomesWithMatchingTasks = outcomes.findAll { result.task(it.key) }
            def hasMatchingTasks = outcomesWithMatchingTasks.size() == outcomes.size() && outcomesWithMatchingTasks.size() == result.tasks.size()
            if (!hasMatchingTasks) {
                println "> Tasks missing:    " + (outcomes.keySet() - outcomesWithMatchingTasks.keySet())
                println "> Tasks in surplus: " + (result.tasks*.path - outcomesWithMatchingTasks.keySet())
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

    def expectedResults = new ExpectedResults(
        ':common:assembleDebug': SUCCESS,
        ':common:bundleDebug': SUCCESS,
        ':common:checkDebugManifest': FROM_CACHE,
        ':common:compileDebugAidl': FROM_CACHE,
        ':common:compileDebugJavaWithJavac': FROM_CACHE,
        ':common:compileDebugNdk': NO_SOURCE,
        ':common:compileDebugRenderscript': FROM_CACHE,
        ':common:compileDebugShaders': FROM_CACHE,
        ':common:compileDebugSources': UP_TO_DATE,
        ':common:extractDebugAnnotations': FROM_CACHE,
        ':common:generateDebugAssets': UP_TO_DATE,
        ':common:generateDebugBuildConfig': FROM_CACHE,
        ':common:generateDebugResources': UP_TO_DATE,
        ':common:generateDebugResValues': FROM_CACHE,
        ':common:generateDebugRFile': FROM_CACHE,
        ':common:generateDebugSources': SUCCESS,
        ':common:javaPreCompileDebug': FROM_CACHE,
        ':common:mergeDebugConsumerProguardFiles': SUCCESS,
        ':common:mergeDebugJniLibFolders': FROM_CACHE,
        ':common:mergeDebugShaders': FROM_CACHE,
        ':common:packageDebugAssets': FROM_CACHE,
        ':common:packageDebugRenderscript': NO_SOURCE,
        ':common:packageDebugResources': FROM_CACHE,
        ':common:platformAttrExtractor': FROM_CACHE,
        ':common:preBuild': UP_TO_DATE,
        ':common:preDebugBuild': UP_TO_DATE,
        ':common:prepareLintJar': SUCCESS,
        ':common:processDebugJavaRes': NO_SOURCE,
        ':common:processDebugManifest': FROM_CACHE,
        ':common:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':common:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':common:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':common:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':common:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':common:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':dasherdancer:assembleDebug': SUCCESS,
        ':dasherdancer:bundleDebug': SUCCESS,
        ':dasherdancer:checkDebugManifest': FROM_CACHE,
        ':dasherdancer:compileDebugAidl': FROM_CACHE,
        ':dasherdancer:compileDebugJavaWithJavac': FROM_CACHE,
        ':dasherdancer:compileDebugNdk': NO_SOURCE,
        ':dasherdancer:compileDebugRenderscript': FROM_CACHE,
        ':dasherdancer:compileDebugShaders': FROM_CACHE,
        ':dasherdancer:compileDebugSources': UP_TO_DATE,
        ':dasherdancer:extractDebugAnnotations': FROM_CACHE,
        ':dasherdancer:generateDebugAssets': UP_TO_DATE,
        ':dasherdancer:generateDebugBuildConfig': FROM_CACHE,
        ':dasherdancer:generateDebugResources': UP_TO_DATE,
        ':dasherdancer:generateDebugResValues': FROM_CACHE,
        ':dasherdancer:generateDebugRFile': FROM_CACHE,
        ':dasherdancer:generateDebugSources': SUCCESS,
        ':dasherdancer:javaPreCompileDebug': FROM_CACHE,
        ':dasherdancer:mergeDebugConsumerProguardFiles': SUCCESS,
        ':dasherdancer:mergeDebugJniLibFolders': FROM_CACHE,
        ':dasherdancer:mergeDebugShaders': FROM_CACHE,
        ':dasherdancer:packageDebugAssets': FROM_CACHE,
        ':dasherdancer:packageDebugRenderscript': NO_SOURCE,
        ':dasherdancer:packageDebugResources': FROM_CACHE,
        ':dasherdancer:platformAttrExtractor': FROM_CACHE,
        ':dasherdancer:preBuild': UP_TO_DATE,
        ':dasherdancer:preDebugBuild': UP_TO_DATE,
        ':dasherdancer:prepareLintJar': SUCCESS,
        ':dasherdancer:processDebugJavaRes': NO_SOURCE,
        ':dasherdancer:processDebugManifest': FROM_CACHE,
        ':dasherdancer:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':dasherdancer:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':dasherdancer:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':dasherdancer:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':dasherdancer:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':dasherdancer:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':doodles:assembleDebug': SUCCESS,
        ':doodles:bundleDebug': SUCCESS,
        ':doodles:checkDebugManifest': FROM_CACHE,
        ':doodles:compileDebugAidl': FROM_CACHE,
        ':doodles:compileDebugJavaWithJavac': FROM_CACHE,
        ':doodles:compileDebugNdk': NO_SOURCE,
        ':doodles:compileDebugRenderscript': FROM_CACHE,
        ':doodles:compileDebugShaders': FROM_CACHE,
        ':doodles:compileDebugSources': UP_TO_DATE,
        ':doodles:extractDebugAnnotations': FROM_CACHE,
        ':doodles:generateDebugAssets': UP_TO_DATE,
        ':doodles:generateDebugBuildConfig': FROM_CACHE,
        ':doodles:generateDebugResources': UP_TO_DATE,
        ':doodles:generateDebugResValues': FROM_CACHE,
        ':doodles:generateDebugRFile': FROM_CACHE,
        ':doodles:generateDebugSources': SUCCESS,
        ':doodles:javaPreCompileDebug': FROM_CACHE,
        ':doodles:mergeDebugConsumerProguardFiles': SUCCESS,
        ':doodles:mergeDebugJniLibFolders': FROM_CACHE,
        ':doodles:mergeDebugShaders': FROM_CACHE,
        ':doodles:packageDebugAssets': FROM_CACHE,
        ':doodles:packageDebugRenderscript': NO_SOURCE,
        ':doodles:packageDebugResources': FROM_CACHE,
        ':doodles:platformAttrExtractor': FROM_CACHE,
        ':doodles:preBuild': UP_TO_DATE,
        ':doodles:preDebugBuild': UP_TO_DATE,
        ':doodles:prepareLintJar': SUCCESS,
        ':doodles:processDebugJavaRes': NO_SOURCE,
        ':doodles:processDebugManifest': FROM_CACHE,
        ':doodles:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':doodles:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':doodles:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':doodles:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':doodles:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':doodles:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':presentquest:assembleDebug': SUCCESS,
        ':presentquest:bundleDebug': SUCCESS,
        ':presentquest:checkDebugManifest': FROM_CACHE,
        ':presentquest:compileDebugAidl': FROM_CACHE,
        ':presentquest:compileDebugJavaWithJavac': FROM_CACHE,
        ':presentquest:compileDebugNdk': NO_SOURCE,
        ':presentquest:compileDebugRenderscript': FROM_CACHE,
        ':presentquest:compileDebugShaders': FROM_CACHE,
        ':presentquest:compileDebugSources': UP_TO_DATE,
        ':presentquest:extractDebugAnnotations': FROM_CACHE,
        ':presentquest:generateDebugAssets': UP_TO_DATE,
        ':presentquest:generateDebugBuildConfig': FROM_CACHE,
        ':presentquest:generateDebugResources': UP_TO_DATE,
        ':presentquest:generateDebugResValues': FROM_CACHE,
        ':presentquest:generateDebugRFile': FROM_CACHE,
        ':presentquest:generateDebugSources': SUCCESS,
        ':presentquest:javaPreCompileDebug': FROM_CACHE,
        ':presentquest:mergeDebugConsumerProguardFiles': SUCCESS,
        ':presentquest:mergeDebugJniLibFolders': FROM_CACHE,
        ':presentquest:mergeDebugShaders': FROM_CACHE,
        ':presentquest:packageDebugAssets': FROM_CACHE,
        ':presentquest:packageDebugRenderscript': NO_SOURCE,
        ':presentquest:packageDebugResources': FROM_CACHE,
        ':presentquest:platformAttrExtractor': FROM_CACHE,
        ':presentquest:preBuild': UP_TO_DATE,
        ':presentquest:preDebugBuild': UP_TO_DATE,
        ':presentquest:prepareLintJar': SUCCESS,
        ':presentquest:processDebugJavaRes': NO_SOURCE,
        ':presentquest:processDebugManifest': FROM_CACHE,
        ':presentquest:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':presentquest:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':presentquest:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':presentquest:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':presentquest:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':presentquest:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':rocketsleigh:assembleDebug': SUCCESS,
        ':rocketsleigh:bundleDebug': SUCCESS,
        ':rocketsleigh:checkDebugManifest': FROM_CACHE,
        ':rocketsleigh:compileDebugAidl': FROM_CACHE,
        ':rocketsleigh:compileDebugJavaWithJavac': FROM_CACHE,
        ':rocketsleigh:compileDebugNdk': NO_SOURCE,
        ':rocketsleigh:compileDebugRenderscript': FROM_CACHE,
        ':rocketsleigh:compileDebugShaders': FROM_CACHE,
        ':rocketsleigh:compileDebugSources': UP_TO_DATE,
        ':rocketsleigh:extractDebugAnnotations': FROM_CACHE,
        ':rocketsleigh:generateDebugAssets': UP_TO_DATE,
        ':rocketsleigh:generateDebugBuildConfig': FROM_CACHE,
        ':rocketsleigh:generateDebugResources': UP_TO_DATE,
        ':rocketsleigh:generateDebugResValues': FROM_CACHE,
        ':rocketsleigh:generateDebugRFile': FROM_CACHE,
        ':rocketsleigh:generateDebugSources': SUCCESS,
        ':rocketsleigh:javaPreCompileDebug': FROM_CACHE,
        ':rocketsleigh:mergeDebugConsumerProguardFiles': SUCCESS,
        ':rocketsleigh:mergeDebugJniLibFolders': FROM_CACHE,
        ':rocketsleigh:mergeDebugShaders': FROM_CACHE,
        ':rocketsleigh:packageDebugAssets': FROM_CACHE,
        ':rocketsleigh:packageDebugRenderscript': NO_SOURCE,
        ':rocketsleigh:packageDebugResources': FROM_CACHE,
        ':rocketsleigh:platformAttrExtractor': FROM_CACHE,
        ':rocketsleigh:preBuild': UP_TO_DATE,
        ':rocketsleigh:preDebugBuild': UP_TO_DATE,
        ':rocketsleigh:prepareLintJar': SUCCESS,
        ':rocketsleigh:processDebugJavaRes': NO_SOURCE,
        ':rocketsleigh:processDebugManifest': FROM_CACHE,
        ':rocketsleigh:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':rocketsleigh:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':rocketsleigh:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':rocketsleigh:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':rocketsleigh:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':rocketsleigh:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':santa-tracker:assembleDebug': SUCCESS,
        ':santa-tracker:assembleDevelopmentDebug': SUCCESS,
        ':santa-tracker:assembleProductionDebug': SUCCESS,
        ':santa-tracker:checkDevelopmentDebugManifest': FROM_CACHE,
        ':santa-tracker:checkProductionDebugManifest': FROM_CACHE,
        ':santa-tracker:compileDevelopmentDebugAidl': FROM_CACHE,
        ':santa-tracker:compileDevelopmentDebugJavaWithJavac': FROM_CACHE,
        ':santa-tracker:compileDevelopmentDebugNdk': NO_SOURCE,
        ':santa-tracker:compileDevelopmentDebugRenderscript': FROM_CACHE,
        ':santa-tracker:compileDevelopmentDebugShaders': FROM_CACHE,
        ':santa-tracker:compileDevelopmentDebugSources': UP_TO_DATE,
        ':santa-tracker:compileProductionDebugAidl': FROM_CACHE,
        ':santa-tracker:compileProductionDebugJavaWithJavac': FROM_CACHE,
        ':santa-tracker:compileProductionDebugNdk': NO_SOURCE,
        ':santa-tracker:compileProductionDebugRenderscript': FROM_CACHE,
        ':santa-tracker:compileProductionDebugShaders': FROM_CACHE,
        ':santa-tracker:compileProductionDebugSources': UP_TO_DATE,
        ':santa-tracker:createDevelopmentDebugCompatibleScreenManifests': FROM_CACHE,
        ':santa-tracker:createProductionDebugCompatibleScreenManifests': FROM_CACHE,
        ':santa-tracker:generateDevelopmentDebugAssets': UP_TO_DATE,
        ':santa-tracker:generateDevelopmentDebugBuildConfig': FROM_CACHE,
        ':santa-tracker:generateDevelopmentDebugResources': UP_TO_DATE,
        ':santa-tracker:generateDevelopmentDebugResValues': FROM_CACHE,
        ':santa-tracker:generateDevelopmentDebugSources': SUCCESS,
        ':santa-tracker:generateProductionDebugAssets': UP_TO_DATE,
        ':santa-tracker:generateProductionDebugBuildConfig': FROM_CACHE,
        ':santa-tracker:generateProductionDebugResources': UP_TO_DATE,
        ':santa-tracker:generateProductionDebugResValues': FROM_CACHE,
        ':santa-tracker:generateProductionDebugSources': SUCCESS,
        ':santa-tracker:javaPreCompileDevelopmentDebug': FROM_CACHE,
        ':santa-tracker:javaPreCompileProductionDebug': FROM_CACHE,
        ':santa-tracker:mergeDevelopmentDebugAssets': FROM_CACHE,
        ':santa-tracker:mergeDevelopmentDebugJniLibFolders': FROM_CACHE,
        ':santa-tracker:mergeDevelopmentDebugResources': FROM_CACHE,
        ':santa-tracker:mergeDevelopmentDebugShaders': FROM_CACHE,
        ':santa-tracker:mergeProductionDebugAssets': FROM_CACHE,
        ':santa-tracker:mergeProductionDebugJniLibFolders': FROM_CACHE,
        ':santa-tracker:mergeProductionDebugResources': FROM_CACHE,
        ':santa-tracker:mergeProductionDebugShaders': FROM_CACHE,
        ':santa-tracker:packageDevelopmentDebug': SUCCESS,
        ':santa-tracker:packageProductionDebug': SUCCESS,
        ':santa-tracker:preBuild': UP_TO_DATE,
        ':santa-tracker:preDevelopmentDebugBuild': FROM_CACHE,
        ':santa-tracker:prepareLintJar': SUCCESS,
        ':santa-tracker:preProductionDebugBuild': FROM_CACHE,
        ':santa-tracker:processDevelopmentDebugGoogleServices': SUCCESS,
        ':santa-tracker:processDevelopmentDebugJavaRes': NO_SOURCE,
        ':santa-tracker:processDevelopmentDebugManifest': FROM_CACHE,
        ':santa-tracker:processDevelopmentDebugResources': FROM_CACHE,
        ':santa-tracker:processProductionDebugGoogleServices': SUCCESS,
        ':santa-tracker:processProductionDebugJavaRes': NO_SOURCE,
        ':santa-tracker:processProductionDebugManifest': FROM_CACHE,
        ':santa-tracker:processProductionDebugResources': FROM_CACHE,
        ':santa-tracker:splitsDiscoveryTaskDevelopmentDebug': FROM_CACHE,
        ':santa-tracker:splitsDiscoveryTaskProductionDebug': FROM_CACHE,
        ':santa-tracker:transformClassesWithDexBuilderForDevelopmentDebug': SUCCESS,
        ':santa-tracker:transformClassesWithDexBuilderForProductionDebug': SUCCESS,
        ':santa-tracker:transformClassesWithMultidexlistForProductionDebug': SUCCESS,
        ':santa-tracker:transformDexArchiveWithDexMergerForDevelopmentDebug': SUCCESS,
        ':santa-tracker:transformDexArchiveWithDexMergerForProductionDebug': SUCCESS,
        ':santa-tracker:transformDexArchiveWithExternalLibsDexMergerForDevelopmentDebug': SUCCESS,
        ':santa-tracker:transformNativeLibsWithMergeJniLibsForDevelopmentDebug': SUCCESS,
        ':santa-tracker:transformNativeLibsWithMergeJniLibsForProductionDebug': SUCCESS,
        ':santa-tracker:transformResourcesWithMergeJavaResForDevelopmentDebug': SUCCESS,
        ':santa-tracker:transformResourcesWithMergeJavaResForProductionDebug': SUCCESS,
        ':santa-tracker:validateSigningDevelopmentDebug': SUCCESS,
        ':santa-tracker:validateSigningProductionDebug': SUCCESS,
        ':snowdown:assembleDebug': SUCCESS,
        ':snowdown:bundleDebug': SUCCESS,
        ':snowdown:checkDebugManifest': FROM_CACHE,
        ':snowdown:compileDebugAidl': FROM_CACHE,
        ':snowdown:compileDebugJavaWithJavac': FROM_CACHE,
        ':snowdown:compileDebugNdk': NO_SOURCE,
        ':snowdown:compileDebugRenderscript': FROM_CACHE,
        ':snowdown:compileDebugShaders': FROM_CACHE,
        ':snowdown:compileDebugSources': UP_TO_DATE,
        ':snowdown:extractDebugAnnotations': FROM_CACHE,
        ':snowdown:generateDebugAssets': UP_TO_DATE,
        ':snowdown:generateDebugBuildConfig': FROM_CACHE,
        ':snowdown:generateDebugResources': UP_TO_DATE,
        ':snowdown:generateDebugResValues': FROM_CACHE,
        ':snowdown:generateDebugRFile': FROM_CACHE,
        ':snowdown:generateDebugSources': SUCCESS,
        ':snowdown:javaPreCompileDebug': FROM_CACHE,
        ':snowdown:mergeDebugConsumerProguardFiles': SUCCESS,
        ':snowdown:mergeDebugJniLibFolders': FROM_CACHE,
        ':snowdown:mergeDebugShaders': FROM_CACHE,
        ':snowdown:packageDebugAssets': FROM_CACHE,
        ':snowdown:packageDebugRenderscript': NO_SOURCE,
        ':snowdown:packageDebugResources': FROM_CACHE,
        ':snowdown:platformAttrExtractor': FROM_CACHE,
        ':snowdown:preBuild': UP_TO_DATE,
        ':snowdown:preDebugBuild': UP_TO_DATE,
        ':snowdown:prepareLintJar': SUCCESS,
        ':snowdown:processDebugJavaRes': NO_SOURCE,
        ':snowdown:processDebugManifest': FROM_CACHE,
        ':snowdown:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':snowdown:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':snowdown:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':snowdown:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':snowdown:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':snowdown:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':village:assembleDebug': SUCCESS,
        ':village:bundleDebug': SUCCESS,
        ':village:checkDebugManifest': FROM_CACHE,
        ':village:compileDebugAidl': FROM_CACHE,
        ':village:compileDebugJavaWithJavac': FROM_CACHE,
        ':village:compileDebugNdk': NO_SOURCE,
        ':village:compileDebugRenderscript': FROM_CACHE,
        ':village:compileDebugShaders': FROM_CACHE,
        ':village:compileDebugSources': UP_TO_DATE,
        ':village:extractDebugAnnotations': FROM_CACHE,
        ':village:generateDebugAssets': UP_TO_DATE,
        ':village:generateDebugBuildConfig': FROM_CACHE,
        ':village:generateDebugResources': UP_TO_DATE,
        ':village:generateDebugResValues': FROM_CACHE,
        ':village:generateDebugRFile': FROM_CACHE,
        ':village:generateDebugSources': SUCCESS,
        ':village:javaPreCompileDebug': FROM_CACHE,
        ':village:mergeDebugConsumerProguardFiles': SUCCESS,
        ':village:mergeDebugJniLibFolders': FROM_CACHE,
        ':village:mergeDebugShaders': FROM_CACHE,
        ':village:packageDebugAssets': FROM_CACHE,
        ':village:packageDebugRenderscript': NO_SOURCE,
        ':village:packageDebugResources': FROM_CACHE,
        ':village:platformAttrExtractor': FROM_CACHE,
        ':village:preBuild': UP_TO_DATE,
        ':village:preDebugBuild': UP_TO_DATE,
        ':village:prepareLintJar': SUCCESS,
        ':village:processDebugJavaRes': NO_SOURCE,
        ':village:processDebugManifest': FROM_CACHE,
        ':village:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug': SUCCESS,
        ':village:transformClassesAndResourcesWithSyncLibJarsForDebug': SUCCESS,
        ':village:transformNativeLibsWithIntermediateJniLibsForDebug': SUCCESS,
        ':village:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':village:transformNativeLibsWithSyncJniLibsForDebug': SUCCESS,
        ':village:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':wearable:assembleDebug': SUCCESS,
        ':wearable:checkDebugManifest': FROM_CACHE,
        ':wearable:compileDebugAidl': FROM_CACHE,
        ':wearable:compileDebugJavaWithJavac': FROM_CACHE,
        ':wearable:compileDebugNdk': NO_SOURCE,
        ':wearable:compileDebugRenderscript': FROM_CACHE,
        ':wearable:compileDebugShaders': FROM_CACHE,
        ':wearable:compileDebugSources': UP_TO_DATE,
        ':wearable:createDebugCompatibleScreenManifests': FROM_CACHE,
        ':wearable:generateDebugAssets': UP_TO_DATE,
        ':wearable:generateDebugBuildConfig': FROM_CACHE,
        ':wearable:generateDebugResources': UP_TO_DATE,
        ':wearable:generateDebugResValues': FROM_CACHE,
        ':wearable:generateDebugSources': SUCCESS,
        ':wearable:javaPreCompileDebug': FROM_CACHE,
        ':wearable:mergeDebugAssets': FROM_CACHE,
        ':wearable:mergeDebugJniLibFolders': FROM_CACHE,
        ':wearable:mergeDebugResources': FROM_CACHE,
        ':wearable:mergeDebugShaders': FROM_CACHE,
        ':wearable:packageDebug': SUCCESS,
        ':wearable:preBuild': UP_TO_DATE,
        ':wearable:preDebugBuild': FROM_CACHE,
        ':wearable:prepareLintJar': SUCCESS,
        ':wearable:processDebugJavaRes': NO_SOURCE,
        ':wearable:processDebugManifest': FROM_CACHE,
        ':wearable:processDebugResources': FROM_CACHE,
        ':wearable:splitsDiscoveryTaskDebug': FROM_CACHE,
        ':wearable:transformClassesWithDexBuilderForDebug': SUCCESS,
        ':wearable:transformDexArchiveWithDexMergerForDebug': SUCCESS,
        ':wearable:transformDexArchiveWithExternalLibsDexMergerForDebug': SUCCESS,
        ':wearable:transformNativeLibsWithMergeJniLibsForDebug': SUCCESS,
        ':wearable:transformResourcesWithMergeJavaResForDebug': SUCCESS,
        ':wearable:validateSigningDebug': SUCCESS,
    )

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
