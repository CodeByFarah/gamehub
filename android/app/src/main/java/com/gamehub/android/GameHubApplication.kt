package com.gamehub.android

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

/**
 * Hilt entry point.
 *
 * Dependency injection is used here for the same reason as on the backend:
 * a ViewModel that constructs its own repository cannot be tested without a
 * network, and a repository that constructs its own Retrofit instance cannot
 * be pointed at a fake.
 */
@HiltAndroidApp
class GameHubApplication : Application()
