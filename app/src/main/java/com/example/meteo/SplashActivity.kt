package com.example.meteo

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        // Handler to delay the transition to MainActivity
        Handler().postDelayed({
            // Start MainActivity after delay
            val intent = Intent(this, MainActivity::class.java)
            startActivity(intent)
            finish() // Close SplashActivity to prevent it from being added to the back stack
        }, 2000) // 2000 milliseconds = 2 seconds delay
    }
}
