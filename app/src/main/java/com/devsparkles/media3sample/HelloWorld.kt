package com.devsparkles.media3sample

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.media3.exoplayer.ExoPlayer

class HelloWorldViewModel : ViewModel() {



    fun loadPlayer(context: Context) {

        // that's how you createa an ExoPlayer instance.
        val player = ExoPlayer.Builder(context).build()


        // that is the main thread
        player.applicationLooper


        // the thread from which an exoplayer instance
        // must be accessed can be explicitly specified by passing a Looper


    }
}