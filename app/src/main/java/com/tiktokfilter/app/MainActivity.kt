package com.tiktokfilter.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.tiktokfilter.app.databinding.ActivityMainBinding
import com.tiktokfilter.app.diagnostics.DiagnosticLog
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository
    private lateinit var diagnosticLog: DiagnosticLog

    private var isSelectModeActive = false
    private val selectedCreators = mutableSetOf<String>()

    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStats()
            refreshHandler.postDelayed(this, STATS_REFRESH_INTERVAL_MILLIS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        settingsRepository = SettingsRepository(this)
        statsRepository = StatsRepository(this)
        diagnosticLog = DiagnosticLog(this, settingsRepository)

        requestStoragePermissionIfNeeded()
        setupListeners()
        binding.adSkipSwitch.isChecked = settingsRepository.isAdSkipEnabled
        binding.blockedCreatorSkipSwitch.isChecked = settingsRepository.isBlockedCreatorSkipEnabled
        binding.realBlockSwitch.isChecked = settingsRepository.isRealBlockAutomationEnabled
        binding.overlaySwitch.isChecked = settingsRepository.isOverlayEnabled
        binding.diagnosticLoggingSwitch.isChecked = settingsRepository.isDiagnosticLoggingEnabled
        binding.liveStreamSkipSwitch.isChecked = settingsRepository.isLiveStreamSkipEnabled
        renderAllLists()
        refreshStats()
    }

    override fun onResume() {
        super.onResume()
        refreshAccessibilityStatus()
        refreshHandler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        refreshHandler.removeCallbacks(refreshRunnable)
    }

    /** Only needed to locate the video file TikTok's own Save action writes, so its
      * audio can be extracted - see DownloadedVideoLocator. Nothing else in this app
      * touches device storage. */
    private fun requestStoragePermissionIfNeeded() {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.READ_MEDIA_VIDEO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(permission), REQUEST_STORAGE_PERMISSION)
        }
    }

    private fun setupListeners() {
        binding.openAccessibilitySettingsButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.adSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isAdSkipEnabled = isChecked
        }
        binding.blockedCreatorSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isBlockedCreatorSkipEnabled = isChecked
        }
        binding.realBlockSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isRealBlockAutomationEnabled = isChecked
        }
        binding.overlaySwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isOverlayEnabled = isChecked
        }
        binding.liveStreamSkipSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isLiveStreamSkipEnabled = isChecked
        }

        binding.addBlockedCreatorButton.setOnClickListener {
            val handle = binding.blockedCreatorInput.text.toString().trim()
            if (handle.isEmpty()) return@setOnClickListener
            settingsRepository.addBlockedCreator(handle)
            binding.blockedCreatorInput.setText("")
            renderBlockedCreators()
        }
        binding.toggleSelectModeButton.setOnClickListener { toggleSelectMode() }
        binding.removeSelectedButton.setOnClickListener { removeSelectedCreators() }

        binding.addAdKeywordButton.setOnClickListener {
            val keyword = binding.adKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addAdKeyword(keyword)
            binding.adKeywordInput.setText("")
            renderAdKeywords()
        }
        binding.addTargetPackageButton.setOnClickListener {
            val pkg = binding.targetPackageInput.text.toString().trim()
            if (pkg.isEmpty()) return@setOnClickListener
            if (pkg !in settingsRepository.targetPackages) {
                settingsRepository.targetPackages = settingsRepository.targetPackages + pkg
            }
            binding.targetPackageInput.setText("")
            renderTargetPackages()
        }

        binding.addMoreOptionsKeywordButton.setOnClickListener {
            val keyword = binding.moreOptionsKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addKeyword(settingsRepository::moreOptionsKeywords, keyword)
            binding.moreOptionsKeywordInput.setText("")
            renderMoreOptionsKeywords()
        }
        binding.addBlockOptionKeywordButton.setOnClickListener {
            val keyword = binding.blockOptionKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addKeyword(settingsRepository::blockOptionKeywords, keyword)
            binding.blockOptionKeywordInput.setText("")
            renderBlockOptionKeywords()
        }
        binding.addBlockConfirmKeywordButton.setOnClickListener {
            val keyword = binding.blockConfirmKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addKeyword(settingsRepository::blockConfirmKeywords, keyword)
            binding.blockConfirmKeywordInput.setText("")
            renderBlockConfirmKeywords()
        }
        binding.addDownloadOptionKeywordButton.setOnClickListener {
            val keyword = binding.downloadOptionKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addKeyword(settingsRepository::downloadOptionKeywords, keyword)
            binding.downloadOptionKeywordInput.setText("")
            renderDownloadOptionKeywords()
        }
        binding.addLiveMoreOptionsKeywordButton.setOnClickListener {
            val keyword = binding.liveMoreOptionsKeywordInput.text.toString().trim()
            if (keyword.isEmpty()) return@setOnClickListener
            settingsRepository.addKeyword(settingsRepository::liveMoreOptionsKeywords, keyword)
            binding.liveMoreOptionsKeywordInput.setText("")
            renderLiveMoreOptionsKeywords()
        }

        binding.clearStatsButton.setOnClickListener {
            statsRepository.clear()
            refreshStats()
        }

        binding.diagnosticLoggingSwitch.setOnCheckedChangeListener { _, isChecked ->
            settingsRepository.isDiagnosticLoggingEnabled = isChecked
        }
        binding.shareDiagnosticLogButton.setOnClickListener { shareDiagnosticLog() }
        binding.clearDiagnosticLogButton.setOnClickListener {
            diagnosticLog.clear()
            refreshStats()
            Toast.makeText(this, "Diagnostic log cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun shareDiagnosticLog() {
        if (diagnosticLog.sizeBytes == 0L) {
            Toast.makeText(this, "Diagnostic log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", File(diagnosticLog.filePath))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share diagnostic log"))
    }

    private fun toggleSelectMode() {
        isSelectModeActive = !isSelectModeActive
        selectedCreators.clear()
        binding.removeSelectedButton.visibility = if (isSelectModeActive) View.VISIBLE else View.GONE
        binding.toggleSelectModeButton.text = if (isSelectModeActive) "Cancel" else "Select"
        renderBlockedCreators()
    }

    private fun removeSelectedCreators() {
        if (selectedCreators.isEmpty()) return
        settingsRepository.blockedCreators = settingsRepository.blockedCreators.filterNot { it in selectedCreators }
        selectedCreators.clear()
        toggleSelectMode() // leaves select mode after a bulk removal, matching a single-remove's implicit "done"
    }

    private fun refreshAccessibilityStatus() {
        binding.statusText.text = if (isAccessibilityServiceEnabled()) {
            "Accessibility Service: Enabled - filtering is active in the background"
        } else {
            "Accessibility Service: Not enabled - tap below to turn it on"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expected = "$packageName/${TikTokFilterService::class.java.name}"
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun refreshStats() {
        binding.adsSkippedText.text = "Ads skipped: ${statsRepository.adsSkipped}"
        binding.creatorsSkippedText.text = "Creators skipped: ${statsRepository.creatorsSkipped}"
        binding.audioExtractedText.text = "Audio saved: ${statsRepository.audioExtractionsCompleted}"
        val log = statsRepository.recentLog()
        binding.activityLogText.text = if (log.isEmpty()) "No activity yet" else log.joinToString("\n")

        val kilobytes = diagnosticLog.sizeBytes / 1024.0
        binding.diagnosticLogSizeText.text = "Diagnostic log: %.1f KB".format(kilobytes)
    }

    private fun renderAllLists() {
        renderBlockedCreators()
        renderAdKeywords()
        renderTargetPackages()
        renderMoreOptionsKeywords()
        renderBlockOptionKeywords()
        renderBlockConfirmKeywords()
        renderDownloadOptionKeywords()
        renderLiveMoreOptionsKeywords()
    }

    /** Not built on the shared renderList helper (unlike every other list here) because
      * it needs an extra per-row control - a checkbox that only appears in select mode,
      * swapped in place of the usual Remove button rather than alongside it. */
    private fun renderBlockedCreators() {
        val container = binding.blockedCreatorsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (normalizedHandle in settingsRepository.blockedCreators) {
            val row = inflater.inflate(R.layout.list_item_row, container, false)
            row.findViewById<TextView>(R.id.rowText).text = normalizedHandle
            val checkbox = row.findViewById<CheckBox>(R.id.rowCheckbox)
            val removeButton = row.findViewById<Button>(R.id.rowRemoveButton)

            if (isSelectModeActive) {
                checkbox.visibility = View.VISIBLE
                checkbox.isChecked = normalizedHandle in selectedCreators
                checkbox.setOnCheckedChangeListener { _, checked ->
                    if (checked) selectedCreators.add(normalizedHandle) else selectedCreators.remove(normalizedHandle)
                }
                removeButton.visibility = View.GONE
            } else {
                checkbox.visibility = View.GONE
                removeButton.visibility = View.VISIBLE
                removeButton.setOnClickListener {
                    settingsRepository.removeBlockedCreator(normalizedHandle)
                    renderBlockedCreators()
                }
            }
            container.addView(row)
        }
    }

    private fun renderAdKeywords() {
        renderList(binding.adKeywordsContainer, settingsRepository.adKeywords) { keyword ->
            settingsRepository.removeAdKeyword(keyword)
            renderAdKeywords()
        }
    }

    private fun renderTargetPackages() {
        renderList(binding.targetPackagesContainer, settingsRepository.targetPackages) { pkg ->
            settingsRepository.targetPackages = settingsRepository.targetPackages.filterNot { it == pkg }
            renderTargetPackages()
        }
    }

    private fun renderMoreOptionsKeywords() {
        renderList(binding.moreOptionsKeywordsContainer, settingsRepository.moreOptionsKeywords) { keyword ->
            settingsRepository.removeKeyword(settingsRepository::moreOptionsKeywords, keyword)
            renderMoreOptionsKeywords()
        }
    }

    private fun renderBlockOptionKeywords() {
        renderList(binding.blockOptionKeywordsContainer, settingsRepository.blockOptionKeywords) { keyword ->
            settingsRepository.removeKeyword(settingsRepository::blockOptionKeywords, keyword)
            renderBlockOptionKeywords()
        }
    }

    private fun renderBlockConfirmKeywords() {
        renderList(binding.blockConfirmKeywordsContainer, settingsRepository.blockConfirmKeywords) { keyword ->
            settingsRepository.removeKeyword(settingsRepository::blockConfirmKeywords, keyword)
            renderBlockConfirmKeywords()
        }
    }

    private fun renderDownloadOptionKeywords() {
        renderList(binding.downloadOptionKeywordsContainer, settingsRepository.downloadOptionKeywords) { keyword ->
            settingsRepository.removeKeyword(settingsRepository::downloadOptionKeywords, keyword)
            renderDownloadOptionKeywords()
        }
    }

    private fun renderLiveMoreOptionsKeywords() {
        renderList(binding.liveMoreOptionsKeywordsContainer, settingsRepository.liveMoreOptionsKeywords) { keyword ->
            settingsRepository.removeKeyword(settingsRepository::liveMoreOptionsKeywords, keyword)
            renderLiveMoreOptionsKeywords()
        }
    }

    /** Rebuilds [container] from scratch with one list_item_row per entry in [items] - simple
      * over efficient, but these lists are always small (a handful of creators/keywords),
      * so a RecyclerView would be more machinery than the job calls for. */
    private fun renderList(container: LinearLayout, items: List<String>, onRemove: (String) -> Unit) {
        container.removeAllViews()
        val inflater = LayoutInflater.from(this)
        for (item in items) {
            val row = inflater.inflate(R.layout.list_item_row, container, false)
            row.findViewById<TextView>(R.id.rowText).text = item
            row.findViewById<Button>(R.id.rowRemoveButton).setOnClickListener { onRemove(item) }
            container.addView(row)
        }
    }

    companion object {
        private const val STATS_REFRESH_INTERVAL_MILLIS = 3_000L
        private const val REQUEST_STORAGE_PERMISSION = 100
    }
}
