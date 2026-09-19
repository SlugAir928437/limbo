package me.rosuh.filepicker.engine

import android.content.Context
import android.widget.ImageView
import com.squareup.picasso.Picasso
import java.io.File

/**
 * @author rosu
 * @date 2020-04-15
 * An [ImageEngine] implementation by using Picasso
 */
class PicassoEngine : ImageEngine {
    override fun loadImage(
        context: Context?,
        imageView: ImageView?,
        url: String?,
        placeholder: Int
    ) {
        if (url?.startsWith("http") == true) {
            Picasso.get()
                .load(url)
                .placeholder(placeholder)
                .into(imageView)
        } else {
            Picasso.get()
                .load(File(url))
                .placeholder(placeholder)
                .into(imageView)
        }
    }
}