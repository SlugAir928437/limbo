package me.rosuh.filepicker.engine

import android.content.Context
import android.widget.ImageView

/**
 * Glide is not bundled with this build. Kept as a compile-time stub so the
 * vendored FilePicker library keeps its structure; the runtime reflection
 * check (Class.forName("com.bumptech.glide.Glide")) never enables it.
 */
class GlideEngine : ImageEngine {
    override fun loadImage(
        context: Context?,
        imageView: ImageView?,
        url: String?,
        placeholder: Int
    ) {
        if (context == null || imageView == null) {
            return
        }
        imageView.setImageResource(placeholder)
    }
}
