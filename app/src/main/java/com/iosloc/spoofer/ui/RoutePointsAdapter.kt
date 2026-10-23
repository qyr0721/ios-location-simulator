package com.iosloc.spoofer.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.iosloc.spoofer.databinding.ItemRoutePointBinding
import com.iosloc.spoofer.location.GeoPoint

class RoutePointsAdapter(
    private val points: MutableList<GeoPoint>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<RoutePointsAdapter.VH>() {

    inner class VH(val b: ItemRoutePointBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemRoutePointBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return VH(b)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val p = points[position]
        holder.b.tvIndex.text = "${position + 1}"
        holder.b.tvPoint.text = "%.6f, %.6f".format(p.lat, p.lon) +
            if (p.name.isNotEmpty()) "  ${p.name}" else ""
        holder.b.btnDelete.setOnClickListener {
            onDelete(holder.bindingAdapterPosition)
        }
    }

    override fun getItemCount() = points.size
}
