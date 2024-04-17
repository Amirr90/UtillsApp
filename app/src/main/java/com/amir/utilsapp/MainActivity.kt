package com.amir.utilsapp

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import com.amir.utilsapp.databinding.ActivityMainBinding
import com.amir.utilsapp.extension.showToast
import com.amir.utilsapp.permissions.PermissionManager
import com.amir.utilsapp.permissions.Permissions

class MainActivity : AppCompatActivity() {

    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding
    private val permissionManager = PermissionManager.from(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)

        val navController = findNavController(R.id.nav_host_fragment_content_main)
        appBarConfiguration = AppBarConfiguration(navController.graph)
        setupActionBarWithNavController(navController, appBarConfiguration)

        binding.fab.setOnClickListener {
            checkPermissionsAndAccessFeature()
        }

    }


    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }




    private fun checkPermissionsAndAccessFeature() {
        val intentWhenDeniedPermanently = Intent()
        permissionManager
            .request(Permissions.Camera)
            .rationale(
                description = "Please approve permission to access this feature",
                title = "Permission required"
            )
            .permissionPermanentlyDeniedIntent(intentWhenDeniedPermanently)
            .permissionPermanentlyDeniedContent(description = "To access this feature we need permission please provide access to app from app settings")
            .checkAndRequestPermission {
                if (it) openNewImagePicker()
                else
                    showToast("Permission Needed")
            }
    }

    private fun openNewImagePicker() {
        showToast("Permission Approved")
    }

}