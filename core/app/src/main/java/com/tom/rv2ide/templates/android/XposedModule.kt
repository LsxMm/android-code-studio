/*
 *  This file is part of AndroidCodeStudio.
 *
 *  AndroidCodeStudio is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  AndroidCodeStudio is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *   along with AndroidCodeStudio.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tom.rv2ide.templates.android

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tom.androidcodestudio.project.manager.PackageHelper
import com.tom.androidcodestudio.project.manager.ProjectManager
import com.tom.androidcodestudio.project.manager.SdkVersionHelper
import com.tom.androidcodestudio.project.manager.builder.*
import com.tom.androidcodestudio.project.manager.builder.module.*
import com.tom.androidcodestudio.project.manager.builder.toplevel.*
import com.tom.rv2ide.R
import com.tom.rv2ide.templates.*
import com.tom.rv2ide.templates.AtcInterface
import com.tom.rv2ide.templates.preferences.Options
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Creates a ready to build Xposed module project.
 *
 * The generated project contains everything that LSPosed/Xposed needs to recognize it as a module:
 * * `assets/xposed_init` pointing at the hook entry class
 * * the `xposedmodule` / `xposeddescription` / `xposedminversion` manifest entries
 * * the Xposed API on the compile classpath only (`compileOnly`)
 */
class XposedModule : Template {

  override val displayName = "Xposed Module"
  override val templateType = Template.TemplateType.OTHER

  private val projectStructBuilder = ProjectStructBuilder()
  private val fileWriter = ActivityWriter()
  private val topLevelGradleWriter = TopLevelGradleWriter()
  private val settingsGradleWriter = SettingsGradleWriter()
  private val versionCatalogWriter = VersionCatalogWriter()
  private val gradlePropertiesWriter = GradlePropertiesWriter()
  private val moduleGradleWriter = MLGradleWriter()
  private val proguardRulesWriter = ProguardRulesWriter()

  // The wrapper script and the launcher icon resources are shared with the EmptyActivity template
  // so that we do not have to duplicate them for every template.
  private val ASSETS_BASE_PATH = EmptyActivity::class.simpleName!!
  private val ASSETS_RESOURCES_PATH = "$ASSETS_BASE_PATH/resources"
  private val ASSETS_GRADLE_PATH = "$ASSETS_BASE_PATH/gradle"

  private val XPOSED_API_GROUP = "de.robv.android.xposed"
  private val XPOSED_API_VERSION = "82"

  override suspend fun create(
      context: Context,
      listener: AtcInterface.TemplateCreationListener?,
      options: TemplateOptions,
  ) =
      withContext(Dispatchers.IO) {
        try {
          Log.d("XposedModule", "create() called - START")

          withContext(Dispatchers.Main) {
            Toast.makeText(
                    context,
                    context.getString(R.string.creating_template_project, displayName),
                    Toast.LENGTH_SHORT,
                )
                .show()
          }

          val packageHelper =
              PackageHelper.createForProject(context, options.projectName.lowercase() + "_project")
          packageHelper.setPackageIdBlocking(options.packageId)

          val sdkHelper = SdkVersionHelper.getInstance(context)
          sdkHelper.setAllSdkVersionsBlocking(options.minSdk, 34, 34)

          val projectRoot = File(options.saveLocation, options.projectName)
          Log.d("XposedModule", "Project root: ${projectRoot.absolutePath}")

          projectRoot.mkdirs()

          val structResult =
              projectStructBuilder.buildProjectStructure(
                  moduleName = "app",
                  projectType =
                      if (options.languageType == LanguageType.KOTLIN)
                          com.tom.androidcodestudio.project.manager.builder.ProjectType.KOTLIN
                      else com.tom.androidcodestudio.project.manager.builder.ProjectType.JAVA,
                  packageId = packageHelper.getPackageId(),
                  baseDir = projectRoot,
                  hasLayout = false,
              )

          if (!structResult.success) {
            Log.e("XposedModule", "Structure creation failed: ${structResult.message}")
            withContext(Dispatchers.Main) {
              listener?.onTemplateCreated(false, structResult.message)
            }
            return@withContext
          }

          copyWrapperFiles(context, projectRoot)

          val versions = buildList {
            add(
                catalogVersion {
                  name("agp")
                  version(ANDROID_GRADLE_PLUGIN_VERSION)
                }
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogVersion {
                    name("kotlin")
                    version(KOTLIN_VERSION)
                  }
              )
              add(
                  catalogVersion {
                    name("coreKtx")
                    version(ANDROIDX_CORE_KTEXT_VERSION)
                  }
              )
            } else {
              add(
                  catalogVersion {
                    name("core")
                    version(ANDROIDX_CORE_VERSION)
                  }
              )
            }
            // The launcher icon resources (themes.xml) are shared with the EmptyActivity
            // template and are based on Material3.
            add(
                catalogVersion {
                  name("material")
                  version(GOOGLE_MATERIAL_COMPONENTS_VERSION)
                }
            )
          }

          val plugins = buildList {
            add(
                catalogPlugin {
                  alias("android-application")
                  id("com.android.application")
                  versionRef("agp")
                }
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogPlugin {
                    alias("kotlin-android")
                    id("org.jetbrains.kotlin.android")
                    versionRef("kotlin")
                  }
              )
            }
          }

          val libraries = buildList {
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  catalogLibrary {
                    alias("androidx-core-ktx")
                    group("androidx.core")
                    name("core-ktx")
                    versionRef("coreKtx")
                  }
              )
            } else {
              add(
                  catalogLibrary {
                    alias("androidx-core")
                    group("androidx.core")
                    name("core")
                    versionRef("core")
                  }
              )
            }
            add(
                catalogLibrary {
                  alias("material")
                  group("com.google.android.material")
                  name("material")
                  versionRef("material")
                }
            )
          }

          val gradleDir = File(projectRoot, "gradle")
          gradleDir.mkdirs()
          versionCatalogWriter.writeToFile(gradleDir, versions, plugins, libraries)

          val topLevelPlugins = buildList {
            add(
                com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePlugin(
                    id = "android.application",
                    version = null,
                    apply = false,
                    useAlias = true,
                )
            )
            if (options.languageType == LanguageType.KOTLIN) {
              add(
                  com.tom.androidcodestudio.project.manager.builder.toplevel.GradlePlugin(
                      id = "kotlin.android",
                      version = null,
                      apply = false,
                      useAlias = true,
                  )
              )
            }
          }
          topLevelGradleWriter.writeToFile(
              projectRoot,
              if (options.useKts)
                  com.tom.androidcodestudio.project.manager.builder.toplevel.GradleFileType.KTS
              else com.tom.androidcodestudio.project.manager.builder.toplevel.GradleFileType.GROOVY,
              topLevelPlugins,
          )

          // The Xposed API is not published on Maven Central, it lives in its own repository.
          val dependencyResolutionBody =
              if (options.useKts)
                  """
                      repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                      repositories {
                          google()
                          mavenCentral()
                          maven { url = uri("https://api.xposed.info/") }
                      }
                  """
                      .trimIndent()
              else
                  """
                      repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
                      repositories {
                          google()
                          mavenCentral()
                          maven { url 'https://api.xposed.info/' }
                      }
                  """
                      .trimIndent()

          val settingsConfig = settingsGradleConfig {
            pluginManagement(
                if (options.useKts) {
                  RepositoryPresets.STANDARD_KTS
                } else {
                  RepositoryPresets.STANDARD_GROOVY
                }
            )
            dependencyResolution(dependencyResolutionBody)
            rootProjectName(options.projectName)
            include(":app")
          }
          settingsGradleWriter.writeToFile(
              projectRoot,
              if (options.useKts) {
                SettingsGradleFileType.KTS
              } else {
                SettingsGradleFileType.GROOVY
              },
              settingsConfig,
          )

          gradlePropertiesWriter.writeToFile(projectRoot, GradlePropertiesPresets.STANDARD_ANDROID)

          val moduleConfig = moduleGradleConfig {
            addPlugin(
                com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin(
                    "alias",
                    "libs.plugins.android.application",
                )
            )
            if (options.languageType == LanguageType.KOTLIN) {
              addPlugin(
                  com.tom.androidcodestudio.project.manager.builder.module.GradlePlugin(
                      "alias",
                      "libs.plugins.kotlin.android",
                  )
              )
            } else {
              enableKotlinOptions(false)
            }
            namespace(packageHelper.getPackageId())
            compileSdk(PROJECTS_COMPILE_SDK_VERSION)
            defaultConfig(
                DefaultConfig(
                    applicationId = packageHelper.getPackageId(),
                    minSdk = Options.OPT_MIN_SDK,
                    targetSdk = 34,
                    versionCode = 1,
                    versionName = "1.0",
                )
            )
            javaVersion(JavaVersion.VERSION_17)
            if (options.languageType == LanguageType.KOTLIN) {
              addDependency(GradleDependency("implementation(libs.androidx.core.ktx)"))
            } else {
              addDependency(GradleDependency("implementation(libs.androidx.core)"))
            }
            addDependency(GradleDependency("implementation(libs.material)"))
            // The Xposed API must only be compiled against, it is provided by the framework at
            // runtime. Bundling it would break the module.
            addDependency(
                GradleDependency(
                    """compileOnly("$XPOSED_API_GROUP:api:$XPOSED_API_VERSION")"""
                )
            )
          }

          val appDir = File(projectRoot, "app")
          val packageId = packageHelper.getPackageId()
          val entryClassName = "XposedEntry"

          moduleGradleWriter.writeToFile(
              appDir,
              if (options.useKts)
                  com.tom.androidcodestudio.project.manager.builder.module.GradleFileType.KTS
              else com.tom.androidcodestudio.project.manager.builder.module.GradleFileType.GROOVY,
              moduleConfig,
          )

          proguardRulesWriter.writeToFile(appDir, ProguardRulesPresets.DEFAULT_ANDROID)

          // The entry point is instantiated reflectively by the framework, so it must survive
          // obfuscation.
          File(appDir, "proguard-rules.pro").appendText(
              "\n# Keep the Xposed entry point (instantiated reflectively by the framework)\n" +
                  "-keep class $packageId.$entryClassName { *; }\n"
          )

          // Hook entry class
          val entryContent =
              if (options.languageType == LanguageType.KOTLIN)
                  kotlinEntrySource(packageId, entryClassName)
              else javaEntrySource(packageId, entryClassName)

          val sourceDir =
              File(
                  projectRoot,
                  "app/src/main/${options.languageType.dirName}/${packageId.replace('.', '/')}",
              )
          sourceDir.mkdirs()
          fileWriter.createFile(
              sourceDir,
              entryClassName,
              options.languageType.extension,
              entryContent,
          )

          // assets/xposed_init tells Xposed which class implements the hook
          val assetsDir = File(projectRoot, "app/src/main/assets")
          assetsDir.mkdirs()
          fileWriter.createFile(
              assetsDir,
              "xposed_init",
              "",
              "$packageId.$entryClassName\n",
          )

          // AndroidManifest with the Xposed module declarations
          val manifestContent =
              """
                  <?xml version="1.0" encoding="utf-8"?>
                  <manifest xmlns:android="http://schemas.android.com/apk/res/android">

                      <application
                          android:allowBackup="true"
                          android:icon="@mipmap/ic_launcher"
                          android:label="@string/app_name"
                          android:roundIcon="@mipmap/ic_launcher_round"
                          android:supportsRtl="true"
                          android:theme="@style/AppTheme">

                          <!-- Required by Xposed/LSPosed to treat this APK as a module -->
                          <meta-data
                              android:name="xposedmodule"
                              android:value="true" />
                          <meta-data
                              android:name="xposeddescription"
                              android:value="@string/xposed_description" />
                          <meta-data
                              android:name="xposedminversion"
                              android:value="$XPOSED_API_VERSION" />

                      </application>

                  </manifest>
              """
                  .trimIndent()

          val manifestDir = File(projectRoot, "app/src/main")
          fileWriter.createFile(manifestDir, "AndroidManifest", "xml", manifestContent)

          val stringsContent =
              """
                  <resources>
                      <string name="app_name">${options.projectName}</string>
                      <string name="xposed_description">${options.projectName} - Xposed module</string>
                  </resources>
              """
                  .trimIndent()

          val valuesDir = File(projectRoot, "app/src/main/res/values")
          fileWriter.createFile(valuesDir, "strings", "xml", stringsContent)

          copyResourceFiles(context, projectRoot)

          writeReadme(projectRoot, options.projectName, packageId)

          val projectManager = ProjectManager.getInstance(context)
          val projectInfo =
              projectManager.createTemplateProjectInfo(
                  projectName = options.projectName,
                  projectDir = projectRoot.absolutePath,
                  projectType = displayName,
              )
          projectManager.addProjectBlocking(projectInfo)

          Log.d("XposedModule", "Project created successfully")

          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                true,
                context.getString(R.string.template_created_success, projectRoot.absolutePath),
            )
            listener?.onTemplateCreated(true, "", projectRoot)
          }
        } catch (e: Exception) {
          Log.e("XposedModule", "Error creating project", e)
          withContext(Dispatchers.Main) {
            listener?.onTemplateCreated(
                false,
                context.getString(R.string.template_created_error, e.message ?: ""),
            )
          }
        }
      }

  private fun kotlinEntrySource(packageId: String, entryClassName: String) =
      """
          package $packageId

          import de.robv.android.xposed.IXposedHookLoadPackage
          import de.robv.android.xposed.XC_MethodHook
          import de.robv.android.xposed.XposedBridge
          import de.robv.android.xposed.XposedHelpers
          import de.robv.android.xposed.callbacks.XC_LoadPackage

          /**
           * Entry point of this Xposed module.
           *
           * Keep the class name in sync with `app/src/main/assets/xposed_init`.
           */
          class $entryClassName : IXposedHookLoadPackage {

            override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
              // Only hook the app we are interested in, otherwise every process gets hooked.
              if (lpparam.packageName != TARGET_PACKAGE) return

              XposedBridge.log("[${'$'}{TAG}] loaded into ${'$'}{lpparam.packageName}")

              try {
                XposedHelpers.findAndHookMethod(
                    TARGET_ACTIVITY,
                    lpparam.classLoader,
                    "onCreate",
                    android.os.Bundle::class.java,
                    object : XC_MethodHook() {
                      override fun afterHookedMethod(param: MethodHookParam) {
                        XposedBridge.log("[${'$'}{TAG}] ${'$'}TARGET_ACTIVITY.onCreate() called")
                      }
                    },
                )
              } catch (e: Throwable) {
                XposedBridge.log("[${'$'}{TAG}] hook failed: ${'$'}{e.message}")
              }
            }

            companion object {
              private const val TAG = "$packageId"

              // TODO: change these to the app/activity you want to hook
              private const val TARGET_PACKAGE = "com.example.target"
              private const val TARGET_ACTIVITY = "com.example.target.MainActivity"
            }
          }
      """
          .trimIndent()

  private fun javaEntrySource(packageId: String, entryClassName: String) =
      """
          package $packageId;

          import android.os.Bundle;
          import de.robv.android.xposed.IXposedHookLoadPackage;
          import de.robv.android.xposed.XC_MethodHook;
          import de.robv.android.xposed.XposedBridge;
          import de.robv.android.xposed.XposedHelpers;
          import de.robv.android.xposed.callbacks.XC_LoadPackage;

          /**
           * Entry point of this Xposed module.
           *
           * Keep the class name in sync with {@code app/src/main/assets/xposed_init}.
           */
          public class $entryClassName implements IXposedHookLoadPackage {

            private static final String TAG = "$packageId";

            // TODO: change these to the app/activity you want to hook
            private static final String TARGET_PACKAGE = "com.example.target";
            private static final String TARGET_ACTIVITY = "com.example.target.MainActivity";

            @Override
            public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
              // Only hook the app we are interested in, otherwise every process gets hooked.
              if (!TARGET_PACKAGE.equals(lpparam.packageName)) {
                return;
              }

              XposedBridge.log("[" + TAG + "] loaded into " + lpparam.packageName);

              try {
                XposedHelpers.findAndHookMethod(
                    TARGET_ACTIVITY,
                    lpparam.classLoader,
                    "onCreate",
                    Bundle.class,
                    new XC_MethodHook() {
                      @Override
                      protected void afterHookedMethod(MethodHookParam param) {
                        XposedBridge.log("[" + TAG + "] " + TARGET_ACTIVITY + ".onCreate() called");
                      }
                    });
              } catch (Throwable t) {
                XposedBridge.log("[" + TAG + "] hook failed: " + t.getMessage());
              }
            }
          }
      """
          .trimIndent()

  private fun writeReadme(projectRoot: File, projectName: String, packageId: String) {
    val readme =
        """
            # $projectName

            一个由 Android Code Studio 生成的 Xposed 模块工程。

            ## 目录说明

            | 路径 | 作用 |
            | --- | --- |
            | `app/src/main/assets/xposed_init` | 声明钩子入口类，内容为 `$packageId.XposedEntry` |
            | `app/src/main/AndroidManifest.xml` | 包含 `xposedmodule` / `xposeddescription` / `xposedminversion` 声明 |
            | `app/src/main/java/.../XposedEntry` | 入口类，实现 `IXposedHookLoadPackage` |

            ## 使用步骤

            1. 修改 `XposedEntry` 里的 `TARGET_PACKAGE` 与 `TARGET_ACTIVITY`，改成你要 Hook 的目标。
            2. 构建 APK：`./gradlew assembleDebug`
            3. 在 Xposed / LSPosed 管理器中启用本模块，然后重启目标应用。
            4. 日志可查看 `XposedBridge.log` 输出（LSPosed 中在“日志”页查看）。

            ## 注意

            * Xposed API 使用 `compileOnly` 引入，不会被打进 APK，这是正确做法。
            * 修改入口类名时，请同步修改 `assets/xposed_init`。
        """
            .trimIndent()

    fileWriter.createFile(projectRoot, "README", "md", readme)
  }

  /** Copy wrapper files (gradlew, gradlew.bat, gradle/wrapper folder) from assets to project root */
  private fun copyWrapperFiles(context: Context, projectRoot: File) {
    try {
      Log.d("XposedModule", "Copying wrapper files...")

      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew", File(projectRoot, "gradlew"))
      File(projectRoot, "gradlew").setExecutable(true, false)

      copyAssetFile(context, "$ASSETS_GRADLE_PATH/gradlew.bat", File(projectRoot, "gradlew.bat"))

      val wrapperDestDir = File(projectRoot, "gradle/wrapper")
      copyAssetFolder(context, "$ASSETS_GRADLE_PATH/wrapper", wrapperDestDir)

      Log.d("XposedModule", "Wrapper files copied successfully")
    } catch (e: Exception) {
      Log.e("XposedModule", "Error copying wrapper files", e)
      // Don't throw, just log - wrapper files are optional
    }
  }

  /** Copy resource files from assets/EmptyActivity/resources to app/src/main/res */
  private fun copyResourceFiles(context: Context, projectRoot: File) {
    try {
      Log.d("XposedModule", "Copying resource files...")

      val resDestDir = File(projectRoot, "app/src/main/res")
      copyAssetFolder(context, ASSETS_RESOURCES_PATH, resDestDir)

      Log.d("XposedModule", "Resource files copied successfully")
    } catch (e: Exception) {
      Log.e("XposedModule", "Error copying resource files", e)
      // Don't throw, just log - additional resources are optional
    }
  }

  /** Recursively copy a folder from assets to destination */
  private fun copyAssetFolder(context: Context, assetPath: String, destDir: File) {
    try {
      val assetManager = context.assets
      val files = assetManager.list(assetPath) ?: emptyArray()

      if (files.isEmpty()) {
        // It's a file, not a directory
        copyAssetFile(context, assetPath, destDir)
      } else {
        // It's a directory
        destDir.mkdirs()

        for (fileName in files) {
          val subAssetPath = "$assetPath/$fileName"
          val subDestFile = File(destDir, fileName)
          copyAssetFolder(context, subAssetPath, subDestFile)
        }
      }
    } catch (e: Exception) {
      Log.e("XposedModule", "Error copying asset folder: $assetPath", e)
    }
  }

  /** Copy a single file from assets to destination */
  private fun copyAssetFile(context: Context, assetPath: String, destFile: File) {
    try {
      destFile.parentFile?.mkdirs()
      val inputStream: InputStream = context.assets.open(assetPath)
      val outputStream = FileOutputStream(destFile)

      inputStream.use { input -> outputStream.use { output -> input.copyTo(output) } }

      Log.d("XposedModule", "Copied: $assetPath -> ${destFile.absolutePath}")
    } catch (e: Exception) {
      Log.e("XposedModule", "Error copying file: $assetPath", e)
      throw e
    }
  }
}
