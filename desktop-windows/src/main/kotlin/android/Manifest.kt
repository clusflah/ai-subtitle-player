package android

object Manifest {
    object permission {
        const val READ_EXTERNAL_STORAGE = "android.permission.READ_EXTERNAL_STORAGE"
        const val WRITE_EXTERNAL_STORAGE = "android.permission.WRITE_EXTERNAL_STORAGE"
        const val READ_MEDIA_VIDEO = "android.permission.READ_MEDIA_VIDEO"
        const val READ_MEDIA_AUDIO = "android.permission.READ_MEDIA_AUDIO"
        const val READ_MEDIA_IMAGES = "android.permission.READ_MEDIA_IMAGES"
        const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
        const val RECORD_AUDIO = "android.permission.RECORD_AUDIO"
    }
}

object R {
    object attr {
        const val progressBarStyleHorizontal = 1
    }
    object color {
        const val transparent = 0
    }
    object drawable {
        const val ic_btn_speak_now = 1
        const val ic_media_play = 2
        const val stat_sys_download = 4
        const val stat_sys_download_done = 3
    }
}
