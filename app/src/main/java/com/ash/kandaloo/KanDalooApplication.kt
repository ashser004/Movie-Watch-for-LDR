package com.ash.kandaloo

import android.app.Application
import com.google.firebase.database.FirebaseDatabase

class KanDalooApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        FirebaseDatabase.getInstance("https://kandaloo-default-rtdb.asia-southeast1.firebasedatabase.app")
            .setPersistenceEnabled(true)
    }
}