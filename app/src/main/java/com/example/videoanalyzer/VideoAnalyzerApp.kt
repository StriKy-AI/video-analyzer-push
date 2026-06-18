package com.example.videoanalyzer

import android.app.Application
import com.example.videoanalyzer.data.PreferencesRepository
import com.example.videoanalyzer.data.VideoAnalyzerRepository

/**
 * Tiny manual DI container. Avoids pulling in Hilt for what amounts to a
 * handful of singletons — keeps the APK smaller and the build simpler.
 */
class VideoAnalyzerApp : Application() {

    lateinit var preferences: PreferencesRepository
        private set

    lateinit var repository: VideoAnalyzerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferences = PreferencesRepository(this)
        repository = VideoAnalyzerRepository(this, preferences)
    }

    companion object {
        @Volatile private var instance: VideoAnalyzerApp? = null
        fun get(): VideoAnalyzerApp = instance ?: error("VideoAnalyzerApp not yet created")
    }
}