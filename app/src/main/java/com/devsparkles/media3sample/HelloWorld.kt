package com.devsparkles.media3sample

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.media3.exoplayer.ExoPlayer

class HelloWorldViewModel : ViewModel() {



    fun loadPlayer(context: Context) {

        // that's how you createa an ExoPlayer instance.
        val player = ExoPlayer.Builder(context).build()



    }
}