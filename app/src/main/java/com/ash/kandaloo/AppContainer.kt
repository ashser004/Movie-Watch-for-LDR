package com.ash.kandaloo

import android.content.Context
import androidx.room.Room
import com.ash.kandaloo.data.KanDalooDatabase
import com.ash.kandaloo.data.PreferencesManager
import com.ash.kandaloo.service.RoomManager

class AppContainer(context: Context) {
    val preferencesManager = PreferencesManager(context)
    val roomManager = RoomManager()

    val database: KanDalooDatabase = Room.databaseBuilder(
        context.applicationContext,
        KanDalooDatabase::class.java,
        "kandaloo_db"
    ).fallbackToDestructiveMigration().build()

    val roomSessionDao = database.roomSessionDao()
}
