package org.gradle.android.test

import com.google.common.collect.ImmutableMap
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import spock.lang.Specification
import spock.lang.Unroll

import static org.gradle.testkit.runner.TaskOutcome.FROM_CACHE
import static org.gradle.testkit.runner.TaskOutcome.NO_SOURCE
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS
import static org.gradle.testkit.runner.TaskOutcome.UP_TO_DATE

class RelocationTest extends Specification {

    static final String GRADLE_INSTALLATION_PROPERTY = "org.gradle.android.test.gradle-installation"
    static final String ANDROID_VERSION_PROPERTY = "org.gradle.android.test.android-version"
    static final String ANDROID_CACHE_FIX_VERSION_PROPERTY = "org.gradle.android.test.android-cache-fix-version"
    static final String SCAN_URL_PROPERTY = "org.gradle.android.test.scan-url"

    static final String DEFAULT_GRADLE_VERSION = "4.4-rc-1"
    static final String DEFAULT_ANDROID_VERSION = "3.1.0-alpha04"
    static final String DEFAULT_ANDROID_CACHE_FIX_VERSION = "0.1.11"

    @Rule TemporaryFolder temporaryFolder
    File cacheDir
    String androidPluginVersion
    String androidCacheFixVersion
    String scanUrl

    def setup() {
        cacheDir = temporaryFolder.newFolder()

        androidPluginVersion = System.getProperty(ANDROID_VERSION_PROPERTY)
        if (!androidPluginVersion) {
            androidPluginVersion = DEFAULT_ANDROID_VERSION
        }

        androidCacheFixVersion = System.getProperty(ANDROID_CACHE_FIX_VERSION_PROPERTY)
        if (!androidCacheFixVersion) {
            androidCacheFixVersion = DEFAULT_ANDROID_CACHE_FIX_VERSION
        }

        scanUrl = System.getProperty(SCAN_URL_PROPERTY)
    }

    @Unroll
    def "santa-tracker can be built relocatably #cacheFixApplied"() {
        def tasksToRun = ["assembleDebug"]

        println "> Using Android plugin ${androidPluginVersion}"
        println "> Cache directory: $cacheDir (files: ${cacheDir.listFiles().length})"

        def originalDir = new File(System.getProperty("original.dir"))
        def relocatedDir = new File(System.getProperty("relocated.dir"))

        def expectedResults = expectedResults(applyCacheFix)

        def applyCacheFixPluginIfNecessary = applyCacheFix ? """
            rootProject {
                buildscript {
                    dependencies {
                        classpath 'gradle.plugin.org.gradle.android:android-cache-fix-gradle-plugin:$androidCacheFixVersion'
                    }
                }
            }

            allprojects { project ->
                project.plugins.matching({ it.class.name == "com.android.build.gradle.api.AndroidBasePlugin" }).all {
                    project.apply plugin: 'org.gradle.android.cache-fix'
                }
            }
        """ : ""

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

            $applyCacheFixPluginIfNecessary

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

        where:
        applyCacheFix << [false, true]
        cacheFixApplied = applyCacheFix ? "with cache fix" : "without cache fix"
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

    def expectedResults(boolean fixApplied) {
        def builder = ImmutableMap.<String, TaskOutcome>builder()
        builder.put(':common:assembleDebug', SUCCESS)
        builder.put(':common:bundleDebug', SUCCESS)
        builder.put(':common:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':common:compileDebugAidl', FROM_CACHE)
        builder.put(':common:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':common:compileDebugNdk', NO_SOURCE)
        builder.put(':common:compileDebugRenderscript', FROM_CACHE)
        builder.put(':common:compileDebugShaders', FROM_CACHE)
        builder.put(':common:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':common:extractDebugAnnotations', FROM_CACHE)
        builder.put(':common:generateDebugAssets', UP_TO_DATE)
        builder.put(':common:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':common:generateDebugResources', UP_TO_DATE)
        builder.put(':common:generateDebugResValues', FROM_CACHE)
        builder.put(':common:generateDebugRFile', FROM_CACHE)
        builder.put(':common:generateDebugSources', SUCCESS)
        builder.put(':common:javaPreCompileDebug', FROM_CACHE)
        builder.put(':common:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':common:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':common:mergeDebugShaders', FROM_CACHE)
        builder.put(':common:packageDebugAssets', FROM_CACHE)
        builder.put(':common:packageDebugRenderscript', NO_SOURCE)
        builder.put(':common:packageDebugResources', FROM_CACHE)
        builder.put(':common:platformAttrExtractor', FROM_CACHE)
        builder.put(':common:preBuild', UP_TO_DATE)
        builder.put(':common:preDebugBuild', UP_TO_DATE)
        builder.put(':common:prepareLintJar', SUCCESS)
        builder.put(':common:processDebugJavaRes', NO_SOURCE)
        builder.put(':common:processDebugManifest', FROM_CACHE)
        builder.put(':common:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':common:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':common:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':common:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':common:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':common:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':dasherdancer:assembleDebug', SUCCESS)
        builder.put(':dasherdancer:bundleDebug', SUCCESS)
        builder.put(':dasherdancer:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':dasherdancer:compileDebugAidl', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':dasherdancer:compileDebugNdk', NO_SOURCE)
        builder.put(':dasherdancer:compileDebugRenderscript', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugShaders', FROM_CACHE)
        builder.put(':dasherdancer:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':dasherdancer:extractDebugAnnotations', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugAssets', UP_TO_DATE)
        builder.put(':dasherdancer:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugResources', UP_TO_DATE)
        builder.put(':dasherdancer:generateDebugResValues', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugRFile', FROM_CACHE)
        builder.put(':dasherdancer:generateDebugSources', SUCCESS)
        builder.put(':dasherdancer:javaPreCompileDebug', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':dasherdancer:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':dasherdancer:mergeDebugShaders', FROM_CACHE)
        builder.put(':dasherdancer:packageDebugAssets', FROM_CACHE)
        builder.put(':dasherdancer:packageDebugRenderscript', NO_SOURCE)
        builder.put(':dasherdancer:packageDebugResources', FROM_CACHE)
        builder.put(':dasherdancer:platformAttrExtractor', FROM_CACHE)
        builder.put(':dasherdancer:preBuild', UP_TO_DATE)
        builder.put(':dasherdancer:preDebugBuild', UP_TO_DATE)
        builder.put(':dasherdancer:prepareLintJar', SUCCESS)
        builder.put(':dasherdancer:processDebugJavaRes', NO_SOURCE)
        builder.put(':dasherdancer:processDebugManifest', FROM_CACHE)
        builder.put(':dasherdancer:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':dasherdancer:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':dasherdancer:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':dasherdancer:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':dasherdancer:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':dasherdancer:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':doodles:assembleDebug', SUCCESS)
        builder.put(':doodles:bundleDebug', SUCCESS)
        builder.put(':doodles:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':doodles:compileDebugAidl', FROM_CACHE)
        builder.put(':doodles:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':doodles:compileDebugNdk', NO_SOURCE)
        builder.put(':doodles:compileDebugRenderscript', FROM_CACHE)
        builder.put(':doodles:compileDebugShaders', FROM_CACHE)
        builder.put(':doodles:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':doodles:extractDebugAnnotations', FROM_CACHE)
        builder.put(':doodles:generateDebugAssets', UP_TO_DATE)
        builder.put(':doodles:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':doodles:generateDebugResources', UP_TO_DATE)
        builder.put(':doodles:generateDebugResValues', FROM_CACHE)
        builder.put(':doodles:generateDebugRFile', FROM_CACHE)
        builder.put(':doodles:generateDebugSources', SUCCESS)
        builder.put(':doodles:javaPreCompileDebug', FROM_CACHE)
        builder.put(':doodles:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':doodles:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':doodles:mergeDebugShaders', FROM_CACHE)
        builder.put(':doodles:packageDebugAssets', FROM_CACHE)
        builder.put(':doodles:packageDebugRenderscript', NO_SOURCE)
        builder.put(':doodles:packageDebugResources', FROM_CACHE)
        builder.put(':doodles:platformAttrExtractor', FROM_CACHE)
        builder.put(':doodles:preBuild', UP_TO_DATE)
        builder.put(':doodles:preDebugBuild', UP_TO_DATE)
        builder.put(':doodles:prepareLintJar', SUCCESS)
        builder.put(':doodles:processDebugJavaRes', NO_SOURCE)
        builder.put(':doodles:processDebugManifest', FROM_CACHE)
        builder.put(':doodles:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':doodles:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':doodles:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':doodles:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':doodles:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':doodles:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':presentquest:assembleDebug', SUCCESS)
        builder.put(':presentquest:bundleDebug', SUCCESS)
        builder.put(':presentquest:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':presentquest:compileDebugAidl', FROM_CACHE)
        builder.put(':presentquest:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':presentquest:compileDebugNdk', NO_SOURCE)
        builder.put(':presentquest:compileDebugRenderscript', FROM_CACHE)
        builder.put(':presentquest:compileDebugShaders', FROM_CACHE)
        builder.put(':presentquest:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':presentquest:extractDebugAnnotations', FROM_CACHE)
        builder.put(':presentquest:generateDebugAssets', UP_TO_DATE)
        builder.put(':presentquest:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':presentquest:generateDebugResources', UP_TO_DATE)
        builder.put(':presentquest:generateDebugResValues', FROM_CACHE)
        builder.put(':presentquest:generateDebugRFile', FROM_CACHE)
        builder.put(':presentquest:generateDebugSources', SUCCESS)
        builder.put(':presentquest:javaPreCompileDebug', FROM_CACHE)
        builder.put(':presentquest:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':presentquest:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':presentquest:mergeDebugShaders', FROM_CACHE)
        builder.put(':presentquest:packageDebugAssets', FROM_CACHE)
        builder.put(':presentquest:packageDebugRenderscript', NO_SOURCE)
        builder.put(':presentquest:packageDebugResources', FROM_CACHE)
        builder.put(':presentquest:platformAttrExtractor', FROM_CACHE)
        builder.put(':presentquest:preBuild', UP_TO_DATE)
        builder.put(':presentquest:preDebugBuild', UP_TO_DATE)
        builder.put(':presentquest:prepareLintJar', SUCCESS)
        builder.put(':presentquest:processDebugJavaRes', NO_SOURCE)
        builder.put(':presentquest:processDebugManifest', FROM_CACHE)
        builder.put(':presentquest:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':presentquest:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':presentquest:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':presentquest:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':presentquest:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':presentquest:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':rocketsleigh:assembleDebug', SUCCESS)
        builder.put(':rocketsleigh:bundleDebug', SUCCESS)
        builder.put(':rocketsleigh:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':rocketsleigh:compileDebugAidl', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':rocketsleigh:compileDebugNdk', NO_SOURCE)
        builder.put(':rocketsleigh:compileDebugRenderscript', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugShaders', FROM_CACHE)
        builder.put(':rocketsleigh:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':rocketsleigh:extractDebugAnnotations', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugAssets', UP_TO_DATE)
        builder.put(':rocketsleigh:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugResources', UP_TO_DATE)
        builder.put(':rocketsleigh:generateDebugResValues', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugRFile', FROM_CACHE)
        builder.put(':rocketsleigh:generateDebugSources', SUCCESS)
        builder.put(':rocketsleigh:javaPreCompileDebug', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':rocketsleigh:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':rocketsleigh:mergeDebugShaders', FROM_CACHE)
        builder.put(':rocketsleigh:packageDebugAssets', FROM_CACHE)
        builder.put(':rocketsleigh:packageDebugRenderscript', NO_SOURCE)
        builder.put(':rocketsleigh:packageDebugResources', FROM_CACHE)
        builder.put(':rocketsleigh:platformAttrExtractor', FROM_CACHE)
        builder.put(':rocketsleigh:preBuild', UP_TO_DATE)
        builder.put(':rocketsleigh:preDebugBuild', UP_TO_DATE)
        builder.put(':rocketsleigh:prepareLintJar', SUCCESS)
        builder.put(':rocketsleigh:processDebugJavaRes', NO_SOURCE)
        builder.put(':rocketsleigh:processDebugManifest', FROM_CACHE)
        builder.put(':rocketsleigh:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':rocketsleigh:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':rocketsleigh:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':rocketsleigh:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':rocketsleigh:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':rocketsleigh:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':santa-tracker:assembleDebug', SUCCESS)
        builder.put(':santa-tracker:assembleDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:assembleProductionDebug', SUCCESS)
        builder.put(':santa-tracker:checkDevelopmentDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':santa-tracker:checkProductionDebugManifest', FROM_CACHE)
        builder.put(':santa-tracker:compileDevelopmentDebugAidl', FROM_CACHE)
        builder.put(':santa-tracker:compileDevelopmentDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':santa-tracker:compileDevelopmentDebugNdk', NO_SOURCE)
        builder.put(':santa-tracker:compileDevelopmentDebugRenderscript', FROM_CACHE)
        builder.put(':santa-tracker:compileDevelopmentDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:compileDevelopmentDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':santa-tracker:compileProductionDebugAidl', FROM_CACHE)
        builder.put(':santa-tracker:compileProductionDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':santa-tracker:compileProductionDebugNdk', NO_SOURCE)
        builder.put(':santa-tracker:compileProductionDebugRenderscript', FROM_CACHE)
        builder.put(':santa-tracker:compileProductionDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:compileProductionDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':santa-tracker:createDevelopmentDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':santa-tracker:createProductionDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':santa-tracker:generateDevelopmentDebugAssets', UP_TO_DATE)
        builder.put(':santa-tracker:generateDevelopmentDebugBuildConfig', FROM_CACHE)
        builder.put(':santa-tracker:generateDevelopmentDebugResources', UP_TO_DATE)
        builder.put(':santa-tracker:generateDevelopmentDebugResValues', FROM_CACHE)
        builder.put(':santa-tracker:generateDevelopmentDebugSources', SUCCESS)
        builder.put(':santa-tracker:generateProductionDebugAssets', UP_TO_DATE)
        builder.put(':santa-tracker:generateProductionDebugBuildConfig', FROM_CACHE)
        builder.put(':santa-tracker:generateProductionDebugResources', UP_TO_DATE)
        builder.put(':santa-tracker:generateProductionDebugResValues', FROM_CACHE)
        builder.put(':santa-tracker:generateProductionDebugSources', SUCCESS)
        builder.put(':santa-tracker:javaPreCompileDevelopmentDebug', FROM_CACHE)
        builder.put(':santa-tracker:javaPreCompileProductionDebug', FROM_CACHE)
        builder.put(':santa-tracker:mergeDevelopmentDebugAssets', FROM_CACHE)
        builder.put(':santa-tracker:mergeDevelopmentDebugJniLibFolders', FROM_CACHE)
        builder.put(':santa-tracker:mergeDevelopmentDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:mergeDevelopmentDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:mergeProductionDebugAssets', FROM_CACHE)
        builder.put(':santa-tracker:mergeProductionDebugJniLibFolders', FROM_CACHE)
        builder.put(':santa-tracker:mergeProductionDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:mergeProductionDebugShaders', FROM_CACHE)
        builder.put(':santa-tracker:packageDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:packageProductionDebug', SUCCESS)
        builder.put(':santa-tracker:preBuild', UP_TO_DATE)
        builder.put(':santa-tracker:preDevelopmentDebugBuild', FROM_CACHE)
        builder.put(':santa-tracker:prepareLintJar', SUCCESS)
        builder.put(':santa-tracker:preProductionDebugBuild', FROM_CACHE)
        builder.put(':santa-tracker:processDevelopmentDebugGoogleServices', SUCCESS)
        builder.put(':santa-tracker:processDevelopmentDebugJavaRes', NO_SOURCE)
        builder.put(':santa-tracker:processDevelopmentDebugManifest', FROM_CACHE)
        builder.put(':santa-tracker:processDevelopmentDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:processProductionDebugGoogleServices', SUCCESS)
        builder.put(':santa-tracker:processProductionDebugJavaRes', NO_SOURCE)
        builder.put(':santa-tracker:processProductionDebugManifest', FROM_CACHE)
        builder.put(':santa-tracker:processProductionDebugResources', FROM_CACHE)
        builder.put(':santa-tracker:splitsDiscoveryTaskDevelopmentDebug', FROM_CACHE)
        builder.put(':santa-tracker:splitsDiscoveryTaskProductionDebug', FROM_CACHE)
        builder.put(':santa-tracker:transformClassesWithDexBuilderForDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:transformClassesWithDexBuilderForProductionDebug', SUCCESS)
        builder.put(':santa-tracker:transformClassesWithMultidexlistForProductionDebug', SUCCESS)
        builder.put(':santa-tracker:transformDexArchiveWithDexMergerForDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:transformDexArchiveWithDexMergerForProductionDebug', SUCCESS)
        builder.put(':santa-tracker:transformDexArchiveWithExternalLibsDexMergerForDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:transformNativeLibsWithMergeJniLibsForDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:transformNativeLibsWithMergeJniLibsForProductionDebug', SUCCESS)
        builder.put(':santa-tracker:transformResourcesWithMergeJavaResForDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:transformResourcesWithMergeJavaResForProductionDebug', SUCCESS)
        builder.put(':santa-tracker:validateSigningDevelopmentDebug', SUCCESS)
        builder.put(':santa-tracker:validateSigningProductionDebug', SUCCESS)
        builder.put(':snowdown:assembleDebug', SUCCESS)
        builder.put(':snowdown:bundleDebug', SUCCESS)
        builder.put(':snowdown:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':snowdown:compileDebugAidl', FROM_CACHE)
        builder.put(':snowdown:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':snowdown:compileDebugNdk', NO_SOURCE)
        builder.put(':snowdown:compileDebugRenderscript', FROM_CACHE)
        builder.put(':snowdown:compileDebugShaders', FROM_CACHE)
        builder.put(':snowdown:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':snowdown:extractDebugAnnotations', FROM_CACHE)
        builder.put(':snowdown:generateDebugAssets', UP_TO_DATE)
        builder.put(':snowdown:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':snowdown:generateDebugResources', UP_TO_DATE)
        builder.put(':snowdown:generateDebugResValues', FROM_CACHE)
        builder.put(':snowdown:generateDebugRFile', FROM_CACHE)
        builder.put(':snowdown:generateDebugSources', SUCCESS)
        builder.put(':snowdown:javaPreCompileDebug', FROM_CACHE)
        builder.put(':snowdown:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':snowdown:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':snowdown:mergeDebugShaders', FROM_CACHE)
        builder.put(':snowdown:packageDebugAssets', FROM_CACHE)
        builder.put(':snowdown:packageDebugRenderscript', NO_SOURCE)
        builder.put(':snowdown:packageDebugResources', FROM_CACHE)
        builder.put(':snowdown:platformAttrExtractor', FROM_CACHE)
        builder.put(':snowdown:preBuild', UP_TO_DATE)
        builder.put(':snowdown:preDebugBuild', UP_TO_DATE)
        builder.put(':snowdown:prepareLintJar', SUCCESS)
        builder.put(':snowdown:processDebugJavaRes', NO_SOURCE)
        builder.put(':snowdown:processDebugManifest', FROM_CACHE)
        builder.put(':snowdown:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':snowdown:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':snowdown:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':snowdown:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':snowdown:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':snowdown:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':village:assembleDebug', SUCCESS)
        builder.put(':village:bundleDebug', SUCCESS)
        builder.put(':village:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':village:compileDebugAidl', FROM_CACHE)
        builder.put(':village:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':village:compileDebugNdk', NO_SOURCE)
        builder.put(':village:compileDebugRenderscript', FROM_CACHE)
        builder.put(':village:compileDebugShaders', FROM_CACHE)
        builder.put(':village:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':village:extractDebugAnnotations', FROM_CACHE)
        builder.put(':village:generateDebugAssets', UP_TO_DATE)
        builder.put(':village:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':village:generateDebugResources', UP_TO_DATE)
        builder.put(':village:generateDebugResValues', FROM_CACHE)
        builder.put(':village:generateDebugRFile', FROM_CACHE)
        builder.put(':village:generateDebugSources', SUCCESS)
        builder.put(':village:javaPreCompileDebug', FROM_CACHE)
        builder.put(':village:mergeDebugConsumerProguardFiles', SUCCESS)
        builder.put(':village:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':village:mergeDebugShaders', FROM_CACHE)
        builder.put(':village:packageDebugAssets', FROM_CACHE)
        builder.put(':village:packageDebugRenderscript', NO_SOURCE)
        builder.put(':village:packageDebugResources', FROM_CACHE)
        builder.put(':village:platformAttrExtractor', FROM_CACHE)
        builder.put(':village:preBuild', UP_TO_DATE)
        builder.put(':village:preDebugBuild', UP_TO_DATE)
        builder.put(':village:prepareLintJar', SUCCESS)
        builder.put(':village:processDebugJavaRes', NO_SOURCE)
        builder.put(':village:processDebugManifest', FROM_CACHE)
        builder.put(':village:transformClassesAndResourcesWithPrepareIntermediateJarsForDebug', SUCCESS)
        builder.put(':village:transformClassesAndResourcesWithSyncLibJarsForDebug', SUCCESS)
        builder.put(':village:transformNativeLibsWithIntermediateJniLibsForDebug', SUCCESS)
        builder.put(':village:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':village:transformNativeLibsWithSyncJniLibsForDebug', SUCCESS)
        builder.put(':village:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':wearable:assembleDebug', SUCCESS)
        builder.put(':wearable:checkDebugManifest', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':wearable:compileDebugAidl', FROM_CACHE)
        builder.put(':wearable:compileDebugJavaWithJavac', fixApplied ? FROM_CACHE : SUCCESS)
        builder.put(':wearable:compileDebugNdk', NO_SOURCE)
        builder.put(':wearable:compileDebugRenderscript', FROM_CACHE)
        builder.put(':wearable:compileDebugShaders', FROM_CACHE)
        builder.put(':wearable:compileDebugSources', fixApplied ? UP_TO_DATE : SUCCESS)
        builder.put(':wearable:createDebugCompatibleScreenManifests', FROM_CACHE)
        builder.put(':wearable:generateDebugAssets', UP_TO_DATE)
        builder.put(':wearable:generateDebugBuildConfig', FROM_CACHE)
        builder.put(':wearable:generateDebugResources', UP_TO_DATE)
        builder.put(':wearable:generateDebugResValues', FROM_CACHE)
        builder.put(':wearable:generateDebugSources', SUCCESS)
        builder.put(':wearable:javaPreCompileDebug', FROM_CACHE)
        builder.put(':wearable:mergeDebugAssets', FROM_CACHE)
        builder.put(':wearable:mergeDebugJniLibFolders', FROM_CACHE)
        builder.put(':wearable:mergeDebugResources', FROM_CACHE)
        builder.put(':wearable:mergeDebugShaders', FROM_CACHE)
        builder.put(':wearable:packageDebug', SUCCESS)
        builder.put(':wearable:preBuild', UP_TO_DATE)
        builder.put(':wearable:preDebugBuild', FROM_CACHE)
        builder.put(':wearable:prepareLintJar', SUCCESS)
        builder.put(':wearable:processDebugJavaRes', NO_SOURCE)
        builder.put(':wearable:processDebugManifest', FROM_CACHE)
        builder.put(':wearable:processDebugResources', FROM_CACHE)
        builder.put(':wearable:splitsDiscoveryTaskDebug', FROM_CACHE)
        builder.put(':wearable:transformClassesWithDexBuilderForDebug', SUCCESS)
        builder.put(':wearable:transformDexArchiveWithDexMergerForDebug', SUCCESS)
        builder.put(':wearable:transformDexArchiveWithExternalLibsDexMergerForDebug', SUCCESS)
        builder.put(':wearable:transformNativeLibsWithMergeJniLibsForDebug', SUCCESS)
        builder.put(':wearable:transformResourcesWithMergeJavaResForDebug', SUCCESS)
        builder.put(':wearable:validateSigningDebug', SUCCESS)
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
