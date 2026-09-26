package me.rosuh.filepicker.adapter

import android.graphics.Rect
import androidx.core.view.GestureDetectorCompat
import androidx.recyclerview.widget.RecyclerView
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.CheckBox
import android.widget.RadioButton
import me.rosuh.filepicker.R

/**
 *
 * @author rosu
 * @date 2018/11/29
 * 列表点击监听器，监听列表的点击并分辨出为单击、长按和子项被点击
 * OnItemTouchListener 无法轻易实现对子控件点击事件的监听
 *
 */
class RecyclerViewListener(
    val recyclerView: RecyclerView,
    val itemClickListener: OnItemClickListener
) :
    RecyclerView.OnItemTouchListener {

    /**
     * Custom item click listener, receive item event and redispatch
     */
    interface OnItemClickListener {

        /**
         * Item click
         */
        fun onItemClick(
            adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
            view: View,
            position: Int
        )

        /**
         * Item long click
         */
        fun onItemLongClick(
            adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
            view: View,
            position: Int
        )

        /**
         * Item child click
         */
        fun onItemChildClick(
            adapter: RecyclerView.Adapter<RecyclerView.ViewHolder>,
            view: View,
            position: Int
        )
    }

    private var gestureDetectorCompat: GestureDetectorCompat =
        GestureDetectorCompat(recyclerView.context, ItemTouchHelperGestureListener())

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        gestureDetectorCompat.onTouchEvent(e)
    }

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        return gestureDetectorCompat.onTouchEvent(e)
    }

    override fun onRequestDisallowInterceptTouchEvent(p0: Boolean) {}

    /**
     * 查找 item 中被点击的目标子视图（复选框/单选按钮）
     * @return 被点击的子视图 ID；如果点击在文本/图标/空白区域，返回 null
     */
    private fun findTargetChildView(itemView: View, touchX: Float, touchY: Float): Int? {
        // 将 RecyclerView 坐标转换为 itemView 本地坐标
        val localX = touchX - itemView.left
        val localY = touchY - itemView.top

        // 检查是否点击在复选框上
        val checkBox = itemView.findViewById<CheckBox>(R.id.cb_list_file_picker)
        if (checkBox != null && checkBox.visibility == View.VISIBLE) {
            val rect = Rect()
            checkBox.getHitRect(rect)
            // 扩大复选框的点击区域，提升可用性
            rect.inset(-40, -20)
            if (rect.contains(localX.toInt(), localY.toInt())) {
                return R.id.cb_list_file_picker
            }
        }

        // 检查是否点击在单选按钮上（单选模式）
        val radioButton = itemView.findViewById<RadioButton>(R.id.rb_list_file_picker)
        if (radioButton != null && radioButton.visibility == View.VISIBLE) {
            val rect = Rect()
            radioButton.getHitRect(rect)
            rect.inset(-40, -20)
            if (rect.contains(localX.toInt(), localY.toInt())) {
                return R.id.rb_list_file_picker
            }
        }

        return null
    }

    inner class ItemTouchHelperGestureListener : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val childView = recyclerView.findChildViewUnder(e.x, e.y)
            childView ?: return false
            when (childView.id) {
                R.id.item_list_file_picker -> {
                    val targetChildId = findTargetChildView(childView, e.x, e.y)
                    val position = recyclerView.getChildLayoutPosition(childView)
                    when (targetChildId) {
                        // 点击复选框/单选按钮: 触发子视图点击（切换选中状态）
                        R.id.cb_list_file_picker,
                        R.id.rb_list_file_picker -> {
                            itemClickListener.onItemChildClick(
                                recyclerView.adapter!!,
                                childView,
                                position
                            )
                        }
                        // 点击图标或文本/空白区域: 触发 item 点击（进入文件夹/选中文件）
                        else -> {
                            itemClickListener.onItemClick(
                                recyclerView.adapter!!,
                                childView,
                                position
                            )
                        }
                    }
                }
                R.id.item_nav_file_picker -> {
                    itemClickListener.onItemClick(
                        recyclerView.adapter!!,
                        childView,
                        recyclerView.getChildLayoutPosition(childView)
                    )
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val childView = recyclerView.findChildViewUnder(e.x, e.y)
            childView ?: return
            when (childView.id) {
                R.id.item_list_file_picker -> {
                    itemClickListener.onItemLongClick(
                        recyclerView.adapter!!,
                        childView,
                        recyclerView.getChildLayoutPosition(childView)
                    )
                }
            }
        }
    }
}
