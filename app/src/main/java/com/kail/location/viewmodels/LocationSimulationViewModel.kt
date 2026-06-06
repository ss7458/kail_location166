package com.kail.location.viewmodels

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import com.kail.location.models.UpdateInfo
import com.kail.location.network.RuoYiClient
import com.kail.location.utils.UpdateChecker
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import kotlinx.coroutines.flow.update
import com.kail.location.models.HistoryRecord
import com.kail.location.repositories.DataBaseHistoryLocation
import com.kail.location.utils.MapUtils
import com.kail.location.utils.GoUtils
import com.kail.location.utils.KailLog
import com.kail.location.utils.SimulationDiagnostics
import com.kail.location.auth.UsageManager
import androidx.preference.PreferenceManager
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.lifecycle.viewModelScope
import androidx.core.content.ContextCompat
import com.kail.location.R
import com.kail.location.service.Root.ServiceGoRoot
import com.kail.location.service.Developer.ServiceGoDeveloper
import com.kail.location.views.locationpicker.LocationPickerActivity
import com.kail.location.utils.service.ServiceConstants

/**
 * 位置模拟页面的 ViewModel。
 * 负责位置信息状态、模拟开关、摇杆开关以及更新检查逻辑。
 *
 * @property application 应用上下文。
 */
class LocationSimulationViewModel(application: Application) : AndroidViewModel(application) {
    private val dbHelper = DataBaseHistoryLocation(application)
    private var db: SQLiteDatabase? = null

    /**
     * 当前位置信息的数据结构。
     */
    data class LocationInfo(
        val name: String = "NONE",
        val address: String = "NONE • NONE",
        val latitude: Double = 0.0,
        val longitude: Double = 0.0
    )

    private val _locationInfo = MutableStateFlow(LocationInfo())
    /**
     * 当前位置信息的状态流。
     */
    val locationInfo: StateFlow<LocationInfo> = _locationInfo.asStateFlow()

    private val _isSimulating = MutableStateFlow(false)
    /**
     * 是否正在进行模拟的状态流。
     */
    val isSimulating: StateFlow<Boolean> = _isSimulating.asStateFlow()

    private val _isStarting = MutableStateFlow(false)
    val isStarting: StateFlow<Boolean> = _isStarting.asStateFlow()

    private val _isJoystickEnabled = MutableStateFlow(false)
    /**
     * 摇杆是否启用的状态流。
     */
    val isJoystickEnabled: StateFlow<Boolean> = _isJoystickEnabled.asStateFlow()

    private val _updateInfo = MutableStateFlow<UpdateInfo?>(null)
    /**
     * 应用更新信息的状态流（若存在）。
     */
    val updateInfo: StateFlow<UpdateInfo?> = _updateInfo.asStateFlow()

    private val _noticeList = MutableStateFlow<List<RuoYiClient.NoticeInfo>>(emptyList())
    val noticeList: StateFlow<List<RuoYiClient.NoticeInfo>> = _noticeList.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _installUri = MutableStateFlow<Uri?>(null)
    val installUri: StateFlow<Uri?> = _installUri.asStateFlow()

    private val _historyRecords = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val historyRecords: StateFlow<List<HistoryRecord>> = _historyRecords.asStateFlow()

    private val _selectedRecordId = MutableStateFlow<Int?>(null)
    val selectedRecordId: StateFlow<Int?> = _selectedRecordId.asStateFlow()

    private val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(application)
    private val _runMode = MutableStateFlow("developer")
    val runMode: StateFlow<String> = _runMode.asStateFlow()

    private val _stepSimulationEnabled = MutableStateFlow(false)
    val stepSimulationEnabled: StateFlow<Boolean> = _stepSimulationEnabled.asStateFlow()

    private val _stepCadenceSpm = MutableStateFlow(120f)
    val stepCadenceSpm: StateFlow<Float> = _stepCadenceSpm.asStateFlow()

    private var startTimeoutJob: kotlinx.coroutines.Job? = null

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ServiceConstants.ACTION_STATUS_CHANGED) return
            val isSimulating = intent.getBooleanExtra(ServiceConstants.EXTRA_IS_SIMULATING, false)
            if (_isStarting.value && !isSimulating) {
                return
            }
            if (isSimulating) {
                startTimeoutJob?.cancel()
                _isStarting.value = false
            }
            _isSimulating.value = isSimulating
        }
    }

    init {
        _runMode.value = sharedPreferences.getString("setting_run_mode", "developer") ?: "developer"
        _isJoystickEnabled.value = sharedPreferences.getBoolean("setting_joystick_enabled", true)
        _stepSimulationEnabled.value = sharedPreferences.getBoolean("setting_step_simulation_enabled", false)
        _stepCadenceSpm.value = sharedPreferences.getFloat("setting_step_cadence_spm", 120f)
        try {
            db = dbHelper.writableDatabase
            loadRecords()
        } catch (_: Exception) {}
        ContextCompat.registerReceiver(
            application,
            statusReceiver,
            IntentFilter(ServiceConstants.ACTION_STATUS_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun setStepSimulationEnabled(enabled: Boolean) {
        _stepSimulationEnabled.value = enabled
        sharedPreferences.edit().putBoolean("setting_step_simulation_enabled", enabled).apply()
    }

    fun setStepCadenceSpm(spm: Float) {
        _stepCadenceSpm.value = spm
        sharedPreferences.edit().putFloat("setting_step_cadence_spm", spm).apply()
    }

    fun setRunMode(mode: String) {
        _runMode.value = mode
        sharedPreferences.edit().putString("setting_run_mode", mode).apply()
    }

    /**
     * 切换模拟状态。
     */
    fun toggleSimulation() {
        if (_isStarting.value) return
        val app = getApplication<Application>()
        val next = !_isSimulating.value
        if (next) {
            viewModelScope.launch {
                if (!UsageManager.canStartSimulation(app)) {
                    KailLog.persist(app, SimulationDiagnostics.TAG,
                        "位置模拟启动被拦截：未登录或免费次数用尽（canStartSimulation=false）", 'w')
                    return@launch
                }
                if (!UsageManager.consumeSimulation(app)) {
                    KailLog.persist(app, SimulationDiagnostics.TAG,
                        "位置模拟启动被拦截：扣减模拟次数失败（consumeSimulation=false）", 'w')
                    return@launch
                }
                val info = locationInfo.value
                
                // 关键修复：启动前强制同步一次最新的运行模式
                val currentRunMode = sharedPreferences.getString("setting_run_mode", "developer") ?: "developer"
                _runMode.value = currentRunMode

                // ROOT 模式靠 ptrace 注入 system_server；刚开机时注入会卡死/重启系统。
                // 开机时长不足时拒绝启动并提示，避免设备卡死。
                if (currentRunMode == "root") {
                    val (ready, remainSec) = UsageManager.systemReadiness()
                    if (!ready) {
                        KailLog.persist(app, SimulationDiagnostics.TAG,
                            "位置模拟启动被拦截：系统未就绪，开机仅 ${android.os.SystemClock.elapsedRealtime() / 1000}s，" +
                                "需 ${UsageManager.bootReadyThresholdSeconds()}s（还需约 ${remainSec}s）", 'w')
                        GoUtils.DisplayToast(
                            app,
                            app.getString(
                                R.string.vm_system_not_ready,
                                UsageManager.bootReadyThresholdSeconds(),
                                remainSec
                            )
                        )
                        return@launch
                    }
                }
                
                try {
                    val wgs84 = MapUtils.bd2wgs(info.longitude, info.latitude)
                    db?.let {
                        DataBaseHistoryLocation.addHistoryLocation(
                            it,
                            info.name,
                            wgs84[0].toString(),
                            wgs84[1].toString(),
                            (System.currentTimeMillis() / 1000).toString(),
                            info.longitude.toString(),
                            info.latitude.toString()
                        )
                    }
                } catch (_: Exception) {}
                val serviceClass = if (currentRunMode == "root") ServiceGoRoot::class.java else ServiceGoDeveloper::class.java
                val extraJoystickEnabled = if (currentRunMode == "root") ServiceGoRoot.EXTRA_JOYSTICK_ENABLED else ServiceGoDeveloper.EXTRA_JOYSTICK_ENABLED
                val extraCoordType = if (currentRunMode == "root") ServiceGoRoot.EXTRA_COORD_TYPE else ServiceGoDeveloper.EXTRA_COORD_TYPE
                val intent = Intent(app, serviceClass)
                intent.putExtra(LocationPickerActivity.LNG_MSG_ID, info.longitude)
                intent.putExtra(LocationPickerActivity.LAT_MSG_ID, info.latitude)
                intent.putExtra(LocationPickerActivity.ALT_MSG_ID, sharedPreferences.getString("setting_altitude", "55.0")?.toDoubleOrNull() ?: 55.0)
                intent.putExtra(extraJoystickEnabled, isJoystickEnabled.value)
                intent.putExtra(extraCoordType, "BD09")
                intent.putExtra("EXTRA_IS_ROUTE_SIMULATION", false)
                
                if (currentRunMode == "root") {
                    val stepEnabled = _stepSimulationEnabled.value
                    val cadence = _stepCadenceSpm.value
                    intent.putExtra(ServiceGoRoot.EXTRA_STEP_ENABLED, stepEnabled)
                    intent.putExtra(ServiceGoRoot.EXTRA_STEP_FREQ, cadence)
                }
                
                if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    val svcName = if (currentRunMode == "root") "ServiceGoRoot" else "ServiceGoDeveloper"
                    KailLog.persist(app, SimulationDiagnostics.TAG,
                        "位置模拟：启动 $svcName（模式=$currentRunMode）")
                    _isStarting.value = true
                    scheduleStartTimeout("位置模拟")
                    ContextCompat.startForegroundService(app, intent)
                } else {
                    KailLog.persist(app, SimulationDiagnostics.TAG,
                        "位置模拟启动被拦截：缺少 ACCESS_FINE_LOCATION 权限", 'w')
                    GoUtils.DisplayToast(app, app.getString(R.string.vm_need_location_permission))
                    return@launch
                }
            }
        } else {
            val currentRunMode = sharedPreferences.getString("setting_run_mode", "developer") ?: "developer"
            val serviceClass = if (currentRunMode == "root") ServiceGoRoot::class.java else ServiceGoDeveloper::class.java
            app.stopService(Intent(app, serviceClass))
            startTimeoutJob?.cancel()
            _isStarting.value = false
            _isSimulating.value = false
        }
    }

    private fun scheduleStartTimeout(label: String) {
        startTimeoutJob?.cancel()
        val app = getApplication<Application>()
        startTimeoutJob = viewModelScope.launch {
            delay(30_000)
            if (_isStarting.value) {
                _isStarting.value = false
                KailLog.persist(app, SimulationDiagnostics.TAG,
                    "$label 启动等待服务状态超时：未收到 STATUS_CHANGED=true", 'w')
            }
        }
    }

    /**
     * 设置是否启用摇杆。
     *
     * @param enabled 为 true 表示启用，false 表示关闭。
     */
    fun setJoystickEnabled(enabled: Boolean) {
        _isJoystickEnabled.value = enabled
        val app = getApplication<Application>()
        PreferenceManager.getDefaultSharedPreferences(app)
            .edit()
            .putBoolean("setting_joystick_enabled", enabled)
            .apply()
        if (_isSimulating.value) {
            val currentRunMode = sharedPreferences.getString("setting_run_mode", "developer") ?: "developer"
            val serviceClass = if (currentRunMode == "root") ServiceGoRoot::class.java else ServiceGoDeveloper::class.java
            val extraJoystickEnabled = if (currentRunMode == "root") ServiceGoRoot.EXTRA_JOYSTICK_ENABLED else ServiceGoDeveloper.EXTRA_JOYSTICK_ENABLED
            val intent = Intent(app, serviceClass)
            intent.putExtra(extraJoystickEnabled, enabled)
            app.startService(intent)
        }
    }

    /**
     * 检查应用更新。
     *
     * @param context 用于检查更新的上下文。
     * @param isAuto 是否为自动检查（自动检查时会抑制部分提示）。
     */
    fun checkUpdate(context: Context, isAuto: Boolean = false) {
        UpdateChecker.check(context) { info, error ->
            if (info != null) {
                _updateInfo.value = info
            } else {
                if (!isAuto) {
                    // Use MainExecutor to show toast
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        if (error != null) {
                            Toast.makeText(context, context.getString(R.string.vm_update_failed, error), Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, context.getString(R.string.vm_up_to_date), Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
    }

    /**
     * 通过清空更新信息来关闭更新弹窗。
     */
    fun dismissUpdateDialog() {
        _updateInfo.value = null
        _installUri.value = null
    }

    fun clearInstallUri() {
        _installUri.value = null
    }

    fun startUpdateDownload(context: Context) {
        val info = _updateInfo.value ?: return
        if (_isDownloading.value) return
        _isDownloading.value = true
        _downloadProgress.value = 0
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val client = okhttp3.OkHttpClient()
                val request = okhttp3.Request.Builder().url(info.downloadUrl).build()
                val response = client.newCall(request).execute()
                if (!response.isSuccessful) throw java.io.IOException("Unexpected code $response")
                val body = response.body ?: throw java.io.IOException("Empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                val dir = java.io.File(context.getExternalFilesDir(null), "Updates")
                if (!dir.exists()) dir.mkdirs()
                val outFile = java.io.File(dir, info.filename)
                body.byteStream().use { input ->
                    java.io.FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var bytesRead: Int
                        var sum = 0L
                        while (true) {
                            bytesRead = input.read(buffer)
                            if (bytesRead == -1) break
                            output.write(buffer, 0, bytesRead)
                            sum += bytesRead
                            if (total > 0) {
                                val pct = ((sum * 100) / total).toInt().coerceIn(0, 100)
                                _downloadProgress.value = pct
                            }
                        }
                        output.flush()
                    }
                }
                _downloadProgress.value = 100
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileProvider",
                    outFile
                )
                _installUri.value = uri
            } catch (e: Exception) {
                KailLog.e(getApplication(), "LocationSimVM", "startUpdateDownload failed", e)
                _isDownloading.value = false
            } finally {
                _isDownloading.value = false
            }
        }
    }

    fun checkAnnouncement() {
        viewModelScope.launch(Dispatchers.IO) {
            RuoYiClient.getNoticeList().onSuccess { list ->
                _noticeList.value = list
            }
        }
    }

    fun dismissNotice() {
        _noticeList.value = emptyList()
    }

    fun loadRecords() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = mutableListOf<HistoryRecord>()
            val database = db
            if (database != null) {
                try {
                    val cursor = database.query(
                        DataBaseHistoryLocation.TABLE_NAME, null,
                        DataBaseHistoryLocation.DB_COLUMN_ID + " > ?", arrayOf("0"),
                        null, null, DataBaseHistoryLocation.DB_COLUMN_TIMESTAMP + " DESC", null
                    )
                    while (cursor.moveToNext()) {
                        val id = cursor.getInt(0)
                        val location = cursor.getString(1)
                        val longitude = cursor.getString(2)
                        val latitude = cursor.getString(3)
                        val timeStamp = cursor.getInt(4).toLong()
                        val bd09Longitude = cursor.getString(5)
                        val bd09Latitude = cursor.getString(6)
                        list.add(
                            HistoryRecord(
                                id = id,
                                name = location,
                                longitudeWgs84 = longitude,
                                latitudeWgs84 = latitude,
                                timestamp = timeStamp,
                                longitudeBd09 = bd09Longitude,
                                latitudeBd09 = bd09Latitude,
                                displayTime = com.kail.location.utils.GoUtils.timeStamp2Date(timeStamp.toString()),
                                displayWgs84 = "",
                                displayBd09 = ""
                            )
                        )
                    }
                    cursor.close()
                } catch (_: Exception) {}
            }
            _historyRecords.value = list
        }
    }

    fun selectRecord(record: HistoryRecord) {
        _selectedRecordId.value = record.id
        _locationInfo.value = _locationInfo.value.copy(
            name = record.name,
            address = record.name,
            latitude = record.latitudeBd09.toDoubleOrNull() ?: 0.0,
            longitude = record.longitudeBd09.toDoubleOrNull() ?: 0.0
        )
    }

    fun renameRecord(id: Int, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db?.let { DataBaseHistoryLocation.updateHistoryLocation(it, id.toString(), newName) }
            } catch (_: Exception) {}
            loadRecords()
        }
    }

    fun deleteRecord(id: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db?.delete(DataBaseHistoryLocation.TABLE_NAME, DataBaseHistoryLocation.DB_COLUMN_ID + " = ?", arrayOf(id.toString()))
            } catch (_: Exception) {}
            loadRecords()
            if (_selectedRecordId.value == id) {
                _selectedRecordId.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        startTimeoutJob?.cancel()
        try {
            getApplication<Application>().unregisterReceiver(statusReceiver)
        } catch (_: Exception) {}
    }
}
