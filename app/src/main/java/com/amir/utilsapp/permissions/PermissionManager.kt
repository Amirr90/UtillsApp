package com.amir.utilsapp.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.annotation.ChecksSdkIntAtLeast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.amir.utilsapp.R
import java.lang.ref.WeakReference

class PermissionManager private constructor(private val lifecycleOwner: WeakReference<LifecycleOwner>) :
    DefaultLifecycleObserver {

    private val requiredPermissions = mutableListOf<Permissions>()
    private var rationaleDescription: String? = null
    private var rationaleTitle: String? = null
    private var permanentlyDeniedDescription: String? = null
    private var callback: (Boolean) -> Unit = {}
    private var intent: Intent? = null
    private var detailedCallback: (Map<String, Boolean>) -> Unit = {}
    private val deniedList = arrayListOf<String>()
    private lateinit var permissionCheck: ActivityResultLauncher<Array<String>>
    private var activity: AppCompatActivity? = null

    init {
        lifecycleOwner.get()?.lifecycle?.addObserver(this)
    }

    override fun onCreate(owner: LifecycleOwner) {
        permissionCheck = if (owner is AppCompatActivity) {
            owner.registerForActivityResult(RequestMultiplePermissions()) { grantResults ->
                sendResultAndCleanUp(grantResults)
            }
        } else {
            (owner as Fragment).registerForActivityResult(RequestMultiplePermissions()) { grantResults ->
                sendResultAndCleanUp(grantResults)
            }
        }
        activity =
            if (lifecycleOwner.get() is Fragment) (lifecycleOwner.get() as? Fragment)?.context?.scanForActivity() else lifecycleOwner.get() as? AppCompatActivity
        super.onCreate(owner)
    }

    companion object {
        fun from(lifecycleOwner: LifecycleOwner) = PermissionManager(WeakReference(lifecycleOwner))

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
        fun sdkEqOrAbove33() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.Q)
        fun sdkEqOrAbove29() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.R)
        fun sdkEqOrAbove30() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.S)
        fun sdkEqOrAbove31() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.P)
        fun sdkEqOrAbove28() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        @SuppressLint("ObsoleteSdkInt")
        @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.M)
        fun sdkEqOrAbove23() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    fun rationale(
        description: String,
        title: String = activity?.getString(R.string.permission_title) ?: "",
    ): PermissionManager {
        rationaleDescription = description
        rationaleTitle = title
        return this
    }

    fun request(vararg permission: Permissions): PermissionManager {
        requiredPermissions.addAll(permission)
        return this
    }

    fun permissionPermanentlyDeniedIntent(intent: Intent): PermissionManager {
        this.intent = intent
        return this
    }

    fun permissionPermanentlyDeniedContent(description: String = ""): PermissionManager {
        this.permanentlyDeniedDescription =
            description.ifEmpty { activity?.getString(R.string.permission_description) }
        return this
    }

    fun checkAndRequestPermission(callback: (Boolean) -> Unit) {
        this.callback = callback
        handlePermissionRequest()
    }

    fun checkAndRequestDetailedPermission(callback: (Map<String, Boolean>) -> Unit) {
        this.detailedCallback = callback
        handlePermissionRequest()
    }

    private fun handlePermissionRequest() {
        // 1 TRUE -> When user has denied the permission at-least once -> rationale alert-dialog show
        // 2 FALSE -> i. User has never requested the permission -> Show permission pop-up
        //            ii. User has denied the permission permanently -> Settings
        activity?.let { activity ->
            if (areAllPermissionsGranted(activity)) {
                sendPositiveResult()
            } else if (shouldShowPermissionRationale(activity)) {
                getPermissionList().forEach {
                    PermissionsPreferences.updatePermissionStatus(it, true)
                }
                val requiresRationaleList =
                    getPermissionList().map { Pair(it, requiresRationale(activity, it)) }
                displayRationale(
                    activity,
                    getCommaSeparatedFormattedString(requiresRationaleList.filter { it.second }
                        .map { it.first })
                )
            } else {
                if (getPermissionList().any { !PermissionsPreferences.getPermissionStatus(it) }) {
                    requestPermissions()
                } else {
                    displayPermanentlyDenied(
                        activity, getCommaSeparatedFormattedString(getPermissionList().filter {
                            isPermanentlyDenied(
                                activity, it
                            )
                        })
                    )
                    cleanUp()
                }
            }
        }
    }

    private fun displayRationale(activity: AppCompatActivity, permission: String?) {
        AlertDialog.Builder(activity)
            .setTitle(rationaleTitle ?: activity.getString(R.string.permission_title))
            .setMessage(
                rationaleDescription ?: activity.getString(R.string.permission_description)
            ).setCancelable(true)
            .setNegativeButton(activity.getString(R.string.no_thanks)) { dialog, _ ->
                dialog.dismiss()
                cleanUp()
            }
            .setPositiveButton(activity.getString(R.string.button_ok)) { _, _ -> requestPermissions() }
            .show()
    }

    @SuppressLint("StringFormatInvalid")
    private fun displayPermanentlyDenied(activity: AppCompatActivity, permission: String?) {
        AlertDialog.Builder(activity).setTitle(activity.getString(R.string.permission_title))
            .setMessage(
                permanentlyDeniedDescription ?: activity.getString(
                    R.string.permission_description_permanently, permission ?: ""
                )
            ).setCancelable(true)
            .setNegativeButton(activity.getString(R.string.no_thanks)) { dialog, _ ->
                dialog.dismiss()
                cleanUp()
            }.setPositiveButton(activity.getString(R.string.go_to_settings)) { _, _ ->
                val finalIntent = if (intent != null) {
                    intent
                } else {
                    val intent2 = Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.packageName)
                    )
                    intent2.addCategory(Intent.CATEGORY_DEFAULT)
                    intent2.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    intent2
                }
                activity.startActivity(finalIntent)
            }.show()
    }

    private fun sendPositiveResult() {
        sendResultAndCleanUp(getPermissionList().associateWith { true })
    }

    private fun sendResultAndCleanUp(grantResults: Map<String, Boolean>) {
        if (deniedList.isNotEmpty()) {
            activity?.let {
                displayPermanentlyDenied(
                    it, getCommaSeparatedFormattedString(deniedList)
                )
            }
        } else {
            callback(grantResults.all { it.value })
            detailedCallback(grantResults)
        }
        cleanUp()
    }

    private fun cleanUp() {
        requiredPermissions.clear()
        rationaleDescription = null
        permanentlyDeniedDescription = null
        deniedList.clear()
        callback = {}
        detailedCallback = {}
    }

    // 4 -> 2 NEW 2 Permanently denied
    private fun requestPermissions() {
        val list = getPermissionList()
        val deniedList = list.filter { isPermanentlyDenied(activity, it) }
        this.deniedList.addAll(deniedList)
        val finalList = list.subtract(deniedList.toSet())
        permissionCheck.launch(finalList.toTypedArray())
    }

    private fun areAllPermissionsGranted(activity: AppCompatActivity) =
        requiredPermissions.all { it.isGranted(activity) }

    private fun shouldShowPermissionRationale(activity: AppCompatActivity) =
        requiredPermissions.any { it.requiresRationale(activity) }

    private fun getPermissionList() =
        requiredPermissions.flatMap { it.permissions.toList() }.toTypedArray()

    private fun Permissions.isGranted(activity: AppCompatActivity) =
        permissions.all { hasPermission(activity, it) }

    private fun Permissions.requiresRationale(activity: AppCompatActivity): Boolean {
        return permissions.any { activity.shouldShowRequestPermissionRationale(it) }
    }

    private fun requiresRationale(activity: AppCompatActivity?, permission: String) =
        activity?.shouldShowRequestPermissionRationale(permission) ?: false

    private fun isPermanentlyDenied(activity: AppCompatActivity?, permission: String) =
        !requiresRationale(activity, permission) && PermissionsPreferences.getPermissionStatus(
            permission
        )

    private fun hasPermission(activity: AppCompatActivity, permission: String) =
        ContextCompat.checkSelfPermission(
            activity, permission
        ) == PackageManager.PERMISSION_GRANTED

    private fun getCommaSeparatedFormattedString(permissions: List<String>): String? {
        val newList = mapPermissionsToStrings(permissions)
        val list = newList.toMutableList()
        return if (list.size == 1) {
            list.first()
        } else {
            list.removeLast()
            val string = list.joinToString(", ")
            string + " and " + newList.last()
        }
    }

    private fun mapPermissionsToStrings(list: List<String>): List<String?> {
        return list.map {
            when (it) {
                Manifest.permission.POST_NOTIFICATIONS -> "Push Notification"
                Manifest.permission.WAKE_LOCK -> "Wake Lock"
                Manifest.permission.INTERNET -> "Internet"
                Manifest.permission.CAMERA -> "Camera"
                Manifest.permission.ACCESS_NETWORK_STATE -> "Access Network State"
                Manifest.permission.READ_CALENDAR -> "Read Calender"
                Manifest.permission.WRITE_CALENDAR -> "Write Calender"
                Manifest.permission.READ_EXTERNAL_STORAGE -> "Read External Storage"
                Manifest.permission.READ_MEDIA_IMAGES -> "Read Media Images"
                Manifest.permission.READ_MEDIA_VIDEO -> "Read Media Video"
                Manifest.permission.READ_MEDIA_AUDIO -> "Read Media Audio"
                Manifest.permission.SCHEDULE_EXACT_ALARM -> "Schedule Exact Alarm"
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> "Write External Storage"
                Manifest.permission.READ_PHONE_STATE -> "Read Phone State"
                Manifest.permission.READ_PHONE_NUMBERS -> "Read Phone Numbers"
                Manifest.permission.GET_ACCOUNTS -> "Get Accounts"
                Manifest.permission.FOREGROUND_SERVICE -> "Foreground Services"
                Manifest.permission.ACCESS_FINE_LOCATION -> "Access Fine Locations"
                Manifest.permission.RECEIVE_BOOT_COMPLETED -> "Receive Boot Completed"
                Manifest.permission.READ_CONTACTS -> "Read Contacts"
                Manifest.permission.RECORD_AUDIO -> "Record Audio"
                Manifest.permission.ACCESS_WIFI_STATE -> "Access WIfi State"
                Manifest.permission.MODIFY_AUDIO_SETTINGS -> "Modify Audio Settings"
                Manifest.permission.BLUETOOTH -> "Bluetooth"
                Manifest.permission.BLUETOOTH_CONNECT -> "BlueTooth Connect"
                Manifest.permission.ACTIVITY_RECOGNITION -> "Activity Recognition"
                Manifest.permission.USE_FULL_SCREEN_INTENT -> "Full Screen Content"
                Manifest.permission.VIBRATE -> "Vibrate"
                else -> "Other"
            }
        }
    }
}

fun Context.scanForActivity(): AppCompatActivity? {
    return when (this) {
        is AppCompatActivity -> this
        is ContextWrapper -> baseContext.scanForActivity()
        else -> {
            null
        }
    }
}