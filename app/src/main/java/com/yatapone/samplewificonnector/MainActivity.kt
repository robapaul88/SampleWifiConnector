package com.yatapone.samplewificonnector

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID
import android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiNetworkSuggestion
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.yatapone.samplewificonnector.databinding.ActivityMainBinding
import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter


class MainActivity : AppCompatActivity() {
    companion object {
        private const val TAG = "!WIFI!"
        private const val TAG_INTERNET = "$TAG.INTERNET"
        private const val PERMISSION_REQUEST_CODE = 999
        private const val SECURITY_TYPE_WPA3 = "WPA3"
        private const val SECURITY_TYPE_WPA2 = "WPA2"
        private const val SECURITY_TYPE_WPA = "WPA"
        private const val SECURITY_TYPE_NA = "N/A"
    }

    private enum class ConnectBy {
        SPECIFIER, SUGGESTION
    }

    private var android10Suggestions: List<WifiNetworkSuggestion>? = null
    private val dateTimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy/MM/dd(E) HH:mm:ss")
    private var permissionRejected: Boolean = false
    private var isDialogDisplayed: Boolean = false
    private lateinit var binding: ActivityMainBinding
    private lateinit var wifiListAdapter: WifiListAdapter
    private val connectivityManager: ConnectivityManager by lazy {
        getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    private lateinit var wifiManager: WifiManager
    private lateinit var wifiScanReceiver: BroadcastReceiver
    private var connectBy = ConnectBy.SPECIFIER

    private var suggestionPostConnectionReceiver: BroadcastReceiver? = null
    private var defaultNetworkCallback: ConnectivityManager.NetworkCallback? = null
    private var connectCallback: ConnectivityManager.NetworkCallback? = null

    private val netMap = mutableMapOf<Network, Boolean>()
    private var hadNet = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private fun toast(message: String) {
        mainHandler.post {
            Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate: ")
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        refreshConnectionType()

        wifiListAdapter = WifiListAdapter { wifi -> onClickWifi(wifi) }
        binding.recyclerView.adapter = wifiListAdapter
        binding.recyclerView.layoutManager = LinearLayoutManager(this)

        wifiManager = getSystemService(Context.WIFI_SERVICE) as WifiManager

        wifiScanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
                Log.d(TAG, "onReceive: success=$success")
                // success or failure processing

                refreshWifiList()
            }
        }

        suggestionPostConnectionReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (!intent.action.equals(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)) {
                    Log.d(TAG, "suggestionPostConnectionReceiver wrong action ${content(intent)}")
                    return
                }
                Log.d(TAG, "suggestionPostConnectionReceiver ${content(intent)}")
                binding.connectedTo.text = getString(R.string.connected)
            }
        }

        binding.bySpecifier.setOnClickListener {
            connectBy = ConnectBy.SPECIFIER
            refreshConnectionType()
        }
        binding.bySuggestions.setOnClickListener {
            connectBy = ConnectBy.SUGGESTION
            refreshConnectionType()
        }
        binding.fab.setOnClickListener {
            bgHandler.post {
                checkInternetNow(forceToast = true)
            }
        }

        registerReceiver(wifiScanReceiver, IntentFilter().also { filter ->
            filter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        })

        registerReceiver(suggestionPostConnectionReceiver, IntentFilter().also { filter ->
            filter.addAction(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)
        })
    }

    private fun refreshConnectionType() {
        val bySpecifier = connectBy == ConnectBy.SPECIFIER
        binding.bySpecifier.setBackgroundColor(ContextCompat.getColor(this, if (bySpecifier) R.color.gray else R.color.purple_200))
        binding.bySuggestions.setBackgroundColor(ContextCompat.getColor(this, if (bySpecifier) R.color.purple_200 else R.color.gray))
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: ")

        if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "onResume: permission.ACCESS_FINE_LOCATION is granted.")
        } else {
            Log.d(TAG, "onResume: permission.ACCESS_FINE_LOCATION is required.")
            if (!permissionRejected) {
                requestPermissions(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQUEST_CODE)
            } else if (!isDialogDisplayed) {
                isDialogDisplayed = true
                AlertDialog.Builder(this)
                    .setTitle("a permission is required")
                    .setMessage("ACCESS_FINE_LOCATION permission is required for scanning Wi-Fi list. Restart app and grant ACCESS_FINE_LOCATION permission.")
                    .setPositiveButton("OK") { _, _ ->
                        finish()
                    }
                    .show()
            }
        }

        refreshWifiList()

        connectivityManager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.d(TAG, "default onAvailable $network")
            }

            override fun onLost(network: Network) {
                Log.d(TAG, "default onLost $network")
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "default onCapabilitiesChanged: $networkCapabilities")
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onNetworkDetected(network)
                } else {
                    netMap[network] = false
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                Log.d(TAG, "default onLinkPropertiesChanged: $linkProperties")
            }
        })
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Log.d(TAG, "onRequestPermissionsResult: requestCode=$requestCode, permissions=${permissions[0]}, grantResults=${grantResults[0]}")
        if (requestCode == PERMISSION_REQUEST_CODE && grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            permissionRejected = true
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun refreshWifiList() {
        binding.statusText.text = dateTimeFormat.format(LocalDateTime.now())

        val wifiList: List<Wifi> = wifiManager.scanResults
            .filter { !it.SSID.isNullOrEmpty() }
            .distinctBy { it.SSID }
            .map {
                val ssid = it.SSID
                val waveLevel = it.level
                val securityType = when {
                    it.capabilities.contains(SECURITY_TYPE_WPA3) -> SECURITY_TYPE_WPA3
                    it.capabilities.contains(SECURITY_TYPE_WPA2) -> SECURITY_TYPE_WPA2
                    it.capabilities.contains(SECURITY_TYPE_WPA) -> SECURITY_TYPE_WPA
                    else -> SECURITY_TYPE_NA
                }
                Wifi(ssid, waveLevel, securityType)
            }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android10Suggestions = wifiList.map { WifiNetworkSuggestion.Builder().setSsid(it.ssid).build() }
        }
        wifiListAdapter.submitList(wifiList.sortedByDescending { it.waveLevel })
        wifiListAdapter.notifyDataSetChanged()
        Log.d(TAG, "refreshWifiList")
    }

    private fun onClickWifi(wifi: Wifi) {
        Log.d(TAG, "onClickWifi $wifi")

        val editText = AppCompatEditText(this)
        AlertDialog.Builder(this)
            .setTitle("Connect to ${wifi.ssid}")
            .setMessage("input passphrase.")
            .setView(editText)
            .setPositiveButton("connect") { dialog, _ ->
                val pass = editText.text.toString()

                if (pass.isEmpty()) {
                    Log.d(TAG, "onClickWifi: input error")
                    Toast.makeText(this, "Please enter pass", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    return@setPositiveButton
                }

                connect(wifi, pass)
                dialog.dismiss()
            }
            .setNegativeButton("cancel") { dialog, _ ->
                dialog.dismiss()
            }
            .create()
            .show()
    }

    private fun connect(wifi: Wifi, pass: String) {
        when (connectBy) {
            ConnectBy.SPECIFIER -> connectBySpecifier(wifi, pass)
            ConnectBy.SUGGESTION -> connectBySuggestions(wifi, pass)
        }
    }

    private fun connectBySpecifier(wifi: Wifi, pass: String) {
        Log.d(TAG, "connectByWifiNetworkSpecifier: wifi=$wifi, pass=$pass")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(wifi.ssid)
        when (wifi.securityType) {
            SECURITY_TYPE_WPA3 -> specifier.setWpa3Passphrase(pass)
            SECURITY_TYPE_WPA2 -> specifier.setWpa2Passphrase(pass)
            SECURITY_TYPE_WPA -> specifier.setWpa2Passphrase(pass)
            SECURITY_TYPE_NA -> specifier.setWpa2Passphrase(pass)
            else -> specifier.setWpa2Passphrase(pass)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .setNetworkSpecifier(specifier.build())
            .build()

        connectCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                super.onAvailable(network)
                Log.d(TAG, "connection onAvailable $network")
                binding.connectedTo.text = getString(R.string.connected_to_frm, wifi.ssid)

            }

            override fun onUnavailable() {
                super.onUnavailable()
                Log.d(TAG, "connection onUnavailable")
                binding.connectedTo.text = getString(R.string.unavailable)
            }


            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                Log.d(TAG, "connection onCapabilitiesChanged: $networkCapabilities")
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    onNetworkDetected(network)
                } else {
                    netMap[network] = false
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                super.onLinkPropertiesChanged(network, linkProperties)
                Log.d(TAG, "connection onLinkPropertiesChanged: $linkProperties")
            }

        }
        connectCallback?.let {
            connectivityManager.requestNetwork(request, it)
        }
    }

    private fun connectBySuggestions(wifi: Wifi, pass: String) {
        Log.d(TAG, "connectTo $wifi (pass=$pass)")
        val suggestions: List<WifiNetworkSuggestion>? = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            android10Suggestions
        } else {
            wifiManager.networkSuggestions
        }
        if (!suggestions.isNullOrEmpty()) {
            val result = wifiManager.removeNetworkSuggestions(suggestions)
            val resultKey = process(result)
            Log.d(
                TAG, "connectTo removeNetworkSuggestions=$resultKey (${
                    suggestions.joinToString {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            it.ssid ?: it.toString()
                        } else {
                            it.toString()
                        }
                    }
                })"
            )
        } else {
            Log.d(TAG, "connectTo no suggestion to remove")
        }
        val suggestion = WifiNetworkSuggestion.Builder()
            .setSsid(wifi.ssid)
        when (wifi.securityType) {
            SECURITY_TYPE_WPA3 -> suggestion.setWpa3Passphrase(pass)
            else -> suggestion.setWpa2Passphrase(pass)
        }
        val result = wifiManager.addNetworkSuggestions(listOf(suggestion.build()))
        val resultKey = process(result)
        Log.d(TAG, "connectByWifiNetworkSuggestion: $resultKey")
        toast(resultKey)
    }

    private fun process(result: Int): String = when (result) {
        STATUS_NETWORK_SUGGESTIONS_SUCCESS -> "STATUS_NETWORK_SUGGESTIONS_SUCCESS"
        STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL -> "STATUS_NETWORK_SUGGESTIONS_ERROR_INTERNAL"
        STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED -> "STATUS_NETWORK_SUGGESTIONS_ERROR_APP_DISALLOWED"
        STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> "STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE"
        STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP -> "STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_EXCEEDS_MAX_PER_APP"
        STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID -> "STATUS_NETWORK_SUGGESTIONS_ERROR_REMOVE_INVALID"
        STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED -> "STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_NOT_ALLOWED"
        STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID -> "STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_INVALID"
        else -> "unknown $result"
    }

    private fun onNetworkDetected(network: Network) {
        netMap[network].takeIf { it != true }?.let {
            netMap[network] = true
            Log.d(TAG_INTERNET, "onCapabilitiesChanged - detected internet")
            toast("Internet detected for $network")
            checkInternet()
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: ")
        stopInternetCheck()
        unregisterReceiver(wifiScanReceiver)

        try {
            defaultNetworkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            Log.d(TAG, "onDestroy: defaultNetworkCallback: e=$e")
        }
        try {
            suggestionPostConnectionReceiver?.let { unregisterReceiver(it) }
        } catch (e: Exception) {
            Log.d(TAG, "onDestroy: unregisterNetworkCallback: e=$e")
        }

        super.onDestroy()
    }

    private val thread: HandlerThread by lazy {
        val backgroundThread = HandlerThread(TAG_INTERNET)
        backgroundThread.start()
        backgroundThread
    }
    private val bgHandler: Handler by lazy {
        Handler(thread.looper)
    }

    private val retryInternetCheckRunnable: Runnable = Runnable {
        bgHandler.postDelayed(internetCheckRunnable, 1000L)
    }

    private val internetCheckRunnable: Runnable = Runnable {
        checkInternetNow()
        bgHandler.post(retryInternetCheckRunnable)
    }

    private fun checkInternetNow(forceToast: Boolean = false) {
        val hasNet = testInternetConnection()
        if (hadNet != hasNet || forceToast) {
            val msg = "Internet ${
                when {
                    forceToast ->
                        if (hasNet) "reachable" else "not reachable"

                    hasNet -> "detected"
                    else -> "lost"
                }
            }"
            toast(msg)
            Log.d(TAG_INTERNET, msg)
        }
    }

    private fun checkInternet() {
        Log.d(TAG_INTERNET, "!!!checkInternet")
        bgHandler.removeCallbacksAndMessages(null)
        bgHandler.post(internetCheckRunnable)
    }

    private fun stopInternetCheck() {
        Log.d(TAG_INTERNET, "stopInternetCheck")
        bgHandler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    private fun testInternetConnection(): Boolean {
        try {
            val url: URL = try {
                URL("https://clients3.google.com/generate_204")
            } catch (e: MalformedURLException) {
                Log.d(TAG_INTERNET, "testInternetConnection MalformedURLException $e")
                return false
            }

            //open connection. If fails return false
            val urlConnection: HttpURLConnection = try {
                url.openConnection() as HttpURLConnection
            } catch (e: IOException) {
                Log.d(TAG_INTERNET, "testInternetConnection IOException1 $e")
                return false
            }
            urlConnection.setRequestProperty("User-Agent", "Android")
            urlConnection.setRequestProperty("Connection", "close")
            urlConnection.connectTimeout = 1000
            urlConnection.readTimeout = 1000
            urlConnection.connect()
            val code = urlConnection.responseCode
            val length = urlConnection.contentLength
            val success = code == 204 && length == 0
            Log.d(TAG_INTERNET, "testInternetConnection - success=$success, code=$code, length=$length")
            return success
        } catch (e: IOException) {
            Log.d(TAG_INTERNET, "testInternetConnection IOException2 $e")
            return false
        }
    }

    private fun content(intent: Intent): String {
        val sb = StringBuilder()

        sb.append("Action=").append(intent.action).append(";")
        sb.append("Data=").append(intent.dataString).append(";")
        sb.append("Flags=").append(Integer.toHexString(intent.flags)).append(";")
        sb.append("Component=").append(intent.component).append(";")

        val extras = intent.extras
        if (extras != null) {
            sb.append("Extras=")
            for (key in extras.keySet()) {
                val value = extras[key]
                sb.append(key).append("->")
                if (value is String) {
                    sb.append(value).append(";")
                } else {
                    value?.let { sb.append(value.javaClass.simpleName).append(";") }
                }
            }
        }

        return sb.toString()
    }
}