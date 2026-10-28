package com.maintiflow.pro

import android.app.Application
import android.content.Context
import com.maintiflow.pro.data.Repository
import com.maintiflow.pro.data.SessionStore
import com.maintiflow.pro.data.api.ApiClient
import com.maintiflow.pro.data.local.LocalDb
import com.maintiflow.pro.master.MasterController

/** Injection de dépendances manuelle : simple à lire et à maintenir. */
class AppContainer(context: Context) {
    val session = SessionStore(context)
    val client = ApiClient(session)
    val db = LocalDb.build(context)
    val repository = Repository(context, session, client, db)
    val master = MasterController(context)
}

class MaintiFlowApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
