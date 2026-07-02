package ru.netology.nmedia.adapter


import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.paging.LoadState
import ru.netology.nmedia.R

class PostsLoadStateViewHolder(
    parent: ViewGroup
) : RecyclerView.ViewHolder(
    LayoutInflater.from(parent.context)
        .inflate(R.layout.item_load_state, parent, false)
) {

    private val progressBar: ProgressBar = itemView.findViewById(R.id.progress_bar)
    private val errorText: TextView = itemView.findViewById(R.id.error_text)

    fun bind(loadState: LoadState) {
        if (loadState is LoadState.Error) {
            errorText.text = loadState.error.localizedMessage
        } else {
            errorText.text = null
        }


        progressBar.visibility = if (loadState is LoadState.Loading) View.VISIBLE else View.GONE
        errorText.visibility = if (loadState is LoadState.Error) View.VISIBLE else View.GONE
    }
}