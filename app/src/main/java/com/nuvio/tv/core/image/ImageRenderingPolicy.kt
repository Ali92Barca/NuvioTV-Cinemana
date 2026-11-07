package com.nuvio.tv.core.image

import android.app.ActivityManager
import android.content.Context

/** Reduce simultaneous bitmap work and double-image blending on constrained TV devices. */
internal fun imageTransitionsEnabled(context: Context): Boolean =
    (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice != true

internal fun imageDecodeParallelism(context: Context): Int = if (imageTransitionsEnabled(context)) 4 else 2
