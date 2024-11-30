package com.example.meteo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.launch
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var swipeRefreshLayout: androidx.swiperefreshlayout.widget.SwipeRefreshLayout
    private val handler = Handler(Looper.getMainLooper()) // Handler to run tasks on the main thread
    private val weatherRunnable = object : Runnable {
        override fun run() {
            // Periodically fetch weather data (every 4 hours)
            lifecycleScope.launch {
                fetchWeatherData("Temara,MA") // Default location for fallback
            }
            handler.postDelayed(this, 4 * 60 * 60 * 1000) // Run again after 4 hours (in milliseconds)
        }
    }

    private lateinit var sharedPreferences: SharedPreferences
    private val lastNotificationKey = "last_notification_time"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize SharedPreferences
        sharedPreferences = getSharedPreferences("WeatherAppPrefs", Context.MODE_PRIVATE)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        requestLocationPermission()

        swipeRefreshLayout.setOnRefreshListener {
            requestLocationPermission() // Refresh location when swiped
        }

        // Start the periodic task when the app is created
        handler.post(weatherRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(weatherRunnable) // Stop the handler when the activity is destroyed
    }

    private val locationPermissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            if (isGranted) {
                getCurrentLocation()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
            }
        }

    private fun requestLocationPermission() {
        when {
            ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED -> {
                getCurrentLocation()
            }
            else -> {
                locationPermissionRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }
    }

    private fun getCurrentLocation() {
        fusedLocationClient.lastLocation.addOnSuccessListener { location: Location? ->
            location?.let {
                val cityCountry = getCityCountryCode(location)
                lifecycleScope.launch {
                    fetchWeatherData(cityCountry)
                }
            } ?: run {
                Toast.makeText(this, "Location not available", Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    fetchWeatherData("Temara,MA") // Fallback if location is not available
                }
            }
        }
    }

    private fun getCityCountryCode(location: Location): String {
        val geocoder = Geocoder(this, Locale.getDefault())
        val addresses = geocoder.getFromLocation(location.latitude, location.longitude, 1)
        val city = addresses?.get(0)?.locality ?: "Temara"
        val countryCode = addresses?.get(0)?.countryCode?.toUpperCase(Locale.ROOT) ?: "MA"
        return "$city,$countryCode"
    }

    private suspend fun fetchWeatherData(cityCountry: String) {
        val apiKey = "daec2a125bd010f6f6d3f74976eb59d6"
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.openweathermap.org/data/2.5/")
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        val weatherApi = retrofit.create(WeatherApi::class.java)

        try {
            val response = weatherApi.getWeather(cityCountry, apiKey, "metric")
            if (response.isSuccessful) {
                val weather = response.body()
                if (weather != null) {
                    val temperature = weather.main.temp
                    Log.d("WeatherData", "Fetched temperature: ${temperature}°C")
                    if (shouldShowNotification()) {
                        showNotification(applicationContext, temperature, weather.name)
                        updateLastNotificationTime()
                    }
                    displayWeatherData(weather)
                } else {
                    Log.d("WeatherData", "Weather data is null, using fallback values.")
                    fetchWeatherData("Temara,MA") // Fetch weather data for Temara as fallback
                }
            } else {
                Log.d("WeatherData", "Failed to fetch weather data, response code: ${response.code()}")
                fetchWeatherData("Temara,MA") // Fetch weather data for Temara as fallback
            }
        } catch (e: Exception) {
            Log.d("WeatherData", "Error fetching weather data: ${e.message}")
            fetchWeatherData("Temara,MA") // Fetch weather data for Temara as fallback
        } finally {
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun shouldShowNotification(): Boolean {
        val lastNotificationTime = sharedPreferences.getLong(lastNotificationKey, 0)
        val currentTime = System.currentTimeMillis()
        val timeDifference = currentTime - lastNotificationTime
        val fourHoursInMillis = 4 * 60 * 60 * 1000 // 4 hours in milliseconds

        return timeDifference >= fourHoursInMillis
    }

    private fun updateLastNotificationTime() {
        val currentTime = System.currentTimeMillis()
        with(sharedPreferences.edit()) {
            putLong(lastNotificationKey, currentTime)
            apply()
        }
    }

    private fun showNotification(context: Context, temperature: Float, city: String) {
        val formattedTemperature = String.format("%.1f", temperature)
        Log.d("WeatherData", "Notification data - Temp: $formattedTemperature°C, City: $city")

        val channelId = "WeatherChannel"
        val channelName = "Weather Notifications"
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // For API 26 and above, create notification channel
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Shows weather updates"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(city)
            .setContentText("Temperature: $formattedTemperature°C")
            .setSmallIcon(R.drawable.applogo) // Use appropriate icon
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(1, notification)
    }

    private fun displayWeatherData(weather: WeatherResponse) {
        val temperature = weather.main.temp
        val description = weather.weather[0].description
        val city = weather.name
        val country = weather.sys.country
        val humidity = weather.main.humidity
        val windSpeed = weather.wind.speed
        val visibility = weather.visibility / 1000 // Convert to km
        val pressure = weather.main.pressure
        val tempFeelsLike = weather.main.feels_like
        val sunrise = weather.sys.sunrise // In Unix timestamp
        val sunset = weather.sys.sunset // In Unix timestamp

        // Get references to UI elements for displaying the weather data
        val temperatureTextView = findViewById<TextView>(R.id.tempText)
        val descriptionTextView = findViewById<TextView>(R.id.weatherDescText)
        val cityTextView = findViewById<TextView>(R.id.cityText)
        val humidityTextView = findViewById<TextView>(R.id.humidityText)
        val windSpeedTextView = findViewById<TextView>(R.id.windSpeedText)
        val visibilityTextView = findViewById<TextView>(R.id.visibilityText)
//        val pressureTextView = findViewById<TextView>(R.id.pressureTextView)
        val feelsLikeTextView = findViewById<TextView>(R.id.feelsLikeText)

        // UI elements for sunrise and sunset
        val sunriseTextView = findViewById<TextView>(R.id.sunriseText)
        val sunsetTextView = findViewById<TextView>(R.id.sunsetText)

        // Update the UI with weather data
        temperatureTextView.text = "$temperature°C"
        descriptionTextView.text = description.capitalize()
        cityTextView.text = "$city, $country"
        humidityTextView.text = "Humidity: $humidity%"
        windSpeedTextView.text = "Wind: $windSpeed m/s"
        visibilityTextView.text = "Visibility: $visibility km"
//        pressureTextView.text = "Pressure: $pressure hPa"
        feelsLikeTextView.text = "Feels Like: $tempFeelsLike°C"

        // Convert Unix timestamps to human-readable time for sunrise and sunset
        val sunriseTime = convertUnixToTime(sunrise)
        val sunsetTime = convertUnixToTime(sunset)

        // Update the UI with sunrise and sunset times
        sunriseTextView.text = "Sunrise: $sunriseTime"
        sunsetTextView.text = "Sunset: $sunsetTime"

        // Set the background image based on the weather description
        val backgroundImage = findViewById<ImageView>(R.id.backgroundImage)

        when {
            description.contains("clear", ignoreCase = true) -> {
                backgroundImage.setImageResource(R.drawable.clear_sky)
            }
            description.contains("cloud", ignoreCase = true) -> {
                backgroundImage.setImageResource(R.drawable.cloudy_sky)
            }
            description.contains("rain", ignoreCase = true) -> {
                backgroundImage.setImageResource(R.drawable.rainy_sky)
            }
            description.contains("snow", ignoreCase = true) -> {
                backgroundImage.setImageResource(R.drawable.rainy_sky)
            }
            else -> {
                backgroundImage.setImageResource(R.drawable.default_weather)
            }
        }
    }


    private fun convertUnixToTime(unixTimestamp: Long): String {
        val date = Date(unixTimestamp * 1000L)
        val format = SimpleDateFormat("HH:mm", Locale.getDefault())
        return format.format(date)
    }
}

data class WeatherResponse(
    val main: Main,
    val weather: List<Weather>,
    val sys: Sys,
    val wind: Wind,
    val visibility: Int,
    val name: String
)

data class Main(val temp: Float, val pressure: Int, val humidity: Int, val feels_like: Float)
data class Weather(val description: String)
data class Sys(val country: String, val sunrise: Long, val sunset: Long)
data class Wind(val speed: Float)

interface WeatherApi {
    @GET("weather")
    suspend fun getWeather(
        @Query("q") cityCountry: String,
        @Query("appid") apiKey: String,
        @Query("units") units: String
    ): Response<WeatherResponse>
}
