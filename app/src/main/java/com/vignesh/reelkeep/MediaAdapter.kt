package com.vignesh.reelkeep

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.load

/** Grid of small previews with a tick box on each, for posts with many photos/videos. */
class MediaAdapter(private val onSelectionChanged: () -> Unit) :
    RecyclerView.Adapter<MediaAdapter.Holder>() {

    var items: List<MediaItem> = emptyList()
        private set
    val selected = mutableSetOf<Int>()

    fun submit(list: List<MediaItem>) {
        items = list
        selected.clear()
        selected.addAll(list.indices)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun setAll(on: Boolean) {
        selected.clear()
        if (on) selected.addAll(items.indices)
        notifyDataSetChanged()
        onSelectionChanged()
    }

    fun selectedItems(): List<MediaItem> = items.filterIndexed { i, _ -> i in selected }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        val check: CheckBox = view.findViewById(R.id.check)
        val badge: TextView = view.findViewById(R.id.badge)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_media, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.thumb.load(item.thumbUrl) { crossfade(true) }
        holder.check.isChecked = position in selected
        holder.badge.text = (if (item.isVideo) "▶ " else "") + "${item.width}×${item.height}"
        holder.itemView.alpha = if (position in selected) 1f else 0.55f
        holder.itemView.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p == RecyclerView.NO_POSITION) return@setOnClickListener
            if (!selected.remove(p)) selected.add(p)
            notifyItemChanged(p)
            onSelectionChanged()
        }
    }

    override fun getItemCount(): Int = items.size
}
