package com.marinov.boletosfei

import android.annotation.SuppressLint
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        const val STATUS_OFFLINE = "0"
        const val STATUS_ONLINE_OK = "1"
        const val STATUS_LOGIN_NEEDED = "A"
        private const val HOME_URL = "https://interage.fei.org.br/secureserver/portal/graduacao/home"
    }

    private var currentFragment: Fragment? = null
    private var boletosFragment: BoletosFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isUserLoggedIn()) {
            launchLogin()
            return
        }

        Dados.init(applicationContext)
        configureSystemBarsForLegacyDevices()
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.topAppBar)
        setSupportActionBar(toolbar)
        ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v, insets ->
            val statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, statusBarHeight, v.paddingRight, v.paddingBottom)
            insets
        }

        if (savedInstanceState == null) {
            val fragment = BoletosFragment()
            boletosFragment = fragment
            supportFragmentManager.beginTransaction()
                .add(R.id.fragment_container, fragment, "boletos_tag")
                .commit()
            currentFragment = fragment
        } else {
            boletosFragment = supportFragmentManager.findFragmentByTag("boletos_tag") as? BoletosFragment
            currentFragment = boletosFragment
        }

        handleIntent(intent)

        iniciarUpdateWorker()
        iniciarBoletosWorker()
        iniciarLoginWorker()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getStringExtra("destination") == "boletos") {
            boletosFragment?.loadBoletos()
        }
    }

    // ── Login ──────────────────────────────────────────

    private fun isUserLoggedIn(): Boolean {
        val prefs = getSharedPreferences(LoginActivity.PREFS_LOGIN, MODE_PRIVATE)
        return prefs.getBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
    }

    private fun launchLogin() {
        getSharedPreferences(LoginActivity.PREFS_LOGIN, MODE_PRIVATE).edit {
            putBoolean(LoginActivity.KEY_IS_LOGGED_IN, false)
        }
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        )
        finish()
    }

    // ── Conexão / Sessão ───────────────────────────────

    fun isOnline(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun checkConnectionAndSession(): String {
        if (!isOnline()) return STATUS_OFFLINE
        return withContext(Dispatchers.IO) {
            try {
                val cookies = CookieManager.getInstance().getCookie(HOME_URL)
                if (!cookies.isNullOrBlank()) {
                    STATUS_ONLINE_OK
                } else {
                    withContext(Dispatchers.Main) { launchLogin() }
                    STATUS_LOGIN_NEEDED
                }
            } catch (e: Exception) {
                Log.w(TAG, "Erro ao verificar sessão: ${e.message}")
                STATUS_OFFLINE
            }
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch { checkConnectionAndSession() }
    }

    // ── Menu superior ──────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.top_app_bar_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_profile -> {
                val profileFragment = ProfileFragment()
                supportFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container, profileFragment)
                    .addToBackStack(null)
                    .commit()
                currentFragment = profileFragment
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ── Navegação ──────────────────────────────────────

    // ── Workers ────────────────────────────────────────

    private fun iniciarUpdateWorker() {
        val work = PeriodicWorkRequest.Builder(UpdateCheckWorker::class.java, 120, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("UpdateCheckWorker", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun iniciarBoletosWorker() {
        val work = PeriodicWorkRequest.Builder(BoletosWorker::class.java, 20, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("BoletosWorkerTask", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    private fun iniciarLoginWorker() {
        val work = PeriodicWorkRequest.Builder(LoginWorker::class.java, 15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork("LoginWorkerTask", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    // ── Sistema (legado) ──────────────────────────────

    @SuppressLint("ObsoleteSdkInt")
    private fun configureSystemBarsForLegacyDevices() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            val isDarkMode = when (AppCompatDelegate.getDefaultNightMode()) {
                AppCompatDelegate.MODE_NIGHT_YES -> true
                AppCompatDelegate.MODE_NIGHT_NO -> false
                else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                window.apply {
                    @Suppress("DEPRECATION")
                    clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
                    addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
                        @Suppress("DEPRECATION")
                        statusBarColor = Color.BLACK
                        @Suppress("DEPRECATION")
                        navigationBarColor = Color.BLACK
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            @Suppress("DEPRECATION")
                            var flags = decorView.systemUiVisibility
                            @Suppress("DEPRECATION")
                            flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                            @Suppress("DEPRECATION")
                            decorView.systemUiVisibility = flags
                        }
                    } else {
                        @Suppress("DEPRECATION")
                        navigationBarColor = if (isDarkMode)
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_dark)
                        else
                            ContextCompat.getColor(this@MainActivity, R.color.nav_bar_light)
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                if (isDarkMode) {
                    @Suppress("DEPRECATION")
                    flags = flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
                } else if (Build.VERSION.SDK_INT > Build.VERSION_CODES.N_MR1) {
                    @Suppress("DEPRECATION")
                    flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                }
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
            if (!isDarkMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                var flags = window.decorView.systemUiVisibility
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = flags
            }
        }
    }
}