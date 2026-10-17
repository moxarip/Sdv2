package com.example.engine

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import java.io.File

data class DetectedStorageVolume(
    val description: String,
    val isRemovable: Boolean,
    val state: String,
    val uuid: String?,
    val path: String?,
    val isMounted: Boolean,
    val requiresFormatWarning: Boolean
)

object StorageVolumeDetector {

    fun getDetectedVolumes(context: Context): List<DetectedStorageVolume> {
        val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val list = mutableListOf<DetectedStorageVolume>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val volumes: List<StorageVolume> = storageManager.storageVolumes
            for (vol in volumes) {
                val desc = vol.getDescription(context)
                val isRem = vol.isRemovable
                val state = vol.state
                val uuid = vol.uuid
                val dir = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) vol.directory?.absolutePath else null
                val isMounted = state == Environment.MEDIA_MOUNTED || state == Environment.MEDIA_MOUNTED_READ_ONLY
                val requiresFormat = state == Environment.MEDIA_UNMOUNTABLE ||
                        state == Environment.MEDIA_UNMOUNTED ||
                        state == "bad_removal"

                list.add(
                    DetectedStorageVolume(
                        description = desc,
                        isRemovable = isRem,
                        state = state,
                        uuid = uuid,
                        path = dir,
                        isMounted = isMounted,
                        requiresFormatWarning = requiresFormat
                    )
                )
            }
        }

        // Also check /storage/ subdirectories
        val storageDir = File("/storage")
        if (storageDir.exists() && storageDir.isDirectory) {
            val sub = storageDir.listFiles() ?: emptyArray()
            for (f in sub) {
                if (f.name != "emulated" && f.name != "self") {
                    if (list.none { it.path == f.absolutePath || it.uuid == f.name }) {
                        list.add(
                            DetectedStorageVolume(
                                description = "External Volume (${f.name})",
                                isRemovable = true,
                                state = if (f.canRead()) Environment.MEDIA_MOUNTED else "unmounted",
                                uuid = f.name,
                                path = f.absolutePath,
                                isMounted = f.canRead(),
                                requiresFormatWarning = !f.canRead()
                            )
                        )
                    }
                }
            }
        }

        return list
    }
}
