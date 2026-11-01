package com.tom.rv2ide.templates

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment as AndroidEnvironment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.provider.DocumentsContractCompat
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialSharedAxis
import com.tom.androidcodestudio.project.manager.builder.LanguageType
import com.tom.rv2ide.R
import com.tom.rv2ide.activities.FolderPickerActivity
import com.tom.rv2ide.activities.IDEConfigurations
import com.tom.rv2ide.databinding.DialogAtcWizardBinding
import com.tom.rv2ide.templates.android.Template
import com.tom.rv2ide.templates.android.TemplateOptions
import com.tom.rv2ide.templates.android.TemplateRegistry
import com.tom.rv2ide.templates.android.etc.NativeCpp.Check
import com.tom.rv2ide.templates.preferences.Options
import com.tom.rv2ide.templates.preferences.WizardPreferences
import com.tom.rv2ide.utils.Environment
import com.tom.rv2ide.utils.ProjectNameValidator
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AtcWizardDialog : BottomSheetDialogFragment() {

  private var listener: AtcInterface.TemplateCreationListener? = null
  private var selectedTemplate: Template? = null
  private var _binding: DialogAtcWizardBinding? = null
  private val binding
    get() = _binding!!

  fun init(listener: AtcInterface.TemplateCreationListener?) {
    this.listener = listener
  }

  override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
    val dialog = BottomSheetDialog(requireContext(), theme)
    val ctx = requireContext()

    _binding =
        DataBindingUtil.inflate(LayoutInflater.from(ctx), R.layout.dialog_atc_wizard, null, false)

    setupSwitches()
    setupInputs(ctx)
    setupTemplatesGrid(ctx)
    setupButtons(ctx)

    dialog.setContentView(binding.root)
    return dialog
  }

  private fun setupSwitches() {
    with(binding) {
      useCMakeSwitch.visibility = View.GONE
      useCMakeSwitch.isChecked = Options.OPT_BUILD_SYSTEM_USE_CMAKE
      useKtsSwitch.isChecked = Options.OPT_USE_GRADLE_KTS

      useCMakeSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_BUILD_SYSTEM_USE_CMAKE = isChecked
        if (isChecked) validateAndSelectCMake()
      }

      useKtsSwitch.setOnCheckedChangeListener { _, isChecked ->
        Options.OPT_USE_GRADLE_KTS = isChecked
      }

      ndkVersionButton.visibility = View.GONE
      ndkVersionButton.setOnClickListener { showNdkVersionPicker(requireContext()) }
    }
  }

  private fun setupInputs(ctx: Context) {
    val lastSaveLocation = WizardPreferences.getLastSaveLocation(ctx)
    binding.saveLocationInput.setText(lastSaveLocation ?: Environment.PROJECTS_DIR.absolutePath)

    binding.projectNameInput.addTextChangedListener(
        SimpleTextWatcher {
          updatePackageNameFromProject(it)
          validateProjectName()
        }
    )

    binding.saveLocationInput.addTextChangedListener(SimpleTextWatcher { validateProjectName() })

    binding.saveLocationLayout.setEndIconOnClickListener {
      (activity as? FragmentActivity)?.let { act ->
        FolderPickerActivity.onFolderPicked = { uriStr ->
          val path = SafResolver.resolveToPath(ctx, uriStr)
          binding.saveLocationInput.setText(path)
          WizardPreferences.setLastSaveLocation(ctx, path)
        }
        act.startActivity(Intent(act, FolderPickerActivity::class.java))
      }
    }

    setupDropdowns(ctx)
  }

  private fun setupDropdowns(ctx: Context) {
    val languageItems = arrayOf(ctx.getString(R.string.kotlin), ctx.getString(R.string.java))
    binding.languageInput.apply {
      setSimpleItems(languageItems)
      setText(languageItems[0], false)
      setOnClickListener { showDropDown() }
    }

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val defIdx = sdkValues.indexOfFirst { it.api == 21 }.coerceAtLeast(0)
    Options.OPT_MIN_SDK = sdkValues.getOrNull(defIdx)?.api ?: 21
    binding.minSdkInput.apply {
      setSimpleItems(minSdkDisplay)
      setText(minSdkDisplay[defIdx], false)
      setOnClickListener { showDropDown() }
      setOnItemClickListener { _, _, position, _ ->
        Options.OPT_MIN_SDK = sdkValues.getOrNull(position)?.api ?: 21
      }
    }

    val nativeLangValues = arrayOf("C++", "C")
    binding.nativeLanguageInput.apply {
      setSimpleItems(nativeLangValues)
      setText(nativeLangValues[0], false)
      setOnClickListener { showDropDown() }
      setOnItemClickListener { _, _, position, _ ->
        Options.OPT_NATIVE_LANGUAGE = if (position == 1) "c" else "cpp"
      }
    }
  }

  private fun setupTemplatesGrid(ctx: Context) {
    val templates = TemplateRegistry.getAllTemplates()
    binding.templatesGrid.layoutManager = GridLayoutManager(ctx, 2)
    binding.templatesGrid.adapter =
        TemplateAdapter(ctx, templates) { template ->
          selectedTemplate = template
          template.configureOptions()

          if (template.javaClass.simpleName == "NativeCpp") {
            validateNativeTemplate(ctx)
          } else {
            proceedToOptionsPage(ctx)
          }
        }
  }

  private fun setupButtons(ctx: Context) {
    binding.backButton.setOnClickListener {
      binding.root.post {
        SheetTransitions.slide(
            binding.wizardContainer,
            binding.pageOptions,
            binding.pageTemplates,
            MaterialSharedAxis.X,
            false,
        )
        binding.backButton.visibility = View.GONE
        binding.createButton.visibility = View.GONE
      }
    }

    binding.createButton.setOnClickListener { createProject(ctx) }
  }

  private fun validateAndSelectCMake() {
    val cmakeVersions = Check.getAllCMakeVersions()
    if (cmakeVersions.isEmpty()) {
      showAlert(
          getString(R.string.atc_cmake_not_found_title),
          getString(R.string.atc_cmake_not_found_message),
      ) {
        startActivity(Intent(requireContext(), IDEConfigurations::class.java))
      }
      binding.useCMakeSwitch.isChecked = false
    } else {
      showCMakeVersionPicker(requireContext(), cmakeVersions)
    }
  }

  private fun validateNativeTemplate(ctx: Context) {
    val progressDialog =
        showProgress(
            getString(R.string.atc_checking_ndk_title),
            getString(R.string.atc_checking_ndk_message),
        )

    CoroutineScope(Dispatchers.IO).launch {
      val hasNdk = Check.isAtLeastOneInstalled()
      val highestNdk = if (hasNdk) Check.getHighestNdkVersion() else null
      val isValid = highestNdk?.let { Check.validateNdkVersion(it) } ?: false

      withContext(Dispatchers.Main) {
        progressDialog.dismiss()

        when {
          !hasNdk -> showNdkError(ctx, getString(R.string.error_ndk_not_found_or_incompatible))
          !isValid ->
              showNdkError(ctx, getString(R.string.atc_ndk_invalid_version, highestNdk.toString()))
          else -> {
            Options.OPT_SELECTED_NDK_VERSION = highestNdk
            proceedToOptionsPage(ctx)
          }
        }
      }
    }
  }

  private fun showNdkError(ctx: Context, message: String) {
    showAlert(getString(R.string.native_error_title), message) {
      startActivity(Intent(ctx, IDEConfigurations::class.java))
    }
  }

  private fun proceedToOptionsPage(ctx: Context) {
    binding.root.post {
      val templateName = "My${selectedTemplate?.displayName?.replace(" ", "")}" ?: "MyProject"
      // The package suffix must stay a valid Java identifier: template names such as
      // "Native C++" or non-ASCII names would otherwise produce an invalid package id.
      val packageSuffix =
          "my" +
              (selectedTemplate?.displayName ?: "project")
                  .lowercase()
                  .replace("[^a-z0-9_]".toRegex(), "")
                  .ifEmpty { "project" }

      binding.projectNameInput.setText(templateName)
      binding.packageNameInput.setText("com.example.$packageSuffix")

      val isNative = Options.OPT_IS_NATIVE_CPP
      binding.useCMakeSwitch.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.nativeLanguageInputLayout.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.visibility = if (isNative) View.VISIBLE else View.GONE
      binding.ndkVersionButton.text =
          getString(R.string.atc_ndk_label, Options.OPT_SELECTED_NDK_VERSION ?: getString(R.string.atc_auto))

      SheetTransitions.slide(
          binding.wizardContainer,
          binding.pageTemplates,
          binding.pageOptions,
          MaterialSharedAxis.X,
          true,
      )
      binding.backButton.visibility = View.VISIBLE
      binding.createButton.visibility = View.VISIBLE
    }
  }

  private fun createProject(ctx: Context) {
    val proj =
        binding.projectNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: selectedTemplate?.displayName?.replace(" ", "")
            ?: "MyProject"
    val pkg =
        binding.packageNameInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: "com.example.${selectedTemplate?.displayName?.replace(" ", ".")?.lowercase() ?: "myproject"}"

    var lang =
        if (binding.languageInput.text?.toString()?.lowercase()?.startsWith("java") == true)
            LanguageType.JAVA
        else LanguageType.KOTLIN

    val sdkValues = Sdk.values()
    val minSdkDisplay = sdkValues.map { it.displayName() }.toTypedArray()
    val selectedIdx = minSdkDisplay.indexOf(binding.minSdkInput.text?.toString()).coerceAtLeast(0)
    val sdkApi = Options.OPT_MIN_SDK ?: 21
    
    val savePath =
        binding.saveLocationInput.text?.toString()?.trim().takeUnless { it.isNullOrBlank() }
            ?: Environment.PROJECTS_DIR.absolutePath
    val projectDir = File(savePath, proj)

    if (projectDir.exists()) {
      showAlert(
          getString(R.string.atc_project_exists_title),
          getString(R.string.atc_project_exists_message, proj),
      )
      return
    }

    if (
        Options.OPT_IS_NATIVE_GAME_ACTIVITY == true &&
            WizardPreferences.getLastSaveLocation(ctx) != Environment.AT_ACSHOME_PROJECTS.toString()
    ) {
      requireAcsHomeProjectsDir()
      return
    }

    WizardPreferences.setLastSaveLocation(ctx, savePath)
    WizardPreferences.addRecentProject(ctx, projectDir.absolutePath)

    selectedTemplate?.let { t ->
      if (t.javaClass.simpleName.contains("Compose", ignoreCase = true)) {
        lang = LanguageType.KOTLIN
      }

      CoroutineScope(Dispatchers.Main).launch {
        try {
          t.create(
              ctx,
              listener,
              TemplateOptions(proj, pkg, lang, sdkApi, Options.OPT_USE_GRADLE_KTS, File(savePath)),
          )
        } catch (e: Exception) {
          listener?.onTemplateCreated(false, "Error: ${e.message}")
        }
      }
    } ?: listener?.onCreationCancelled()

    dismiss()
  }

  private fun requireAcsHomeProjectsDir() {
    showAlert(
        getString(R.string.atc_invalid_location_title),
        getString(R.string.atc_invalid_location_message),
        getString(R.string.atc_auto_switch),
    ) {
      binding.saveLocationInput.setText(Environment.AT_ACSHOME_PROJECTS.toString())
      WizardPreferences.setLastSaveLocation(
          requireContext(),
          Environment.AT_ACSHOME_PROJECTS.toString(),
      )
    }
  }

  private fun validateProjectName() {
    val projectName = binding.projectNameInput.text?.toString()?.trim().orEmpty()
    val saveLocation = binding.saveLocationInput.text?.toString()?.trim().orEmpty()

    if (projectName.isEmpty()) {
      binding.projectNameLayout.error = null
      return
    }

    val projectDir = File(saveLocation, projectName)

    when {
      projectDir.exists() -> {
        binding.projectNameLayout.error =
            getString(R.string.project_name_exists_message, projectName)
        binding.createButton.isEnabled = false
      }
      // Any Unicode name is accepted. Only characters that are illegal in a file name (or that
      // would confuse the build system) are rejected, so names like "我的应用" work fine.
      !ProjectNameValidator.isValid(projectName) -> {
        binding.projectNameLayout.error = getString(R.string.invalid_project_name_message)
        binding.createButton.isEnabled = false
      }
      else -> {
        binding.projectNameLayout.error = null
        binding.createButton.isEnabled = true
      }
    }
  }

  private fun updatePackageNameFromProject(projectName: CharSequence?) {
    val current = binding.packageNameInput.text?.toString()?.trim().orEmpty()
    if (current.isNotEmpty() && current.contains('.')) {
      val segs = current.split('.').toMutableList()
      // Keep the package valid: only ASCII letters, digits and underscores are allowed, and a
      // segment must start with a letter. A fully non-ASCII project name leaves the current
      // segment untouched instead of clobbering it with a meaningless "app".
      val sanitized =
          projectName
              ?.toString()
              ?.trim()
              ?.lowercase()
              ?.replace("[^a-z0-9_]".toRegex(), "")
              .orEmpty()
      if (sanitized.isEmpty()) return
      val segment =
          if (sanitized.first().isLetter()) {
            sanitized
          } else {
            "app$sanitized"
          }
      segs[segs.lastIndex] = segment
      binding.packageNameInput.setText(segs.joinToString("."))
    }
  }

  private fun showNdkVersionPicker(ctx: Context) {
    val versions = Check.getAllNdkVersions()
    if (versions.isEmpty()) {
      showAlert(
          getString(R.string.atc_no_ndk_title),
          getString(R.string.atc_no_ndk_message),
      )
      return
    }

    val versionLabels =
        versions.map { "$it ${if (Check.validateNdkVersion(it)) "✓" else "✗"}" }.toTypedArray()
    val currentIndex = versions.indexOf(Options.OPT_SELECTED_NDK_VERSION).coerceAtLeast(0)

    MaterialAlertDialogBuilder(ctx)
        .setTitle(getString(R.string.atc_select_ndk_title))
        .setSingleChoiceItems(versionLabels, currentIndex) { dialog, which ->
          val selectedVersion = versions[which]
          if (Check.validateNdkVersion(selectedVersion)) {
            Options.OPT_SELECTED_NDK_VERSION = selectedVersion
            binding.ndkVersionButton.text =
                getString(R.string.atc_ndk_label, selectedVersion)
            dialog.dismiss()
          } else {
            Toast.makeText(
                    ctx,
                    getString(R.string.atc_invalid_ndk_toast, selectedVersion),
                    Toast.LENGTH_SHORT,
                )
                .show()
          }
        }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
  }

  private fun showCMakeVersionPicker(ctx: Context, versions: List<String>) {
    val versionLabels =
        versions
            .map { "$it ${if (Check.validateCMakeVersion(it) != null) "✓" else "✗"}" }
            .toTypedArray()

    MaterialAlertDialogBuilder(ctx)
        .setTitle(getString(R.string.atc_select_cmake_title))
        .setSingleChoiceItems(versionLabels, 0) { dialog, which ->
          val selectedVersion = versions[which]
          Check.validateCMakeVersion(selectedVersion)?.let { path ->
            Options.OPT_CMAKE_PATH = path
            Toast.makeText(
                    ctx,
                    getString(R.string.atc_cmake_selected_toast, selectedVersion),
                    Toast.LENGTH_SHORT,
                )
                .show()
            dialog.dismiss()
          }
              ?: Toast.makeText(
                      ctx,
                      getString(R.string.atc_invalid_cmake_toast, selectedVersion),
                      Toast.LENGTH_SHORT,
                  )
                  .show()
        }
        .setNegativeButton(getString(R.string.cancel)) { _, _ ->
          binding.useCMakeSwitch.isChecked = false
        }
        .show()
  }

  private fun showAlert(
      title: String,
      message: String,
      positiveText: String = getString(android.R.string.ok),
      onPositive: (() -> Unit)? = null,
  ) {
    MaterialAlertDialogBuilder(requireContext())
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(positiveText) { _, _ -> onPositive?.invoke() }
        .setNegativeButton(getString(R.string.cancel), null)
        .show()
  }

  private fun showProgress(title: String, message: String) =
      MaterialAlertDialogBuilder(requireContext())
          .setTitle(title)
          .setMessage(message)
          .setCancelable(false)
          .create()
          .apply { show() }

  override fun onDestroyView() {
    super.onDestroyView()
    _binding = null
  }
}

class TemplateAdapter(
    private val ctx: Context,
    private val templates: List<Template>,
    private val onTemplateClick: (Template) -> Unit,
) : RecyclerView.Adapter<TemplateVH>() {

  override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TemplateVH {
    val displayMetrics = ctx.resources.displayMetrics
    val screenWidthDp = displayMetrics.widthPixels / displayMetrics.density
    android.util.Log.d("TemplateAdapter", "Screen Width DP: $screenWidthDp")
    
    val card =
        MaterialCardView(ctx).apply {
          layoutParams =
              ViewGroup.MarginLayoutParams(
                      ViewGroup.LayoutParams.MATCH_PARENT,
                      ViewGroup.LayoutParams.WRAP_CONTENT,
                  )
                  .apply { setMargins(8.dp, 8.dp, 8.dp, 8.dp) }
          radius = 20.dp.toFloat()
          isClickable = true
          isFocusable = true
          strokeWidth = 0
          elevation = 1.dp.toFloat()
        }

    val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    val title =
        TextView(ctx).apply {
          textSize = 14f
          setPadding(12.dp, 12.dp, 12.dp, 8.dp)
          gravity = android.view.Gravity.CENTER
        }
    
    val image =
        ImageView(ctx).apply {
          scaleType = ImageView.ScaleType.CENTER_CROP
          
          if (screenWidthDp >= 600) {
            android.util.Log.d("TemplateAdapter", "Using small size for large screen")
            layoutParams = LinearLayout.LayoutParams(100.dp, 100.dp).apply {
              gravity = android.view.Gravity.CENTER
            }
          } else {
            android.util.Log.d("TemplateAdapter", "Using normal size")
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 160.dp)
          }
        }

    layout.addView(title)
    layout.addView(image)
    card.addView(layout)
    return TemplateVH(card, title, image)
  }

  override fun onBindViewHolder(holder: TemplateVH, position: Int) {
    val template = templates[position]
    holder.title.text = localizedTemplateName(ctx, template)

    val resId =
        ctx.resources.getIdentifier(
            template.javaClass.simpleName.replace(Regex("([a-z])([A-Z])"), "$1_$2").lowercase(),
            "drawable",
            ctx.packageName,
        )
    holder.image.setImageResource(if (resId != 0) resId else android.R.drawable.ic_menu_gallery)
    holder.card.setOnClickListener { onTemplateClick(template) }
  }

  override fun getItemCount() = templates.size
}

/**
 * Returns the localized label of a template.
 *
 * [Template.displayName] is kept in English on purpose: it is also used to derive the default
 * project name and the default package name, which must stay ASCII.
 */
internal fun localizedTemplateName(ctx: Context, template: Template): String =
    when (template.displayName) {
      "No Activity" -> ctx.getString(R.string.template_no_activity)
      "Basic Activity" -> ctx.getString(R.string.template_basic_activity)
      "Empty Activity" -> ctx.getString(R.string.template_empty_activity)
      "Compose Activity" -> ctx.getString(R.string.template_compose_activity)
      "Bottom Navigation" -> ctx.getString(R.string.template_bottom_navigation)
      "Navigation drawer" -> ctx.getString(R.string.template_navigation_drawer)
      "Responsive activity" -> ctx.getString(R.string.template_responsive_activity)
      "Game Activity" -> ctx.getString(R.string.template_game_activity)
      "Native C++" -> ctx.getString(R.string.template_native_cpp)
      "Xposed Module" -> ctx.getString(R.string.template_xposed_module)
      else -> template.displayName
    }

class TemplateVH(val card: MaterialCardView, val title: TextView, val image: ImageView) :
    RecyclerView.ViewHolder(card)

class SimpleTextWatcher(private val afterChanged: (CharSequence?) -> Unit) :
    android.text.TextWatcher {
  override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

  override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}

  override fun afterTextChanged(s: android.text.Editable?) = afterChanged(s)
}

private val Int.dp: Int
  get() = (this * android.content.res.Resources.getSystem().displayMetrics.density).toInt()

internal object SafResolver {
  private const val ANDROID_DOCS_AUTHORITY = "com.android.externalstorage.documents"
  private const val ANDROIDIDE_DOCS_AUTHORITY = "com.tom.rv2ide.documents"

  fun resolveToPath(context: Context, uriStr: String): String {
    return try {
      val uri = Uri.parse(uriStr)
      val docUri =
          DocumentsContractCompat.buildDocumentUriUsingTree(
              uri,
              DocumentsContractCompat.getTreeDocumentId(uri)!!,
          ) ?: return Environment.PROJECTS_DIR.absolutePath

      val docId =
          DocumentsContractCompat.getDocumentId(docUri)
              ?: return Environment.PROJECTS_DIR.absolutePath

      when (docUri.authority) {
        ANDROIDIDE_DOCS_AUTHORITY -> docId
        ANDROID_DOCS_AUTHORITY -> {
          val split = docId.split(':')
          if (split.size != 2) return Environment.PROJECTS_DIR.absolutePath

          if (split[0] == "primary") {
            File(AndroidEnvironment.getExternalStorageDirectory(), split[1]).absolutePath
          } else {
            "/storage/${split[0]}/${split[1]}"
          }
        }
        else -> Environment.PROJECTS_DIR.absolutePath
      }
    } catch (e: Exception) {
      e.printStackTrace()
      Environment.PROJECTS_DIR.absolutePath
    }
  }
}
