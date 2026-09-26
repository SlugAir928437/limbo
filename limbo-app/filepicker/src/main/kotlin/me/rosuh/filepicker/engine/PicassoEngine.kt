package me.rosuh.filepicker.engine

import android.content.Context
import android.widget.ImageView

/**
 * Picasso is not bundled with this build. Kept as a compile-time stub so the
 * vendored FilePicker library keeps its structure; the runtime reflection
 * check (Class.forName("com.squareup.picasso.Picasso")) never enables it.
 */
class PicassoEngine : ImageEngine {
    override fun loadImage(
        context: Context?,
        imageView: ImageView?,
        url: String?,
        placeholder: Int
    ) {
        if (imageView == null) {
            return
        }
        imageView.setImageResource(placeholder)
    }
}
