package com.ringpass.app

import android.app.Application
import com.ringpass.app.data.local.RingPassDatabase
import com.ringpass.app.data.repository.TrustedRuleRepository
import com.ringpass.app.dnd.DndController
import com.ringpass.app.util.NotificationHelper

class RingPassApplication : Application() {

    lateinit var database: RingPassDatabase
        private set

    lateinit var repository: TrustedRuleRepository
        private set

    lateinit var dndController: DndController
        private set

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.ensureChannels(this)
        database = RingPassDatabase.getInstance(this)
        repository = TrustedRuleRepository(database.trustedRuleDao())
        dndController = DndController(this)
    }
}
