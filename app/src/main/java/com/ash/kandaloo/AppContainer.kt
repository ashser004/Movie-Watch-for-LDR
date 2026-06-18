package com.ash.kandaloo

import android.content.Context
import com.ash.kandaloo.data.PreferencesManager
import com.ash.kandaloo.service.RoomManager

class AppContainer(context: Context) {
    val preferencesManager = PreferencesManager(context)
    val roomManager = RoomManager()
}
