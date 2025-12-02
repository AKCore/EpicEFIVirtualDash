package com.buttonbox.ble

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.buttonbox.ble.databinding.ActivitySplashBinding

@SuppressLint("CustomSplashScreen")
class SplashActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySplashBinding
    
    companion object {
        private const val SPLASH_DELAY = 2000L
        private const val PREFS_NAME = "epic_dash_prefs"
        private const val KEY_DISCLAIMER_ACCEPTED = "disclaimer_accepted"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Animate logo fade in
        binding.ivLogo.alpha = 0f
        binding.tvAppName.alpha = 0f
        binding.tvTagline.alpha = 0f
        
        binding.ivLogo.animate().alpha(1f).setDuration(800).start()
        binding.tvAppName.animate().alpha(1f).setDuration(800).setStartDelay(400).start()
        binding.tvTagline.animate().alpha(1f).setDuration(800).setStartDelay(600).start()
        
        Handler(Looper.getMainLooper()).postDelayed({
            checkDisclaimerAndProceed()
        }, SPLASH_DELAY)
    }

    private fun checkDisclaimerAndProceed() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val disclaimerAccepted = prefs.getBoolean(KEY_DISCLAIMER_ACCEPTED, false)
        
        if (disclaimerAccepted) {
            proceedToMain()
        } else {
            showDisclaimer()
        }
    }

    private fun showDisclaimer() {
        AlertDialog.Builder(this, R.style.DarkAlertDialog)
            .setTitle("⚠️ Off-Road Use Only")
            .setMessage(
                "IMPORTANT DISCLAIMER\n\n" +
                "EpicDash is designed for OFF-ROAD and COMPETITION USE ONLY.\n\n" +
                "• This application is NOT intended for use on public roads\n" +
                "• Do not operate this device while driving\n" +
                "• The driver should never interact with this application while the vehicle is in motion\n" +
                "• Always follow local laws and regulations\n" +
                "• Use at your own risk\n\n" +
                "By continuing, you acknowledge that you understand and accept these terms."
            )
            .setPositiveButton("I Understand & Accept") { _, _ ->
                val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                prefs.edit().putBoolean(KEY_DISCLAIMER_ACCEPTED, true).apply()
                proceedToMain()
            }
            .setNegativeButton("Decline") { _, _ ->
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun proceedToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}
