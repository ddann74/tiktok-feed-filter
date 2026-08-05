package com.tiktokfilter.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.tiktokfilter.app.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var statsRepository: StatsRepository

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

        setupListeners()
        binding.adSkipSwitch.isChecked = settingsRepository.isAdSkipEnabled
        binding.blockedCreatorSkipSwitch.isChecked = settingsRepository.isBlockedCreatorSkipEnabled
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

        binding.addBlockedCreatorButton.setOnClickListener {
            val handle = binding.blockedCreatorInput.text.toString().trim()
            if (handle.isEmpty()) return@setOnClickListener
            settingsRepository.addBlockedCreator(handle)
            binding.blockedCreatorInput.setText("")
            renderBlockedCreators()
        }
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

        binding.clearStatsButton.setOnClickListener {
            statsRepository.clear()
            refreshStats()
        }
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
        val log = statsRepository.recentLog()
        binding.activityLogText.text = if (log.isEmpty()) "No activity yet" else log.joinToString("\n")
    }

    private fun renderAllLists() {
        renderBlockedCreators()
        renderAdKeywords()
        renderTargetPackages()
    }

    private fun renderBlockedCreators() {
        renderList(binding.blockedCreatorsContainer, settingsRepository.blockedCreators.map { "@$it" }) { display ->
            settingsRepository.removeBlockedCreator(display)
            renderBlockedCreators()
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
    }
}
