package com.mrsep.musicrecognizer.feature.recognition.presentation.ext

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import androidx.annotation.Px
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.transform.RoundedCornersTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun Context.getCachedImageOrNull(
    url: String,
    @Px widthPx: Int,
    @Px heightPx: Int,
    @Px cornerRadiusPx: Int? = null,
): Bitmap? {
    return withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(this@getCachedImageOrNull)
            .data(url)
            .size(widthPx, heightPx)
            .networkCachePolicy(CachePolicy.DISABLED)
            .apply {
                cornerRadiusPx?.let {
                    transformations(RoundedCornersTransformation(cornerRadiusPx.toFloat()))
                }
            }
            .build()
        val bitmap = when (val result = imageLoader.execute(request)) {
            is SuccessResult -> (result.drawable as? BitmapDrawable)?.bitmap
            is ErrorResult -> null
        }
        bitmap
    }
}

suspend fun Context.downloadImageToDiskCache(url: String) {
    withContext(Dispatchers.IO) {
        val request = ImageRequest.Builder(this@downloadImageToDiskCache)
            .data(url)
            .networkCachePolicy(CachePolicy.ENABLED)
            .diskCachePolicy(CachePolicy.ENABLED)
            // Disable reading from/writing to the memory cache
            .memoryCachePolicy(CachePolicy.DISABLED)
            // Set a custom `Decoder.Factory` that skips the decoding step
            .decoderFactory { _, _, _ ->
                Decoder { DecodeResult(ColorDrawable(Color.BLACK), false) }
            }
            .build()
        imageLoader.execute(request)
    }
}