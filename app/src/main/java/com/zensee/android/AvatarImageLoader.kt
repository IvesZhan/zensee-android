package com.zensee.android

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import android.util.LruCache
import android.view.View
import android.widget.ImageView
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestManager
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

object AvatarImageLoader {
    private const val LOG_TAG = "AvatarImageLoader"
    private const val MAX_AVATAR_SIZE_PX = 384
    private val TAG_KEY = R.id.avatarImageLoaderTag
    private val urlCache = object : LruCache<String, String>(64) {
        override fun sizeOf(key: String, value: String): Int {
            return (value.length / 1024).coerceAtLeast(1)
        }
    }

    fun load(
        imageView: ImageView,
        avatarUrl: String?,
        fallbackView: View? = null
    ) {
        val normalizedUrl = avatarUrl?.trim().orEmpty()
        imageView.setTag(TAG_KEY, normalizedUrl)
        val requestManager = requestManager(imageView)
        requestManager.clear(imageView)

        if (normalizedUrl.isBlank()) {
            showFallback(imageView, fallbackView)
            return
        }

        urlCache.get(normalizedUrl)?.let { cachedUrl ->
            showLoading(imageView, fallbackView)
            requestImage(requestManager, imageView, fallbackView, normalizedUrl, cachedUrl)
            return
        }

        showLoading(imageView, fallbackView)
        requestImage(requestManager, imageView, fallbackView, normalizedUrl, normalizedUrl)
    }

    private fun requestImage(
        requestManager: RequestManager,
        imageView: ImageView,
        fallbackView: View?,
        requestKey: String,
        imageUrl: String
    ) {
        requestManager
            .load(imageUrl)
            .override(MAX_AVATAR_SIZE_PX, MAX_AVATAR_SIZE_PX)
            .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
            .skipMemoryCache(false)
            .centerCrop()
            .transition(DrawableTransitionOptions.withCrossFade())
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(TAG_KEY) == requestKey) {
                        val causeSummary = e?.rootCauses
                            ?.joinToString(" | ") { cause ->
                                "${cause.javaClass.simpleName}:${cause.message.orEmpty()}"
                            }
                            .orEmpty()
                        Log.w(LOG_TAG, "load failed url=$imageUrl causes=$causeSummary", e)
                        showFallback(imageView, fallbackView)
                    }
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    if (imageView.getTag(TAG_KEY) == requestKey) {
                        urlCache.put(requestKey, imageUrl)
                        imageView.isVisible = true
                        fallbackView?.isVisible = false
                    }
                    return false
                }
            })
            .into(imageView)
    }

    private fun requestManager(imageView: ImageView): RequestManager {
        val context = imageView.context
        val activity = context.findActivity()
        if (activity != null && (activity.isDestroyed || activity.isFinishing)) {
            return Glide.with(context.applicationContext)
        }
        return runCatching { Glide.with(imageView) }
            .getOrElse { Glide.with(context.applicationContext) }
    }

    private fun showLoading(imageView: ImageView, fallbackView: View?) {
        imageView.setImageDrawable(null)
        imageView.isInvisible = true
        fallbackView?.isVisible = true
    }

    private fun showFallback(imageView: ImageView, fallbackView: View?) {
        imageView.setImageDrawable(null)
        imageView.isVisible = false
        fallbackView?.isVisible = true
    }

    private tailrec fun Context.findActivity(): Activity? {
        return when (this) {
            is Activity -> this
            is ContextWrapper -> baseContext.findActivity()
            else -> null
        }
    }
}
